package io.github.kusoroadeolu.locks;


import java.util.concurrent.locks.LockSupport;

public class BaselineRelayLock extends RelayLock{

    @Override
    public void unlock() {
        var ourTicket = nowServing.getPlain(); //our ticket
        var waitQueue = this.waitQueue;
        var nextTicket = ourTicket + 1;
        int mask = this.mask;

        RelayLock.Slot next = waitQueue[nextTicket & mask];
        nowServing.set(nextTicket);
        next.signal(); //handoff to next waiter

        //No lookahead here
        Thread waiter = waitList.get(nextTicket);
        LockSupport.unpark(waiter); // has a null branch, so if this is null, no sys call
    }
}
