package fun.bm.mili.lmili.thread.regiontick.dag;

/**
 * Tick DAG 执行计划 —— TickDagExecutionPlanner 的输出（不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.14）。
 *
 * @param nodeOrder         本轮执行顺序（Scheduler 侧排序结果，what should run now）
 * @param maxFanoutPerNode  单节点允许的最大并行 fan-out（CPU_PRESSURE 下减半）
 * @param budgetNanosPerNode 单节点预算（不足则跳过本轮，挂回 + aging）
 */
public record TickDagExecution(
        int[] nodeOrder,
        int maxFanoutPerNode,
        long budgetNanosPerNode
) {

    public static TickDagExecution empty() {
        return new TickDagExecution(new int[0], 1, 0);
    }

    public int size() {
        return nodeOrder.length;
    }

    public boolean isEmpty() {
        return nodeOrder.length == 0;
    }
}
