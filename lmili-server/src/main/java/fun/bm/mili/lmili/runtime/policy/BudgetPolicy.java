package fun.bm.mili.lmili.runtime.policy;

/**
 * CPU 预算策略（SchedulerController.applyBudgetPolicy 的输入）（ARCHITECTURE_AdaptiveRuntime.md §3.3，
 * 由本实施新建的最小类型；实现要点：不直接改 MiliSchedulerImpl 内部 —— 翻译为 per-region 提交闸门参数下发 TickController，§5.2）。
 */
public record BudgetPolicy(
        boolean enabled,
        long regionSoftNanos,
        long regionHardNanos,
        int cpuBudgetPct,
        String appliedBy
) {

    public static final BudgetPolicy DEFAULTS = new BudgetPolicy(false, 4_000_000L, 5_000_000L, 75, "SYSTEM");

    public static BudgetPolicy of(long softNanos, long hardNanos, int cpuBudgetPct, String appliedBy) {
        return new BudgetPolicy(true, softNanos, hardNanos, cpuBudgetPct, appliedBy);
    }
}
