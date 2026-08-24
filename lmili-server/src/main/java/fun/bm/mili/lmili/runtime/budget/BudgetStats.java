package fun.bm.mili.lmili.runtime.budget;

import fun.bm.mili.lmili.runtime.task.TickTaskType;

import java.util.Map;

/**
 * 预算统计（RegionTickBudget.stats() 返回值，不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.12 诊断）。
 */
public record BudgetStats(
        long softBudgetNanos,
        long hardBudgetNanos,
        long remainingNanos,
        boolean reserveOpen,
        Map<TickTaskType, Long> poolRemainingNanos
) {
}
