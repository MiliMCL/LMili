package fun.bm.mili.lmili.runtime.control;

/**
 * Tick 治理统计快照（TickController.snapshot() 返回值，不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.4）。
 */
public record TickStatsSnapshot(
        long tickCount,
        long avgNanos,
        long maxNanos,
        long budgetRejections,
        long agingPromotions,
        double tpsTarget,
        boolean parallelEnabled
) {
}
