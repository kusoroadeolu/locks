package io.github.kusoroadeolu.locks.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class UnparkCallLatencyBench {

    volatile boolean stop = false;
    Thread parked;

    @Setup(Level.Trial)
    public void startThread() {
        CountDownLatch latch = new CountDownLatch(1);
        parked = Thread.ofPlatform().start(() ->  {
            latch.countDown();
            while (!stop) {
                LockSupport.park();
            }
        });

        try {
            latch.await();
        }catch (InterruptedException _){}
    }

    @Setup(Level.Invocation)
    public void awaitParked() {
        while (parked.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }
    }

    @TearDown(Level.Trial)
    public void stop() {
        stop = true;
        LockSupport.unpark(parked);
    }


    @Benchmark
    public void unpark() {
        LockSupport.unpark(parked);
    }


    void main() throws Exception {
            new Runner(
                    new OptionsBuilder()
                            .include(UnparkCallLatencyBench.class.getSimpleName())
                            .build())
                    .run();
    }
}
