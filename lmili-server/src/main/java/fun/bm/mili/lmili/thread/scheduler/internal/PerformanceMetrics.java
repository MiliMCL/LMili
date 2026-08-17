package fun.bm.mili.lmili.thread.scheduler.internal;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.PerformanceSnapshot;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 性能指标收集器 —— 低开销的运行时性能追踪。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>写入无锁</b>：使用 LongAdder/AtomicLong，避免同步阻塞</li>
 *   <li><b>P99 近似</b>：使用分位数采样算法，无需存储所有数据点</li>
 *   <li><b>快照隔离</b>：快照读取时不阻塞写入</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有计数器操作都是原子性的，多线程并发写入不会丢失数据。
 * 快照操作读取的是某一时刻的值，不保证完全一致性。
 */
public final class PerformanceMetrics {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 计数器 ----
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final LongAdder failedTasks = new LongAdder();
    private final LongAdder cancelledTasks = new LongAdder();
    private final LongAdder totalTaskNanos = new LongAdder();

    // ---- Region 追踪 ----
    private final ConcurrentHashMap<Long, RegionMetrics> regionMetrics = new ConcurrentHashMap<>();
    private final AtomicLong activeRegionCount = new AtomicLong(0);

    // ---- 直方图（用于 P99 计算）----
    private final AtomicLongArray histogram = new AtomicLongArray(BUCKET_COUNT);
    private static final int BUCKET_COUNT = 64;
    private static final long MAX_NANOS = 10_000_000L; // 10ms

    // ---- 对象池命中统计 ----
    private final LongAdder poolHits = new LongAdder();
    private final LongAdder poolMisses = new LongAdder();

    // ---- Carrier 线程 ----
    private volatile int carrierThreadCount = 0;

    /**
     * 创建性能收集器。
     */
    public PerformanceMetrics() {
        updateCarrierThreadCount();
    }

    /**
     * 记录任务提交。
     */
    public void recordSubmit() {
        submittedTasks.increment();
    }

    /**
     * 记录任务完成。
     *
     * @param durationNanos 任务执行耗时（纳秒）
     */
    public void recordCompletion(final long durationNanos) {
        completedTasks.increment();
        totalTaskNanos.add(durationNanos);
        recordHistogram(durationNanos);
    }

    /**
     * 记录任务失败。
     */
    public void recordFailure() {
        failedTasks.increment();
    }

    /**
     * 记录任务取消。
     */
    public void recordCancellation() {
        cancelledTasks.increment();
    }

    /**
     * 记录对象池命中。
     */
    public void recordPoolHit() {
        poolHits.increment();
    }

    /**
     * 记录对象池未命中（创建新对象）。
     */
    public void recordPoolMiss() {
        poolMisses.increment();
    }

    /**
     * 记录 Region 任务提交。
     */
    public void recordRegionSubmit(final long regionId) {
        regionMetrics.computeIfAbsent(regionId, RegionMetrics::new)
                .recordSubmit();
    }

    /**
     * 记录 Region 任务完成。
     */
    public void recordRegionCompletion(final long regionId) {
        RegionMetrics rm = regionMetrics.get(regionId);
        if (rm != null) rm.recordCompletion();
    }

    /**
     * 获取 P99 延迟。
     *
     * <p>使用直方图近似，不需要存储所有样本。
     */
    public long getP99Nanos() {
        long total = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            total += histogram.get(i);
        }
        if (total == 0) return 0;

