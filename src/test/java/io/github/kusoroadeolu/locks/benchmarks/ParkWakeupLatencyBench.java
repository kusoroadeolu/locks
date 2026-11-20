package io.github.kusoroadeolu.locks.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ParkWakeupLatencyBench {
    volatile boolean stop = false;
    volatile boolean signalled;
    final AtomicReference<Thread> jmhThread = new AtomicReference<>(null);

    @Setup(Level.Trial)
    public void startThread() {
        jmhThread.set(null);
        stop = false;
        signalled = false;

        Thread.ofPlatform().start(() ->  {
            Thread jt;

            while ((jt = jmhThread.get()) == null) Thread.onSpinWait();

            while (!stop) {
                if (!signalled) {
                    signalled = true;
                    LockSupport.unpark(jt);
                }
            }
        });
    }

    @TearDown(Level.Trial)
    public void stop() {
        stop = true;
    }


    @Benchmark
    public void park() {
        if (jmhThread.getPlain() == null)
            jmhThread.set(Thread.currentThread());
        signalled = false;
        LockSupport.park();
    }


    void main() throws Exception {
        new Runner(
                new OptionsBuilder()
                        .include(ParkWakeupLatencyBench.class.getSimpleName())
                        .build())
                .run();
    }
}
