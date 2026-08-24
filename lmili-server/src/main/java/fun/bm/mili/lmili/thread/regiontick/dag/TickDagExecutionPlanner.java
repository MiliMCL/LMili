package fun.bm.mili.lmili.thread.regiontick.dag;

import fun.bm.mili.lmili.runtime.budget.RegionTickBudget;
import fun.bm.mili.lmili.runtime.policy.PressureState;
import fun.bm.mili.lmili.runtime.task.TickTaskPriority;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick DAG 执行规划器 —— Scheduler 侧"现在该执行什么"（ARCHITECTURE_AdaptiveRuntime.md §3.14）。
 *
 * <p><strong>D-12</strong>：依赖计算在 {@link TickDag#readySet}（只算 what can run）；
 * <strong>排序与预算分配在本类（planner 是 Scheduler 侧的合法排序点）</strong>。
 *
 * <p>排序：优先级降序 → 估算耗时升序（Shortest-Job-First 同优先级内）。
 * 预算：per-node = clamp(softBudget / 就绪数, 1, hardBudget)。
 * fan-out：CPU_PRESSURE 下减半（与 FanoutController.pressureLimited 语义一致）。
 */
public final class TickDagExecutionPlanner {

    private final TickDag dag;
    private final int maxFanOut;

    public TickDagExecutionPlanner(TickDag dag, int maxFanOut) {
        this.dag = dag;
        this.maxFanOut = Math.max(1, Math.min(64, maxFanOut));
    }

    public TickDagExecution plan(TickDag.ReadyNodeSet readySet, RegionTickBudget budget, PressureState state) {
        if (readySet == null || readySet.isEmpty()) {
            return TickDagExecution.empty();
        }
        final List<Integer> ordered = new ArrayList<>(readySet.nodeIds());
        // 排序（Scheduler 侧职责）：优先级降序 → 估算耗时升序
        ordered.sort((a, b) -> {
            final TickDagNode na = dag.node(a);
            final TickDagNode nb = dag.node(b);
            final TickTaskPriority pa = na != null ? na.priority() : TickTaskPriority.MEDIUM;
            final TickTaskPriority pb = nb != null ? nb.priority() : TickTaskPriority.MEDIUM;
            if (pa != pb) {
                return Integer.compare(pb.ordinal(), pa.ordinal());
            }
            final long ea = na != null ? na.estimatedNanos() : 0;
            final long eb = nb != null ? nb.estimatedNanos() : 0;
            return Long.compare(ea, eb);
        });

        long perNode = 0;
        if (budget != null) {
            final long soft = budget.softBudgetNanos();
            final long hard = budget.hardBudgetNanos();
            perNode = Math.max(1, Math.min(Math.max(1, soft / Math.max(1, ordered.size())), hard));
        }

        final int fanOut = (state == PressureState.CPU_PRESSURE)
                ? Math.max(1, maxFanOut / 2)
                : maxFanOut;

        return new TickDagExecution(ordered.stream().mapToInt(Integer::intValue).toArray(), fanOut, perNode);
    }

    public TickDag dag() {
        return dag;
    }

    public int maxFanOut() {
        return maxFanOut;
    }
}
