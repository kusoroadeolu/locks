package io.github.kusoroadeolu.locks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

class Pad0 {
    byte b1, b2, b3, b4;
    long l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15;
}

class ValueField extends Pad0 {
    int value;

    static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(ValueField.class, "value", int.class);
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
* a current serving field, until it equals their ticket before entering
* some critical section. Here we augment it with a waiting queue/array to
* so threads spin on some slot that corresponds to their ticket to improve locality
* and a linked wait list to allow relayed unparks. Threads in the waiting queue
* never park, however they spin briefly, periodically yielding their CPU time.
* Threads that go in the wait list however park immediately.
*
* The main idea here is when a thread releases the lock, and there are parked threads
* it can look ahead and wakeup a parked thread farther into the queue.
* So by the time its turn arrives, it'd already be running on the CPU.
*
* The effectiveness of the relay trick does depend on 2 things
* 1. The length of the critical section
* 2. The max number of spinning waiters
*
* If the (time taken to exec the critical section * number of spinning waiters)
* is less than the time for the os to schedule a thread back onto
* the cpu, the relay trick doesn't help much here.
*
* My hypothesis going into this is that this should have around 1.5x to 2x less mean latency than a
* fair reentrant lock, though much slower than a non-fair reentrant lock
*
* */
public class RelayLock implements FairLock {

    private final AtomicInt nowServing;
    private final Ticket ticket;
    private final WaitList waitList;
    private final Slot[] waitQueue;
    private final int mask;
    private static final int WAIT_QUEUE_LENGTH = Math.max(1, roundToPowerOfTwo(Runtime.getRuntime().availableProcessors()) / 2);
    private static final int SPINS_PER_YIELD = 1 << 6;


    public RelayLock() {
        waitQueue = new Slot[WAIT_QUEUE_LENGTH];
        waitList = new WaitList();
        ticket = new Ticket();
        nowServing = new AtomicInt();
        mask = WAIT_QUEUE_LENGTH - 1;

        VarHandle.releaseFence();
        for (int i = 0; i < WAIT_QUEUE_LENGTH; ++i) {
            waitQueue[i] = new Slot();
            if (i == 0) waitQueue[0].signal();
        }
        VarHandle.fullFence();
    }

    static class Ticket extends Pad1 {
        public int nextTicket() {
            return (int) VALUE.getAndAdd(this, 1);
        }

        public int get() {
            return (int) VALUE.getVolatile(this);
        }
    }

    static class Slot extends Pad1 {

        static final int NEXT = 1;

        public boolean isNext() {
            return (int) VALUE.getAcquire(this) == NEXT;
        }

        public void clearState() {
            VALUE.set(this, 0);
        }

        public void signal() {
            VALUE.setRelease(this, NEXT);
        }
    }

    static class AtomicInt extends Pad1 {
        public int get() {
            return (int) VALUE.getVolatile(this);
        }

        public void set(int newState) {
            VALUE.setVolatile(this, newState);
        }

        public int getPlain() {
            return (int) VALUE.get(this);
        }
    }

    @Override
    public void lock() {
        int ourTicket = ticket.nextTicket();
        var waitQueue = this.waitQueue;
        int mask = this.mask;
        var waitList = this.waitList;

        int len = mask + 1;
        boolean inMap = false;
        for (;;) {
            int nowServing = this.nowServing.get();
            if ((ourTicket - nowServing) < len) {

                if (inMap)
                    waitList.remove(ourTicket);

                int index = ourTicket & mask;
                Slot s = waitQueue[index];
                awaitTurn(s);
                s.clearState();
                return;

            }else {
                //check if we can acquire, enqueue, check if we can acquire, then park
                if (!inMap) {
                    waitList.add(ourTicket);
                    inMap = true;
                } else {
                    LockSupport.park();
                }

            }
        }
    }


