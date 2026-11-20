package io.github.kusoroadeolu.locks.benchmarks;

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

        // pop min, insert min + random delta, so size stays constant, no gc interference
        // well though long will get boxed, so
        long hold() {
            long min = h[0];
            siftDown(0, min + (next() & DELTA_MASK));
            return min;
        }
    }

    @State(Scope.Benchmark)
    public static class BaseState {
        @Param({"RelayLock", "ReentrantLock"})
        public String type;
        FairLock lock;

        @Param({"100"})
        public int work;

        @Setup(Level.Trial)
        public void setupLock() {
            lock = type.equals("RelayLock") ? new RelayLock() : new FairReentrantLock();
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
        public static final int[] THREAD_SWEEP = {6, 8 , 16, 24, 32, 48, 64};

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
mvn clean test -Pjmh -DskipTests"
* */

/* 6 Threads
╭─────────────────────────────────────────── io.github.kusoroadeolu.locks.benchmarks.FairLockBench.add ───────────────────────────────────────────╮
│  Lines Work Type            Score (ns/op)      vs best      P00      P50      P90      P95      P99    P99.9    P99.99     P99.999         Max  │
│  ----- ---- ------------- --------------- ------------ -------- -------- -------- -------- -------- -------- --------- ----------- -----------  │
│  5     100  RelayLock     17,993.7 ± 29.3        1.00x    200.0 17,472.0 24,992.0 25,472.0 27,872.0 46,272.0 700,103.9 1,171,803.2 4,177,920.0  │
│             ReentrantLock 34,629.8 ± 33.4 1.92x slower 20,192.0 34,368.0 39,488.0 41,344.0 48,960.0 67,456.0 711,680.0 1,026,095.9 1,421,312.0  │
╰─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
╭────────────────────────────────────────── io.github.kusoroadeolu.locks.benchmarks.FairLockBench.hold ───────────────────────────────────────────╮
│  Size  Work Type            Score (ns/op)      vs best      P00      P50      P90      P95      P99    P99.9    P99.99     P99.999         Max  │
│  ----- ---- ------------- --------------- ------------ -------- -------- -------- -------- -------- -------- --------- ----------- -----------  │
│  65536 100  RelayLock     18,657.1 ± 24.7        1.00x    300.0 17,696.0 25,792.0 26,400.0 28,672.0 45,056.0 695,296.0   982,016.0 1,316,864.0  │
│             ReentrantLock 35,775.7 ± 35.1 1.92x slower 20,992.0 35,648.0 41,088.0 43,264.0 50,560.0 70,016.0 693,248.0 1,340,100.1 2,551,808.0  │
╰─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯

8 Threads

╭─────────────────────────────────────────── io.github.kusoroadeolu.locks.benchmarks.FairLockBench.add ───────────────────────────────────────────╮
│  Lines Work Type            Score (ns/op)      vs best      P00      P50      P90      P95      P99    P99.9    P99.99     P99.999         Max  │
│  ----- ---- ------------- --------------- ------------ -------- -------- -------- -------- -------- -------- --------- ----------- -----------  │
│  5     100  RelayLock     25,588.1 ± 39.1        1.00x    300.0 25,088.0 26,976.0 28,096.0 33,856.0 58,880.0 713,728.0 3,993,600.0 4,218,880.0  │
│             ReentrantLock 46,652.8 ± 41.3 1.82x slower 30,080.0 46,080.0 52,800.0 55,360.0 63,744.0 87,808.0 746,496.0 1,460,224.0 1,484,800.0  │
╰─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
╭─────────────────────────────────────────── io.github.kusoroadeolu.locks.benchmarks.FairLockBench.hold ───────────────────────────────────────────╮
│  Size  Work Type            Score (ns/op)      vs best      P00      P50      P90      P95      P99     P99.9    P99.99     P99.999         Max  │
│  ----- ---- ------------- --------------- ------------ -------- -------- -------- -------- -------- --------- --------- ----------- -----------  │
│  65536 100  RelayLock     26,561.4 ± 41.6        1.00x    600.0 25,984.0 28,000.0 28,992.0 35,840.0  78,848.0 716,800.0 4,038,656.0 4,382,720.0  │
│             ReentrantLock 51,531.3 ± 38.1 1.94x slower 28,800.0 48,960.0 67,328.0 73,088.0 83,584.0 111,872.0 725,393.5 1,006,592.0 1,542,144.0  │
╰──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
* */