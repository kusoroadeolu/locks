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

import static io.github.kusoroadeolu.locks.benchmarks.FairLockBench.Args;

@Fork(value = 3, jvmArgs = {Args.INITIAL_HEAP_SIZE, Args.MAX_HEAP_SIZE, Args.GC_TYPE})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FairLockBench {

    static final class Data {
        static final int STRIDE = 16; // 16 longs = 128 bytes, should dodge intels adjacent-line prefetch, we could make this 8 though
        static final int PAD = 12;    // we keep the first counter away from the array header, is this necessary though? could remove this later on
        final long[] x;
        final int lines;

        Data(int lines) {
            this.lines = lines;
            this.x = new long[PAD + lines * STRIDE];
        }

        long add() {
            long old = x[PAD];
            for (int i = 0; i < lines; i++) {
                x[PAD + i * STRIDE] = old + 1;
            }
            return old;
        }
    }

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

        @Param({"100"})
        public int work;

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
    public static class CacheLineLockState extends BaseState {
        @Param({"5"})
        public int lines;

        Data data;

        @Setup(Level.Trial)
        public void setup() {
            data = new Data(lines);
        }
    }

    @State(Scope.Benchmark)
    public static class HeapLockState extends BaseState {
        @Param({"65536"})   // 512 KB of longs
        public int size;

        HeapData data;

        @Setup(Level.Trial)
        public void setup() {
            data = new HeapData(size);
        }
    }

    @Benchmark
    public long add(CacheLineLockState s) {
        long r;
        s.lock.lock();
        try {
            r = s.data.add();
        } finally {
            s.lock.unlock();
        }
        Blackhole.consumeCPU(s.work);
        return r;
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
        Blackhole.consumeCPU(s.work);
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
            if (t > (cores * 2)) break;
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
| 6       | add   | RelayLock         | 17,840.2 ± 26.7  | 300.0    | 17,696.0 | 24,576.0 | 25,280.0 | 27,680.0 | 47,313.5 | 697,344.0 | 1,174,636.0 | 1,935,360.0 |
| 6       | add   | ReentrantLock     | 37,196.4 ± 32.2  | 20,096.0 | 36,992.0 | 42,176.0 | 43,776.0 | 51,200.0 | 71,680.0 | 714,752.0 | 1,018,804.3 | 1,488,896.0 |
| 6       | add   | BaselineRelayLock | 37,672.6 ± 32.8  | 200.0    | 37,888.0 | 42,752.0 | 44,544.0 | 51,584.0 | 74,624.0 | 713,728.0 | 1,015,886.8 | 1,257,472.0 |
| 6       | hold  | RelayLock         | 18,728.0 ± 27.7  | 400.0    | 18,080.0 | 25,696.0 | 26,272.0 | 28,672.0 | 49,280.0 | 700,416.0 | 1,368,965.0 | 1,685,504.0 |
| 6       | hold  | ReentrantLock     | 37,762.0 ± 35.4  | 20,800.0 | 37,696.0 | 42,752.0 | 44,800.0 | 51,456.0 | 76,288.0 | 713,728.0 | 1,231,064.9 | 1,812,480.0 |
| 6       | hold  | BaselineRelayLock | 38,156.2 ± 33.4  | 8,592.0  | 38,656.0 | 43,840.0 | 45,568.0 | 52,544.0 | 74,496.0 | 716,079.4 | 1,012,736.0 | 1,267,712.0 |
| 8       | add   | RelayLock         | 25,691.1 ± 27.2  | 5,200.0  | 25,088.0 | 27,200.0 | 28,288.0 | 34,496.0 | 75,776.0 | 716,800.0 | 967,272.2   | 1,730,560.0 |
| 8       | add   | ReentrantLock     | 49,131.4 ± 33.5  | 29,280.0 | 48,576.0 | 54,400.0 | 56,448.0 | 63,744.0 | 87,296.0 | 722,944.0 | 1,022,156.2 | 1,271,808.0 |
| 8       | add   | BaselineRelayLock | 50,395.8 ± 32.1  | 22,176.0 | 49,856.0 | 56,384.0 | 59,200.0 | 68,992.0 | 89,600.0 | 727,040.0 | 1,261,898.5 | 1,318,912.0 |
| 8       | hold  | RelayLock         | 26,740.8 ± 27.3  | 500.0    | 26,176.0 | 28,192.0 | 29,280.0 | 35,968.0 | 74,880.0 | 715,776.0 | 997,376.0   | 1,198,080.0 |
| 8       | hold  | ReentrantLock     | 49,470.7 ± 30.4  | 30,880.0 | 48,768.0 | 54,848.0 | 56,896.0 | 63,744.0 | 81,920.0 | 728,064.0 | 1,012,396.1 | 2,654,208.0 |
| 8       | hold  | BaselineRelayLock | 51,024.3 ± 29.0  | 8,688.0  | 50,368.0 | 57,792.0 | 60,480.0 | 68,992.0 | 93,952.0 | 729,088.0 | 1,010,688.0 | 1,566,720.0 |
| 16      | add   | RelayLock         | 51,403.0 ± 22.3  | 17,792.0 | 50,560.0 | 53,888.0 | 55,744.0 | 65,280.0 | 120,576.0 | 751,616.0 | 1,221,438.6 | 1,560,576.0 |
| 16      | add   | ReentrantLock     | 94,537.9 ± 26.0  | 58,944.0 | 93,824.0 | 102,528.0 | 105,984.0 | 117,888.0 | 150,923.5 | 770,048.0 | 1,050,624.0 | 1,218,560.0 |
| 16      | add   | BaselineRelayLock | 99,933.1 ± 50.4  | 60,096.0 | 98,560.0 | 109,440.0 | 114,176.0 | 132,864.0 | 204,032.0 | 1,046,528.0 | 3,068,038.9 | 3,743,744.0 |
| 16      | hold  | RelayLock         | 52,826.0 ± 24.0  | 23,776.0 | 51,968.0 | 54,976.0 | 56,576.0 | 66,432.0 | 121,472.0 | 750,592.0 | 1,245,184.0 | 2,248,704.0 |
| 16      | hold  | ReentrantLock     | 99,819.6 ± 31.9  | 66,304.0 | 98,560.0 | 108,416.0 | 113,280.0 | 133,376.0 | 180,736.0 | 794,624.0 | 1,085,383.8 | 1,468,416.0 |
| 16      | hold  | BaselineRelayLock | 101,788.8 ± 33.6 | 60,160.0 | 100,736.0 | 110,336.0 | 114,688.0 | 130,816.0 | 173,568.0 | 797,696.0 | 1,271,808.0 | 2,588,672.0 |
*/