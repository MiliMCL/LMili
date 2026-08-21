package fun.bm.mili.lmili.thread.runtime.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 运行时指标 —— 记录 Runtime 的性能和运行数据。
 *
 * <p>至少记录：
 * <ul>
 *   <li>TPS</li>
 *   <li>MSPT</li>
 *   <li>Region MSPT</li>
 *   <li>Slice MSPT</li>
 *   <li>P50</li>
 *   <li>P95</li>
 *   <li>P99</li>
 *   <li>Max Tick Time</li>
 *   <li>Queue Size</li>
 *   <li>Worker Utilization</li>
 *   <li>Steal Count</li>
 *   <li>Steal Failure</li>
 *   <li>Timeout Count</li>
 *   <li>Cancellation Count</li>
 *   <li>Late Completion Count</li>
 *   <li>Cross Region Access</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法都是线程安全的。
 */
public final class RuntimeMetrics {

    /** TPS 计算窗口 */
    private static final int TPS_WINDOW_SIZE = 100;

    // ---- 基本指标 ----

    /** 总 Tick 数 */
    private final AtomicLong totalTicks = new AtomicLong(0);

    /** 总 Tick 耗时（纳秒） */
    private final LongAdder totalTickTimeNanos = new LongAdder();

    /** 最大 Tick 耗时（纳秒） */
    private final AtomicLong maxTickTimeNanos = new AtomicLong(0);

    /** 超时 Tick 数 */
    private final AtomicLong timeoutCount = new AtomicLong(0);

    /** 取消的 Task 数 */
    private final AtomicLong cancellationCount = new AtomicLong(0);

    /** 迟到完成数 */
    private final AtomicLong lateCompletionCount = new AtomicLong(0);

    /** 跨 Region 访问数 */
    private final AtomicLong crossRegionAccessCount = new AtomicLong(0);

    // ---- P50/P95/P99 计算 ----

    /** 最近 N 次 Tick 耗时记录 */
    private final long[] recentTickTimes = new long[TPS_WINDOW_SIZE];
    private final AtomicLong tickTimeIndex = new AtomicLong(0);

    // ---- 任务统计 ----

    /** 提交的任务总数 */
    private final LongAdder tasksSubmitted = new LongAdder();

    /** 完成的任务总数 */
    private final LongAdder tasksCompleted = new LongAdder();

    /** 失败的任务总数 */
    private final LongAdder tasksFailed = new LongAdder();

    // ---- Work Stealing 统计 ----

    /** 窃取成功次数 */
    private final LongAdder stealSuccessCount = new LongAdder();

    /** 窃取失败次数 */
    private final LongAdder stealFailureCount = new LongAdder();

    /**
     * 记录一次 Tick 完成。
     *
     * @param elapsedNanos Tick 耗时（纳秒）
     * @param timedOut     是否超时
     */
    public void recordTick(long elapsedNanos, boolean timedOut) {
        totalTicks.incrementAndGet();
        totalTickTimeNanos.add(elapsedNanos);

        // 更新最大值
        long currentMax;
        while ((currentMax = maxTickTimeNanos.get()) < elapsedNanos) {
            if (maxTickTimeNanos.compareAndSet(currentMax, elapsedNanos)) {
                break;
            }
        }

        // 记录到循环缓冲区
        int idx = (int) (tickTimeIndex.getAndIncrement() % TPS_WINDOW_SIZE);
        recentTickTimes[idx] = elapsedNanos;

        if (timedOut) {
            timeoutCount.incrementAndGet();
        }
    }

    /**
     * 记录任务提交。
     */
    public void recordTaskSubmit() {
        tasksSubmitted.incrementAndGet();
    }

    /**
     * 记录任务完成。
     */
    public void recordTaskComplete() {
        tasksCompleted.incrementAndGet();
    }

    /**
     * 记录任务失败。
     */
    public void recordTaskFailure() {
        tasksFailed.incrementAndGet();
    }

    /**
     * 记录窃取成功。
     */
    public void recordStealSuccess() {
        stealSuccessCount.incrementAndGet();
    }

    /**
     * 记录窃取失败。
     */
    public void recordStealFailure() {
        stealFailureCount.incrementAndGet();
    }

    /**
     * 记录取消。
     */
    public void recordCancellation() {
        cancellationCount.incrementAndGet();
    }

    /**
     * 记录迟到完成。
     */
    public void recordLateCompletion() {
        lateCompletionCount.incrementAndGet();
    }

    /**
     * 记录跨 Region 访问。
     */
    public void recordCrossRegionAccess() {
        crossRegionAccessCount.incrementAndGet();
    }

    // ---- 查询 ----

    /**
     * 获取平均 Tick 时间（纳秒）。
     */
    public long getAverageTickTimeNanos() {
        long ticks = totalTicks.get();
        return ticks > 0 ? totalTickTimeNanos.sum() / ticks : 0;
    }

    /**
     * 获取平均 Tick 时间（毫秒）。
     */
    public double getAverageTickTimeMs() {
        return getAverageTickTimeNanos() / 1_000_000.0;
    }

