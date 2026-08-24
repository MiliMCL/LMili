package fun.bm.mili.lmili.runtime.io;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * P99 延迟直方图 —— 无锁环形直方图（2048 槽，纳秒对数分桶，原子槽）。
 * IO worker 线程写（record），控制线程采样读（p99）。
 *
 * <p>精度：对数分桶近似值，误差 ±10% 可接受（ARCHITECTURE_AdaptiveRuntime.md §8.3）。
 */
public final class P99Histogram {

    private static final int SLOTS = 2048;
    private static final long MIN_NANOS = 1_000L;         // 1µs
    private static final long MAX_NANOS = 60_000_000_000L; // 60s
    private static final double BUCKET_RATIO = Math.pow(MAX_NANOS / (double) MIN_NANOS, 1.0 / (SLOTS - 1));

    private final AtomicLongArray buckets = new AtomicLongArray(SLOTS);
    private final LongAdder count = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong(0);

    /** 记录一次延迟（纳秒）。 */
    public void record(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        buckets.incrementAndGet(bucketOf(nanos));
        count.increment();
        // 最大值：CAS 环（低竞争，无自旋风险）
        long cur;
        do {
            cur = maxNanos.get();
            if (cur >= nanos) {
                break;
            }
        } while (!maxNanos.compareAndSet(cur, nanos));
    }

    private static int bucketOf(long nanos) {
        if (nanos <= MIN_NANOS) {
            return 0;
        }
        if (nanos >= MAX_NANOS) {
            return SLOTS - 1;
        }
        final double idx = Math.log(nanos / (double) MIN_NANOS) / Math.log(BUCKET_RATIO);
        return Math.min(SLOTS - 1, (int) idx);
    }

    /** 该分桶代表的最大延迟（用于面板展示，近似） */
    public long bucketUpperBound(int bucket) {
        return (long) (MIN_NANOS * Math.pow(BUCKET_RATIO, bucket + 1));
    }

    /**
     * 近似 p99（纳秒）；无样本返回 0。
     * 从高桶向下扫描直到累计样本达到 1%。
     */
    public long p99() {
        final long total = count.sum();
        if (total == 0) {
            return 0;
        }
        long remaining = total / 100; // 允许最高的 1%
        for (int i = SLOTS - 1; i >= 0; i--) {
            final long c = buckets.get(i);
            if (c > remaining) {
                return bucketUpperBound(i);
            }
            remaining -= c;
        }
        return 0;
    }

    /** 周期内最大延迟 */
    public long maxNanos() {
        return maxNanos.get();
    }

    /** 采样数 */
    public long sampleCount() {
        return count.sum();
    }

    /** 重置（周期窗口开始调用） */
    public void reset() {
        for (int i = 0; i < SLOTS; i++) {
            buckets.set(i, 0);
        }
        count.reset();
        maxNanos.set(0);
    }
}
