package io.github.kusoroadeolu.locks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;

class Pad0 {
    byte b1, b2, b3, b4;
    long l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15;
}

class ValueField extends Pad0 {
    int value;

    static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(ValueField.class, "value", int.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}

class Pad1 extends ValueField {
    long l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16;
}


/*
* A fair non-reentrant augmented ticket lock that tries to
* hide scheduler bottlenecks and wakeup latency that plagues fair locks
* by relaying wake-ups far earlier than they should occur.
*
* In a ticket lock, threads atomically claim tickets and wait on
* a current serving ticket , until it equals their ticket before entering
* some critical section. Here we augment it with a waiting queue/array to
* so threads spin on some slot that corresponds to their ticket to improve locality
* and a concurrent map to allow relayed unparks. Threads in the waiting queue
* never park, however they spin briefly, periodically yielding their CPU time.
* Threads that go in the concurrent map however park immediately.
*
* The main idea here is when a thread releases the lock, and there are parked threads
* it can look ahead and wakeup a parked thread farther into the queue.
* So by the time its turn arrives, it'd already be running on the CPU.
*
* The core algorithm is actually quite simple, however it requires a
* concurrent hashmap. A skip list map can work too, though lookups would
* shift from O(1) to O(log(n)).
*
* The effectiveness of the relay trick does depend on 2 things
* 1. The length of the critical section
* 2. The max number of spinning waiters
*
* If the (time taken to exec the critical section * number of waiters)
* is less than the time for the cpu to schedule a thread back onto
* the cpu, the relay trick doesn't help much here.
*
* My hypothesis going into this is that this should have around 1.5x to 2x less mean latency than a
* fair reentrant lock, though much slower than a non-fair reentrant lock
*
* */
public class RelayLock implements FairLock {

    private final AtomicInt currentSequence;
    private final Sequence sequence;
    private final ConcurrentMap<Integer, Thread> waitMap;
    private final Slot[] waitQueue;
    private final int mask;
    private static final int NCPU = roundToPowerOfTwo(Runtime.getRuntime().availableProcessors());
    private static final int SPINS_PER_YIELD = 1 << 5;


    public RelayLock() {
        int queueLen = Math.max(1, NCPU / 2);
        waitQueue = new Slot[queueLen];
        waitMap = new ConcurrentHashMap<>(NCPU - queueLen);
        sequence = new Sequence();
        currentSequence = new AtomicInt();
        mask = queueLen - 1;

        VarHandle.releaseFence();
        for (int i = 0; i < queueLen; ++i) {
            waitQueue[i] = new Slot();
            if (i == 0) waitQueue[0].signal();
        }
        VarHandle.fullFence();
    }

    static class Sequence extends Pad1 {
        public int nextSequence() {
            return (int) STATE.getAndAdd(this, 1);
        }

        public int get() {
            return (int) STATE.getVolatile(this);
        }
    }

    static class Slot extends Pad1 {

        static final int NEXT = 1;

        public boolean isNext() {
            return (int) STATE.getAcquire(this) == NEXT;
        }

        public void clearState() {
            STATE.set(this, 0);
        }

        public void signal() {
            STATE.setRelease(this, NEXT);
        }
    }

    static class AtomicInt extends Pad1 {
        public int get() {
            return (int) STATE.getVolatile(this);
        }

        public void set(int newState) {
            STATE.setVolatile(this, newState);
        }

        public int getPlain() {
            return (int) STATE.get(this);
        }
    }

    @Override
    public void lock() {
        int seq = sequence.nextSequence();
        var waitQueue = this.waitQueue;
        int len = mask + 1;
        boolean inMap = false;
        for (;;) {
            int currSeq = currentSequence.get();
            if ((seq - currSeq) < len) {

                if (inMap)
                    waitMap.remove(seq);

                int index = seq & mask;
                Slot s = waitQueue[index];
                awaitTurn(s);
                s.clearState();
                return;
            }else {
                //check if we can acquire, enqueue, check if we can acquire, then park
                //remove on unpark
                if (!inMap) {
                    waitMap.put(seq, Thread.currentThread());
                    inMap = true;
                } else {
                    LockSupport.park();
                }

            }
        }
    }


    @Override
    public void unlock() {
        var seq = currentSequence.getPlain(); //our ticket
        var waitQueue = this.waitQueue;
        var nextSeq = seq + 1;
        Slot next = waitQueue[nextSeq & mask];
        currentSequence.set(nextSeq);
        next.signal(); //handoff to next waiter

        int waiters = sequence.get() - seq;
        if (waiters > (mask + 1)) {
            int lookahead = seq + (mask + 1);
            Thread waiter = waitMap.get(lookahead); //ties the ordering
            //lookahead the length of the queue to signal a farther waiter
            LockSupport.unpark(waiter);
        }

    }

    void awaitTurn(Slot s) {
        for (int i = 0; !s.isNext();) {
            if (i < SPINS_PER_YIELD) {
                Thread.onSpinWait();
                ++i;
            } else {
                Thread.yield();
                i = 0;
            }
        }
    }


    static int roundToPowerOfTwo(final int value) {
        return Math.max(1, 1 << (32 - Integer.numberOfLeadingZeros(value - 1)));
    }
}