        long threshold = (long) (total * 0.99);
        long cumulative = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += histogram.get(i);
            if (cumulative >= threshold) {
                return bucketUpperBound(i);
            }
        }
        return MAX_NANOS;
    }

    /**
     * 获取平均任务时间。
     */
    public long getAverageTaskNanos() {
        long completed = completedTasks.sum();
        return completed > 0 ? totalTaskNanos.sum() / completed : 0;
    }

    /**
     * 获取对象池命中率。
     */
    public double getPoolHitRate() {
        long hits = poolHits.sum();
        long misses = poolMisses.sum();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前活跃 region 数。
     */
    public int getActiveRegionCount() {
        return (int) activeRegionCount.get();
    }

    /**
     * 获取当前待处理任务数。
     */
    public int getPendingTaskCount() {
        return (int) (submittedTasks.sum() - completedTasks.sum() - failedTasks.sum() - cancelledTasks.sum());
    }

    /**
     * 获取当前活跃任务数（正在执行的）。
     */
    public int getActiveTaskCount() {
        // 近似值：提交 - 完成 - 失败 - 取消
        return Math.max(0, getPendingTaskCount());
    }

    /**
     * 获取 carrier thread 数量。
     */
    public int getCarrierThreadCount() {
        return carrierThreadCount;
    }

    /**
     * 更新 carrier thread 数量。
     */
    public void updateCarrierThreadCount() {
        try {
            this.carrierThreadCount = ForkJoinPool.commonPool().getParallelism();
        } catch (Exception e) {
            // 忽略，保持上次的值
        }
    }

    /**
     * 创建不可变的性能快照。
     */
    @NotNull
    public PerformanceSnapshot snapshot() {
        updateCarrierThreadCount();
        return new SnapshotImpl(this);
    }

    /**
     * 获取诊断信息映射。
     */
    @NotNull
    public Map<String, Object> diagnostics() {
        Map<String, Object> diag = new HashMap<>();
        diag.put("submitted_tasks", submittedTasks.sum());
        diag.put("completed_tasks", completedTasks.sum());
        diag.put("failed_tasks", failedTasks.sum());
        diag.put("cancelled_tasks", cancelledTasks.sum());
        diag.put("pending_tasks", getPendingTaskCount());
        diag.put("active_regions", getActiveRegionCount());
        diag.put("average_task_nanos", getAverageTaskNanos());
        diag.put("p99_task_nanos", getP99Nanos());
        diag.put("pool_hit_rate", getPoolHitRate());
        diag.put("carrier_threads", carrierThreadCount);
        diag.put("region_count", regionMetrics.size());
        return diag;
    }

    /**
     * 记录直方图数据。
     */
    private void recordHistogram(final long nanos) {
        if (nanos < 0 || nanos > MAX_NANOS) return;
        int bucket = (int) ((nanos * BUCKET_COUNT) / MAX_NANOS);
        if (bucket >= BUCKET_COUNT) bucket = BUCKET_COUNT - 1;
        histogram.incrementAndGet(bucket);
    }

    private long bucketUpperBound(final int bucket) {
        return (long) (((double) (bucket + 1) / BUCKET_COUNT) * MAX_NANOS);
    }

    /**
     * Region 级别的指标。
     */
    private static final class RegionMetrics {
        final long regionId;
        final LongAdder submitted = new LongAdder();
        final LongAdder completed = new LongAdder();
        volatile boolean active = true;

        RegionMetrics(long regionId) {
            this.regionId = regionId;
        }

        void recordSubmit() {
            submitted.increment();
        }

        void recordCompletion() {
            completed.increment();
        }
    }

    /**
     * 不可变的快照实现。
     */
    private static final class SnapshotImpl implements PerformanceSnapshot {
        private final long submitted;
        private final long completed;
        private final long failed;
        private final long cancelled;
        private final int pending;
        private final int active;
        private final int activeRegions;
        private final long avgNanos;
        private final long p99Nanos;
        private final double poolHitRate;
        private final int carrierThreads;
        private final long timestamp;
        private final Map<String, Object> diagnostics;

        SnapshotImpl(PerformanceMetrics metrics) {
            this.submitted = metrics.submittedTasks.sum();
            this.completed = metrics.completedTasks.sum();
            this.failed = metrics.failedTasks.sum();
            this.cancelled = metrics.cancelledTasks.sum();
            this.pending = metrics.getPendingTaskCount();
            this.active = metrics.getActiveTaskCount();
            this.activeRegions = metrics.getActiveRegionCount();
            this.avgNanos = metrics.getAverageTaskNanos();
            this.p99Nanos = metrics.getP99Nanos();
            this.poolHitRate = metrics.getPoolHitRate();
            this.carrierThreads = metrics.carrierThreadCount;
            this.timestamp = System.currentTimeMillis();
            this.diagnostics = metrics.diagnostics();
        }

        @Override public long totalSubmittedTasks() { return submitted; }
        @Override public long totalCompletedTasks() { return completed; }
        @Override public long totalFailedTasks() { return failed; }
        @Override public long totalCancelledTasks() { return cancelled; }
        @Override public int pendingTaskCount() { return pending; }
        @Override public int activeTaskCount() { return active; }
        @Override public int activeRegionCount() { return activeRegions; }
        @Override public long averageTaskNanos() { return avgNanos; }
        @Override public long p99TaskNanos() { return p99Nanos; }
        @Override public double poolHitRate() { return poolHitRate; }
        @Override public int carrierThreadCount() { return carrierThreads; }
        @Override public @NotNull Map<String, Object> diagnostics() { return diagnostics; }
        @Override public long timestampMillis() { return timestamp; }
    }

    // 使用标准库 java.util.concurrent.atomic.AtomicLongArray
}