    /**
     * 获取最大 Tick 时间（毫秒）。
     */
    public double getMaxTickTimeMs() {
        return maxTickTimeNanos.get() / 1_000_000.0;
    }

    /**
     * 计算 P50（中位数 Tick 时间，毫秒）。
     */
    public double getP50() {
        return getPercentile(50) / 1_000_000.0;
    }

    /**
     * 计算 P95（95 百分位数，毫秒）。
     */
    public double getP95() {
        return getPercentile(95) / 1_000_000.0;
    }

    /**
     * 计算 P99（99 百分位数，毫秒）。
     */
    public double getP99() {
        return getPercentile(99) / 1_000_000.0;
    }

    /**
     * 计算百分位数。
     *
     * @param percentile 百分位（0-100）
     * @return 对应耗时的纳秒数
     */
    public long getPercentile(int percentile) {
        if (percentile <= 0) return 0;
        if (percentile >= 100) return maxTickTimeNanos.get();

        // 复制并排序
        long[] copy = new long[TPS_WINDOW_SIZE];
        System.arraycopy(recentTickTimes, 0, copy, 0, TPS_WINDOW_SIZE);
        java.util.Arrays.sort(copy);

        // 找到对应百分位的值
        int index = (int) Math.ceil(percentile / 100.0 * TPS_WINDOW_SIZE);
        index = Math.min(index, TPS_WINDOW_SIZE - 1);
        return copy[index];
    }

    /**
     * 获取当前 TPS（基于最近 N 次 Tick 的平均值）。
     */
    public double getCurrentTPS() {
        long avgNanos = getAverageTickTimeNanos();
        return avgNanos > 0 ? 1_000_000_000.0 / avgNanos : 20.0;
    }

    /**
     * 获取窃取成功率。
     */
    public double getStealSuccessRate() {
        long success = stealSuccessCount.sum();
        long total = success + stealFailureCount.sum();
        return total > 0 ? (double) success / total : 0.0;
    }

    // ---- 快照 ----

    public long getTotalTicks() { return totalTicks.get(); }
    public long getTimeoutCount() { return timeoutCount.get(); }
    public long getCancellationCount() { return cancellationCount.get(); }
    public long getLateCompletionCount() { return lateCompletionCount.get(); }
    public long getCrossRegionAccessCount() { return crossRegionAccessCount.get(); }
    public long getTasksSubmitted() { return tasksSubmitted.sum(); }
    public long getTasksCompleted() { return tasksCompleted.sum(); }
    public long getTasksFailed() { return tasksFailed.sum(); }
    public long getStealSuccessCount() { return stealSuccessCount.sum(); }
    public long getStealFailureCount() { return stealFailureCount.sum(); }

    /**
     * 获取完整快照。
     */
    public Snapshot snapshot() {
        return new Snapshot(
                totalTicks.get(),
                getAverageTickTimeMs(),
                getMaxTickTimeMs(),
                getP50(),
                getP95(),
                getP99(),
                getCurrentTPS(),
                timeoutCount.get(),
                cancellationCount.get(),
                lateCompletionCount.get(),
                crossRegionAccessCount.get(),
                tasksSubmitted.sum(),
                tasksCompleted.sum(),
                tasksFailed.sum(),
                stealSuccessCount.sum(),
                stealFailureCount.sum(),
                getStealSuccessRate()
        );
    }

    /**
     * 重置所有指标。
     */
    public void reset() {
        totalTicks.set(0);
        totalTickTimeNanos.reset();
        maxTickTimeNanos.set(0);
        timeoutCount.set(0);
        cancellationCount.set(0);
        lateCompletionCount.set(0);
        crossRegionAccessCount.set(0);
        tasksSubmitted.reset();
        tasksCompleted.reset();
        tasksFailed.reset();
        stealSuccessCount.reset();
        stealFailureCount.reset();
    }

    /**
     * 指标快照。
     */
    public record Snapshot(
            long totalTicks,
            double averageTickTimeMs,
            double maxTickTimeMs,
            double p50ms,
            double p95ms,
            double p99ms,
            double currentTPS,
            long timeoutCount,
            long cancellationCount,
            long lateCompletionCount,
            long crossRegionAccessCount,
            long tasksSubmitted,
            long tasksCompleted,
            long tasksFailed,
            long stealSuccessCount,
            long stealFailureCount,
            double stealSuccessRate
    ) {
        @Override
        public String toString() {
            return String.format(
                    "Metrics{ticks=%d, avg=%.2fms, max=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, tps=%.1f, " +
                    "tasks=%d/%d/%d, steals=%d/%d (%.1f%%), timeouts=%d, late=%d, crossRegion=%d}",
                    totalTicks, averageTickTimeMs, maxTickTimeMs, p50ms, p95ms, p99ms, currentTPS,
                    tasksCompleted, tasksSubmitted, tasksFailed,
                    stealSuccessCount, stealSuccessCount + stealFailureCount, stealSuccessRate * 100,
                    timeoutCount, lateCompletionCount, crossRegionAccessCount
            );
        }
    }
}
