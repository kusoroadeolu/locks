package io.github.kusoroadeolu.locks;

import java.util.concurrent.locks.ReentrantLock;

public class FairReentrantLock implements FairLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    @Override
    public void lock() {
        lock.lock();
    }

    @Override
    public void unlock() {
        lock.unlock();
    }
}