    @Override
    public void unlock() {
        var ourTicket = nowServing.getPlain(); //our ticket
        var waitQueue = this.waitQueue;
        var nextTicket = ourTicket + 1;
        Slot next = waitQueue[nextTicket & mask];
        nowServing.set(nextTicket);
        next.signal(); //handoff to next waiter
        //could get reordered to happen before ticket.get, though not much of an issue

        int waiters = ticket.get() - ourTicket;
        if (waiters > (mask + 1)) {
            int lookahead = ourTicket + (mask + 1);
            Thread waiter = waitList.get(lookahead); //ties the ordering
            //lookahead the length of the queue to signal a farther waiter
            LockSupport.unpark(waiter);
        }

    }

    static void awaitTurn(Slot s) {
        for (int i = 0; !s.isNext();) {
            if  (i < SPINS_PER_YIELD) {
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


    //GC Based variant of the harris micheal linked list
    //Uses fence hoisting to allow plain traversals as the fence synchronizes
    //with volatile cas's that happen before the fence
    static class WaitList {
        final Node head;
        final static Object DUMMY = new Object();

        WaitList() {
            this.head = new Node(0, null);
        }

        //null -> marked, dummy -> dumy
        public void add(int ticket) {
            Node node = new Node(ticket, Thread.currentThread());
            Node h = head;
            outer: for (;;) {

                VarHandle.acquireFence();
                Node pred = h;
                Node curr = h.next;

                for (;;) {
                    if (curr != null) {
                        if (curr.isDummy()) continue outer;

                        if (curr.isMarked()) {
                            curr = helpUnlink(pred, curr);
                            continue;
                        }
                    }

                   boolean enqueue = curr == null || curr.ticket > ticket;

                   if (enqueue) {

                       node.next = curr;
                       if (pred.casNext(curr, node)) return;
                       else curr = pred.next;
                       //pred.next would be made visible by acquire barrier emitted on failed cas
                       continue;
                   }

                   pred = curr; curr = pred.next;
                }
            }
        }

        //should always be present
        public void remove(int ticket) {
            Node h = head;
            outer: for (;;) {

                VarHandle.acquireFence();
                Node pred = h;
                Node curr = h.next;

                for (;;) {

                    if (curr != null) {
                        if (curr.isDummy()) continue outer;

                        if (curr.isMarked()) {
                            curr = helpUnlink(pred, curr);
                            continue;
                        }
                    }

                    boolean remove = curr != null && ticket == curr.ticket;

                    if (remove) {
                        curr.waiter = null;
                        helpUnlink(pred, curr);
                        return;
                    }

                    pred = curr; curr = pred.next;
                }
            }
        }


        public Thread get(int ticket) {
            VarHandle.acquireFence();
            Node node = head.next;

            for (;;) {
                if (node == null || node.ticket > ticket) return null;

                if (node.isDummy()) {
                    node = node.next;
                    continue;
                }



                if (ticket == node.ticket)
                    if (node.isMarked()) return null;
                    else return (Thread) node.waiter;


                node = node.next;
            }
        }

        Node helpUnlink(Node pred, Node curr) {
            Node dummy = new Node(0, DUMMY);
            Node n;
            for (;;) {
                n = curr.next;
                dummy.next = n;

                if (n != null && n.isDummy()) {
                    n = n.next; //get the dummy's next ptr
                    break;
                } else if (curr.casNext(n, dummy))
                    break; //emits an acquire fence, allows later reads to synchronize with this
            }

            pred.casNext(curr, n);
            return n;
        }


        static class Node {
            final int ticket;
            Object waiter;
            Node next;

            public Node(int ticket, Object waiter) {
                this.ticket = ticket;
                this.waiter = waiter;
            }

            public boolean isMarked() {
                return waiter == null;
            }

            public boolean casNext(Node from, Node to) {
                return NEXT.compareAndSet(this, from, to);
            }

            public boolean isDummy() {
                return waiter == DUMMY;
            }
        }


        static final VarHandle NEXT;

        static {
            try {
                NEXT = MethodHandles.lookup().findVarHandle(Node.class, "next", Node.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

}
