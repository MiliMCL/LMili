package fun.bm.mili.lmili.thread.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Worker 利用率追踪 —— 记录单个 Worker 的执行与空闲统计。
 *
 * <p>用于计算 Worker Utilization 指标：
 * <pre>
 * utilization = busyTime / (busyTime + idleTime)
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>所有方法都线程安全。统计使用 {@link LongAdder} 保证高并发性能。
 */
public final class WorkerUtilization {

    /** Worker ID */
    private final int workerId;

    /** 总忙碌时间（纳秒） */
    private final LongAdder totalBusyNanos = new LongAdder();

    /** 总空闲时间（纳秒） */
    private final LongAdder totalIdleNanos = new LongAdder();

    /** 成功执行的任务数 */
    private final LongAdder tasksExecuted = new LongAdder();

    /** 窃取成功次数 */
    private final LongAdder stealSuccessCount = new LongAdder();

    /** 窃取失败次数 */
    private final LongAdder stealFailureCount = new LongAdder();

    /** 本地队列最大深度 */
    private final AtomicLong maxQueueDepth = new AtomicLong(0);

    /** 窃取来源 Worker ID 统计 */
    private final AtomicLong lastStealFrom = new AtomicLong(-1);

    public WorkerUtilization(int workerId) {
        this.workerId = workerId;
    }

    /**
     * 记录一次任务执行。
     *
     * @param executionTimeNanos 执行时间（纳秒）
     */
    public void recordTaskExecution(long executionTimeNanos) {
        totalBusyNanos.add(executionTimeNanos);
        tasksExecuted.increment();
    }

    /**
     * 记录空闲时间（等待任务时）。
     *
     * @param idleTimeNanos 空闲时间（纳秒）
     */
    public void recordIdle(long idleTimeNanos) {
        if (idleTimeNanos > 0) {
            totalIdleNanos.add(idleTimeNanos);
        }
    }

    /**
     * 记录一次窃取成功。
     *
     * @param fromWorkerId 被窃取的 Worker ID
     */
    public void recordStealSuccess(int fromWorkerId) {
        stealSuccessCount.increment();
        lastStealFrom.set(fromWorkerId);
    }

    /**
     * 记录一次窃取失败。
     */
    public void recordStealFailure() {
        stealFailureCount.increment();
    }

    /**
     * 更新队列最大深度。
     *
     * @param currentDepth 当前队列深度
     */
    public void updateQueueDepth(int currentDepth) {
        long current;
        while ((current = maxQueueDepth.get()) < currentDepth) {
            if (maxQueueDepth.compareAndSet(current, currentDepth)) {
                return;
            }
        }
    }

    // ---- 查询 ----

    /**
     * 获取 Worker 利用率（0.0 ~ 1.0）。
     */
    public double getUtilization() {
        long busy = totalBusyNanos.sum();
        long idle = totalIdleNanos.sum();
        long total = busy + idle;
        return total > 0 ? (double) busy / total : 0.0;
    }

    public int getWorkerId() { return workerId; }
    public long getTotalBusyNanos() { return totalBusyNanos.sum(); }
    public long getTotalIdleNanos() { return totalIdleNanos.sum(); }
    public long getTasksExecuted() { return tasksExecuted.sum(); }
    public long getStealSuccessCount() { return stealSuccessCount.sum(); }
    public long getStealFailureCount() { return stealFailureCount.sum(); }
    public long getMaxQueueDepth() { return maxQueueDepth.get(); }
    public long getLastStealFrom() { return lastStealFrom.get(); }

    /**
     * 获取快照。
     */
    public Snapshot snapshot() {
        return new Snapshot(
                workerId,
                getUtilization(),
                totalBusyNanos.sum(),
                totalIdleNanos.sum(),
                tasksExecuted.sum(),
                stealSuccessCount.sum(),
                stealFailureCount.sum(),
                maxQueueDepth.get()
        );
    }

    /**
     * 利用率快照。
     */
    public record Snapshot(
            int workerId,
            double utilization,
            long totalBusyNanos,
            long totalIdleNanos,
            long tasksExecuted,
            long stealSuccessCount,
            long stealFailureCount,
            long maxQueueDepth
    ) {
        public double stealSuccessRate() {
            long total = stealSuccessCount + stealFailureCount;
            return total > 0 ? (double) stealSuccessCount / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("Worker-%d{util=%.1f%%, tasks=%d, steals=%d/%d (%.1f%%), maxQueue=%d}",
                    workerId,
                    utilization * 100,
                    tasksExecuted,
                    stealSuccessCount,
                    stealSuccessCount + stealFailureCount,
                    stealSuccessRate() * 100,
                    maxQueueDepth);
        }
    }
}
