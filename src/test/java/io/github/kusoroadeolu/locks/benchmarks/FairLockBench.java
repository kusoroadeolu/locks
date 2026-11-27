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
        @Param({"RelayLock", "ReentrantLock", "BaselineRelayLock"})
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
        public static final int[] THREAD_SWEEP = {6,16, 24, 32, 48, 64};

    }
    //                            .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-fc")

    static void main(String[] args) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        Files.createDirectories(Path.of("target/jmh-results"));
        for (int t : Args.THREAD_SWEEP) {
            if (t > cpus) break;
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

