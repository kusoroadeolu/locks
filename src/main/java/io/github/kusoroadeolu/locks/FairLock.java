package io.github.kusoroadeolu.locks;

public interface FairLock {
    void lock();
    void unlock();
}
