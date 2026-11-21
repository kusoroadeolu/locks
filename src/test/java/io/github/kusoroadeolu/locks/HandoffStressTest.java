package io.github.kusoroadeolu.locks;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicInteger;

@JCStressTest
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both saw each other")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Advancer found waiter, will wake it")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Waiter saw the advance, won't park")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost wakeup")
@State
public class HandoffStressTest {

    //We want to ensure sequential ordering after a volatile write for get
    //And after a volatile read for enqueue
    final AtomicInteger var = new AtomicInteger(0);
    final RelayLock.WaitList l = new RelayLock.WaitList();


    //Actor cos playing the thread advancing the current ticket
    @Actor
    public void advancer(II_Result result) {
        var.set(1);
        Thread waiter = l.get(1);
        result.r1 = waiter == null ? 0 : 1;
    }

    //Actor cos playing a thread about to park
    @Actor
    public void waiter(II_Result result) {
        l.add(1);
        result.r2 = var.get();
    }

    //Forbidden - 0, 0
}

/*
* mvn clean test -Pjcstress -DskipTests "-Djcstress.mode=tough" "-Djcstress.forks=3" "-Djcstress.test=.*HandoffStressTest"
* */
