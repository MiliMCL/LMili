package fun.bm.mili.lmili.thread.regiontick.dag;

import fun.bm.mili.lmili.runtime.task.TickTaskPriority;
import fun.bm.mili.lmili.runtime.task.TickTaskType;

/**
 * Tick DAG 节点 —— 规划期元数据（不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.14）。
 *
 * @param name          节点名（可观测）
 * @param estimatedNanos 估算耗时（默认预算池）
 * @param type          tick 类型（决定预算池与并行度）
 * @param priority      优先级（Scheduler 侧排序依据；D-12 允许 planner/executor 排序）
 */
public record TickDagNode(
        String name,
        long estimatedNanos,
        TickTaskType type,
        TickTaskPriority priority
) {

    public static TickDagNode of(String name, long estimatedNanos, TickTaskType type, TickTaskPriority priority) {
        return new TickDagNode(name, estimatedNanos, type, priority);
    }

    /** 默认节点（单一 system 快捷路径） */
    public static TickDagNode defaultNode(String name) {
        return new TickDagNode(name, 0, TickTaskType.ENTITY, TickTaskPriority.MEDIUM);
    }
}
