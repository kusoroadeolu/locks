package io.github.kusoroadeolu.locks.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class XaddBench {

    Xadd xadd;

    @Setup(Level.Iteration)
    public void setup() {
        xadd = new Xadd();
    }

    @Benchmark
    @Threads(8)
    public void xaddVolatile(Blackhole bh) {
        bh.consume(xadd.xadd());
    }

    @Benchmark
    @Threads(8)
    public void xaddAcquire(Blackhole bh) {
        bh.consume(xadd.xaddAcquire());
    }

    @Benchmark
    @Threads(8)
    public void xaddRelease(Blackhole bh) {
        bh.consume(xadd.xaddRelease());
    }

    @Benchmark
    @Threads(8)
    public void cas(Blackhole bh) {
        bh.consume(xadd.cas());
    }

    static class Pad0 {
        int i1;
        long l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15;
    }

    static class XaddField extends Pad0 {
        int xadd;

        static final VarHandle XADD;

        static {
            try {
                XADD = MethodHandles.lookup().findVarHandle(XaddField.class, "xadd", int.class);
            }catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public int xadd() {
            return (int) XADD.getAndAdd(this, 1);
        }

        public int xaddAcquire() {
            return (int) XADD.getAndAddAcquire(this, 1);
        }

        public int xaddRelease() {
            return (int) XADD.getAndAddRelease(this, 1);
        }

        public int cas() {
            int x;
            do {
                x = (int) XADD.getAcquire(this);
            }while (!XADD.weakCompareAndSet(this, x, x + 1));
            return x;
        }

    }

    static class Xadd extends XaddField {
        long l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16;
    }

    void main() throws Exception {
        new Runner(
                new OptionsBuilder()
                        .include(XaddBench.class.getSimpleName())
                        .build())
                .run();
    }

}

/* Yeah, so looks like my PC doesn't have a primitive xadd acq/rel option and these
are just implemented underneath using a weak cas
Benchmark               Mode  Cnt    Score    Error  Units
XaddBench.cas           avgt   15  979.792 ± 90.936  ns/op
XaddBench.xaddAcquire   avgt   15  967.454 ± 94.114  ns/op
XaddBench.xaddRelease   avgt   15  972.044 ± 98.592  ns/op
XaddBench.xaddVolatile  avgt   15  139.567 ± 12.764  ns/op
* */
