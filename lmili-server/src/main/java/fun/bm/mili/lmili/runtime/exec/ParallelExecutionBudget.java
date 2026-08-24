package fun.bm.mili.lmili.runtime.exec;

/**
 * 并行执行预算 —— ParallelExecutor 的"已获准执行"描述（ARCHITECTURE_AdaptiveRuntime.md §2.6 包布局，
 * 由本实施创建合理最小版本；语义：预算不足的节点不阻塞、挂回 + aging）。
 *
 * @param maxParallel 允许的最大并行度（CPU_PRESSURE 下减半，min 1）
 * @param perNodeBudgetNanos 单节点预算（不足则跳过本轮）
 * @param serial 是否强制串行（全局并行开关关闭时退化）
 */
public record ParallelExecutionBudget(
        int maxParallel,
        long perNodeBudgetNanos,
        boolean serial
) {

    public static ParallelExecutionBudget serialBudget() {
        return new ParallelExecutionBudget(1, 0, true);
    }
}
