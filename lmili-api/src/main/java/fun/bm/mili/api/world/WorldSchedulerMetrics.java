package fun.bm.mili.api.world;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * 世界调度器指标 —— 单个世界的调度性能统计。
 *
 * <p>通过 {@link WorldScheduler#metrics()} 获取，包含任务数、执行时间、失败率等。
 *
 * @since 2.0.0
 */
public record WorldSchedulerMetrics(
    /** 绑定的世界名称 */
    @NotNull String worldName,
    /** 已提交的任务总数 */
    long tasksSubmitted,
    /** 正在执行的任务数 */
    long tasksRunning,
    /** 队列中等待的任务数 */
    long tasksQueued,
    /** 已完成的任务数 */
    long tasksCompleted,
    /** 失败的任务数 */
    long tasksFailed,
    /** 平均执行时间 (纳秒) */
    long averageExecutionNanos,
    /** 被拒绝的任务数 */
    long rejectedCount,
    /** 当前 TPS 目标 */
    double tpsTarget,
    /** 当前 CPU 预算 (纳秒) */
    long cpuBudgetNanos
) {
    /**
     * 创建空指标（用于 no-op 场景）。
     *
     * @param worldName 世界名称
     * @return 空指标实例
     */
    @NotNull
    public static WorldSchedulerMetrics empty(@NotNull String worldName) {
        return new WorldSchedulerMetrics(worldName, 0, 0, 0, 0, 0, 0, 0, 20.0, 4_000_000L);
    }

    /**
     * 获取平均执行时间 (毫秒)。
     *
     * @return 平均执行时间 (ms)
     */
    public double averageExecutionMs() {
        return averageExecutionNanos / 1_000_000.0;
    }

    /**
     * 获取任务成功率。
     *
     * @return 成功率 [0.0, 1.0]
     */
    public double successRate() {
        return tasksSubmitted == 0 ? 1.0 :
            (double) tasksCompleted / tasksSubmitted;
    }

    /**
     * 获取任务拒绝率。
     *
     * @return 拒绝率 [0.0, 1.0]
     */
    public double rejectionRate() {
        return tasksSubmitted == 0 ? 0.0 :
            (double) rejectedCount / tasksSubmitted;
    }
}
