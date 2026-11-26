package io.github.kusoroadeolu.locks.benchmarks;

import io.github.kusoroadeolu.locks.BaselineRelayLock;
import io.github.kusoroadeolu.locks.FairLock;
import io.github.kusoroadeolu.locks.FairReentrantLock;
import io.github.kusoroadeolu.locks.RelayLock;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kusoroadeolu.locks.benchmarks.FairLockBench.Args;

@Fork(value = 3, jvmArgs = {Args.INITIAL_HEAP_SIZE, Args.MAX_HEAP_SIZE, Args.GC_TYPE})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FairLockBench {

    static final class HeapData {
        static final int DELTA_MASK = (1 << 20) - 1;
        final long[] h;
        final int n;
        long seed = 0x9E3779B97F4A7C15L;
        private static final int RANDOM_SEED = 32;

        HeapData(int n) {
            this.n = n;
            this.h = new long[n];
            var r = new java.util.SplittableRandom(RANDOM_SEED);
            for (int i = 0; i < n; i++) h[i] = r.nextInt(1 << 20);
            for (int i = n / 2 - 1; i >= 0; i--) siftDown(i, h[i]);
        }

        private long next() { // xor shift
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            return seed >>> 1;
        }

        private void siftDown(int i, long v) {
            for (;;) {
                int l = 2 * i + 1;
                if (l >= n) break;
                int r = l + 1;
                int c = (r < n && h[r] < h[l]) ? r : l;
                if (h[c] >= v) break;
                h[i] = h[c];
                i = c;
            }
            h[i] = v;
        }

        long hold() {
            long min = h[0];
            siftDown(0, min + (next() & DELTA_MASK));
            return min;
        }
    }

    @State(Scope.Benchmark)
    public static class BaseState {
        @Param({"RelayLock", "BaselineRelayLock", "ReentrantLock"})
        public String type;
        FairLock lock;

        @Setup(Level.Trial)
        public void setupLock() {
            lock = switch (type) {
                case "RelayLock" -> new RelayLock();
                case "BaselineRelayLock" -> new BaselineRelayLock();
                case "ReentrantLock" -> new FairReentrantLock(); //Fair JDK reentrant lock
                default -> throw new IllegalArgumentException();
            };
        }
    }



    @State(Scope.Benchmark)
    public static class HeapLockState extends BaseState {
        @Param({"65536"})
        public int size;

        HeapData data;

        @Setup(Level.Trial)
        public void setup() {
            data = new HeapData(size);
        }
    }

    @Benchmark
    public long hold(HeapLockState s) {
        long r;
        s.lock.lock();
        try {
            r = s.data.hold();
        } finally {
            s.lock.unlock();
        }
        return r;
    }




    static class Args {
        public static final String INITIAL_HEAP_SIZE = "-Xms4g";
        public static final String MAX_HEAP_SIZE = "-Xmx4g";
        public static final String GC_TYPE = "-XX:+UseG1GC";
        public static final int[] THREAD_SWEEP = {6, 8,16, 24, 32, 48, 64};

    }
    //                            .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")

    static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        Files.createDirectories(Path.of("target/jmh-results"));
        for (int t : Args.THREAD_SWEEP) {
            if (t > cores) break;
            new Runner(
                    new OptionsBuilder()
                            .include(FairLockBench.class.getSimpleName())
                            .threads(t)
                            .resultFormat(ResultFormatType.JSON)
                            .result("target/jmh-results/jmh-threads-" + t + ".json")
                            .build())
                    .run();
        }
    }
}

/*
mvn clean test -Pjmh
* */

/* intel i5 4 core 8 cpu Windows

| Threads | Bench | Lock              | Score (ns/op)    | P00      | P50     | P90     | P95     | P99     | P99.9   | P99.99    | P99.999   | Max       |
|---------|-------|-------------------|------------------|----------|---------|---------|---------|---------|---------|-----------|-----------|-----------|
| 6       | hold  | RelayLock         | 18,728.0 ± 27.7  | 400.0    | 18,080.0 | 25,696.0 | 26,272.0 | 28,672.0 | 49,280.0 | 700,416.0 | 1,368,965.0 | 1,685,504.0 |
| 6       | hold  | ReentrantLock     | 37,762.0 ± 35.4  | 20,800.0 | 37,696.0 | 42,752.0 | 44,800.0 | 51,456.0 | 76,288.0 | 713,728.0 | 1,231,064.9 | 1,812,480.0 |
| 6       | hold  | BaselineRelayLock | 38,156.2 ± 33.4  | 8,592.0  | 38,656.0 | 43,840.0 | 45,568.0 | 52,544.0 | 74,496.0 | 716,079.4 | 1,012,736.0 | 1,267,712.0 |
| 8       | hold  | RelayLock         | 26,740.8 ± 27.3  | 500.0    | 26,176.0 | 28,192.0 | 29,280.0 | 35,968.0 | 74,880.0 | 715,776.0 | 997,376.0   | 1,198,080.0 |
| 8       | hold  | ReentrantLock     | 49,470.7 ± 30.4  | 30,880.0 | 48,768.0 | 54,848.0 | 56,896.0 | 63,744.0 | 81,920.0 | 728,064.0 | 1,012,396.1 | 2,654,208.0 |
| 8       | hold  | BaselineRelayLock | 51,024.3 ± 29.0  | 8,688.0  | 50,368.0 | 57,792.0 | 60,480.0 | 68,992.0 | 93,952.0 | 729,088.0 | 1,010,688.0 | 1,566,720.0 |
*/