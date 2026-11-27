package io.github.kusoroadeolu.locks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

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
 * I believe effectiveness of the relay trick does depend on 2 things
 * 1. The length of the critical section
 * 2. The max number of spinning waiters
 *
 * If the (time taken to exec the critical section * number of spinning waiters)
 * is less than the time for the os to schedule a thread back onto
 * the cpu, the relay trick doesn't help much here.
 *
 * I still expect lock handoff to dominate critical section cost
 *just not as bad as other fair locks
 * */
public class RelayLock implements FairLock {

    final AtomicInt nowServing;
    final Ticket ticket;
    final WaitList waitList;
    final Slot[] spinArray;
    final int mask;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();
    private static final int SPINS_PER_YIELD = 1 << 6;


    public RelayLock(int spinArrayLength) {
        int wql = Math.max(1, roundToPowerOfTwo(spinArrayLength));
        spinArray = new Slot[wql];
        waitList = new WaitList();
        ticket = new Ticket();
        nowServing = new AtomicInt();
        mask = wql - 1;

        VarHandle.releaseFence();
        for (int i = 0; i < wql; ++i) {
            spinArray[i] = new Slot();
            if (i == 0) spinArray[0].signal();
        }
        VarHandle.fullFence();
    }

    public RelayLock() {
        this(NCPU / 2);
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

        public boolean isSignalled() {
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
        var spinArray = this.spinArray;
        int mask = this.mask;
        var waitList = this.waitList;

        int len = mask + 1;
        boolean parking = false;
        for (;;) {
            int nowServing = this.nowServing.get();
            boolean inSpinWindow = (ourTicket - nowServing) < len;
            if (inSpinWindow) {

                if (parking)
                    waitList.remove(ourTicket);

                int index = ourTicket & mask;
                Slot s = spinArray[index];
                awaitTurn(s);
                s.clearState();
                return;

            }else {
                //check if we can acquire, enqueue, check if we can acquire, then park
                if (!parking) {
                    waitList.add(ourTicket);
                    parking = true;
                } else {
                    LockSupport.park(this);
                }

            }
        }
    }


    @Override
    public void unlock() {
        int ourTicket = nowServing.getPlain(); //our ticket
        var spinArray = this.spinArray;
        int nextTicket = ourTicket + 1;
        int mask = this.mask;
        int len = mask + 1;

        Slot next = spinArray[nextTicket & mask];
        nowServing.set(nextTicket);
        next.signal(); //handoff to next waiter

        int waiters = ticket.get() - ourTicket;

        if (waiters > len) {
            int lookahead = ourTicket + len;
            Thread waiter = waitList.get(lookahead); //ties the ordering
            //lookahead the length of the queue to signal a farther waiter
            LockSupport.unpark(waiter);
        }

    }

    static void awaitTurn(Slot s) {
        for (int i = 0; !s.isSignalled();) {
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


    //Variant of the harris micheal linked list
    static class WaitList {
        final Node head;
        final static Object DUMMY = new Object();

        WaitList() {
            this.head = new Node(0, null);
        }

        //Could manually hoist an acquire fence before each loop and use plain accesses for reads (once)
        //Though not needed now
        //null -> marked, dummy -> dummy
        public void add(int ticket) {
            Node node = new Node(ticket, Thread.currentThread());
            Node h = head;
            restartFromHead: for (;;) {

                Node pred = h;
                Node curr = h.nextAcquire();

                for (;;) {

                    if (curr != null) {
                        Object waiter = curr.waiterAcquire();

                        if (waiter == DUMMY) continue restartFromHead;

                        if (waiter == null) {
                            curr = helpUnlink(pred, curr);
                            continue;
                        }
                    }

                    boolean enqueue = curr == null || curr.ticket > ticket;

                    if (enqueue) {
                        node.next = curr;
                        if (pred.casNext(curr, node)) return;
                        else curr = pred.next;
                        continue;
                    }

                    pred = curr; curr = pred.nextAcquire();
                }
            }
        }

        //should always be present
        public void remove(int ticket) {
            Node h = head;
            restartFromHead: for (;;) {
                Node pred = h;
                Node curr = h.nextAcquire();

                for (;;) {
                    Object waiter = curr.waiterAcquire();

                    if (waiter == DUMMY) continue restartFromHead;

                    if (waiter == null) {
                        curr = helpUnlink(pred, curr);
                        continue;
                    }


                    boolean remove = ticket == curr.ticket;

                    if (remove) {
                        WAITER.setRelease(curr, null);
                        helpUnlink(pred, curr);
                        return;
                    }

                    pred = curr; curr = pred.nextAcquire();
                }
            }
        }


        public Thread get(int ticket) {
            Node node = head.nextVolatile();

            for (;;) {
                if (node == null || node.ticket > ticket) return null;

                Object waiter = node.waiter;

                if (waiter == DUMMY) {
                    node = node.nextVolatile();
                    continue;
                }

                if (ticket == node.ticket)
                    return (Thread) waiter;


                node = node.nextVolatile();
            }
        }

        Node helpUnlink(Node pred, Node curr) {
            Node dummy = new Node(0, DUMMY);
            Node n;

            for (;;) {
                n = curr.nextAcquire();
                dummy.next = n;


                if (n != null && n.waiter == DUMMY) {
                    n = n.nextAcquire(); //get the dummy's next ptr
                    //could possibly use a plain read here
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

            public boolean casNext(Node from, Node to) {
                return NEXT.compareAndSet(this, from, to);
            }

            public Node nextAcquire() {
                return (Node) NEXT.getAcquire(this);
            }

            public Node nextVolatile() {
                return (Node) NEXT.getAcquire(this);
            }

            public Object waiterAcquire() {
                return WAITER.getAcquire(this);
            }

        }


        static final VarHandle NEXT;
        static final VarHandle WAITER;

        static {
            var l = MethodHandles.lookup();
            try {
                WAITER = l.findVarHandle(Node.class, "waiter", Object.class);
                NEXT = l.findVarHandle(Node.class, "next", Node.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

}