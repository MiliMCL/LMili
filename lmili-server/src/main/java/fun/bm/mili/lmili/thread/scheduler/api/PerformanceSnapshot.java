package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * 性能指标快照 —— 调度器在某一时刻的性能数据不可变视图。
 *
 * <p>通过 {@link MiliScheduler#performanceSnapshot()} 获取。
 * 所有数值在快照创建时冻结，不会随后续操作变化。
 */
public interface PerformanceSnapshot {

    /**
     * 自调度器启动以来提交的任务总数。
     */
    long totalSubmittedTasks();

    /**
     * 已完成的任务总数。
     */
    long totalCompletedTasks();

    /**
     * 失败的任务总数。
     */
    long totalFailedTasks();

    /**
     * 被取消的任务总数。
     */
    long totalCancelledTasks();

    /**
     * 当前排队等待执行的任务数。
     */
    int pendingTaskCount();

    /**
     * 当前正在执行的任务数。
     */
    int activeTaskCount();

    /**
     * 活跃 region 数量（至少有一个任务正在执行或排队的 region）。
     */
    int activeRegionCount();

    /**
     * 平均任务执行时间（纳秒）。
     */
    long averageTaskNanos();

    /**
     * P99 任务执行时间（纳秒）。
     */
    long p99TaskNanos();

    /**
     * 对象池命中率（0.0 - 1.0）。
     */
    double poolHitRate();

    /**
     * 当前使用的 carrier thread 数量。
     */
    int carrierThreadCount();

    /**
     * 额外诊断信息。
     */
    @NotNull Map<String, Object> diagnostics();

    /**
     * 快照创建时间戳（毫秒）。
    */
    long timestampMillis();
}
