package io.github.kusoroadeolu.locks;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;


public class WaitListStressTest {

    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "List is empty")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "List is not empty")
    @State
    public static class NoDanglingNodes {

        final RelayLock.WaitList l = new RelayLock.WaitList();

        @Actor
        public void actor1() {
            l.add(1);
            l.remove(1);
        }

        @Actor
        public void actor2() {
            l.add(2);
            l.remove(2);
        }

        @Arbiter
        public void arbiter(I_Result r) {
            Thread w1 = l.get(1);
            Thread w2 = l.get(2);
            r.r1 = w1 == null && w2 == null ? 1 : 0;
        }

    }
}
