package fun.bm.mili.lmili.thread.regiontick.dag;

import fun.bm.mili.lmili.runtime.task.TickTaskType;
import fun.bm.mili.lmili.thread.runtime.ownership.RegionOwnership;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tick DAG —— 编译期依赖 + 运行期"可运行集"（ARCHITECTURE_AdaptiveRuntime.md §3.14 / D-12）。
 *
 * <p>包位置：{@code thread/regiontick/dag}（§2.6 布局；与 {@link CompiledDag}/{@link SystemGraph}
 * 同包，编译产物沿用现有类）。
 *
 * <p><strong>D-12</strong>：readySet 只算依赖（what can run）—— 不含排序/预算逻辑
 * （排序是 Scheduler 侧 planner 的职责）。执行状态（completed/remainingDeps）为
 * 单 tick 周期内的单写者状态。
 *
 * <p>编译：委托 {@link SystemGraph#compile()}（现有类零改动）。
 */
public final class TickDag {

    /** 全局 region 哨兵（不归属任何 worker） */
    public static final long GLOBAL_REGION_ID = -1L;

    private final CompiledDag compiled;
    private final TickDagNode[] nodes;          // nodeId → 元数据
    private final boolean[] completed;          // 本 tick 执行状态（单写者）
    private final int[] remainingDeps;          // 依赖计数副本（单写者）

    private TickDag(CompiledDag compiled, TickDagNode[] nodes) {
        this.compiled = compiled;
        this.nodes = nodes;
        final int n = compiled.nodeCount();
        this.completed = new boolean[n];
        this.remainingDeps = new int[n];
        reset();
    }

    /** 从编译结果构造（nodes 必须与 CompiledDag.nodeCount() 对齐） */
    public static TickDag compile(CompiledDag compiled, TickDagNode[] nodes) {
        final int n = compiled.nodeCount();
        final TickDagNode[] aligned = new TickDagNode[n];
        for (int i = 0; i < n; i++) {
            aligned[i] = nodes != null && i < nodes.length && nodes[i] != null
                    ? nodes[i] : TickDagNode.defaultNode("node-" + i);
        }
        return new TickDag(compiled, aligned);
    }

    /** 快捷路径：单 system profile 编译 */
    public static TickDag compile(SystemProfile profile) {
        final SystemGraph graph = new SystemGraph();
        final SystemGraph.SystemHandle handle = graph.register(profile, ctx -> {
            // 占位执行器：真实执行由集成方 NodeRunner 接管（本类只做依赖/就绪计算）
        });
        final CompiledDag dag = graph.compile();
        return compile(dag, new TickDagNode[]{TickDagNode.defaultNode(handle.name() != null ? handle.name() : "root")});
    }

    /**
     * 可运行集：依赖已满足（remainingDeps==0）且 region 可访问
     * （全局节点（regionId<=0，含未设置默认 0 与 GLOBAL_REGION_ID） 或 regionId 属于
     * ownership 持有的 region 集合）。
     *
     * <p><strong>只算依赖（what can run），禁止排序/预算逻辑（D-12）</strong>：
     * 排序由 {@link TickDagExecutionPlanner}（Scheduler 侧）负责。
     */
    public ReadyNodeSet readySet(RegionOwnership ownership) {
        final Set<Long> owned = ownership != null ? ownership.getAllOwnerships().keySet() : Set.of();
        final Set<Integer> ready = new LinkedHashSet<>();
        for (int i = 0; i < compiled.nodeCount(); i++) {
            if (completed[i]) {
                continue;
            }
            if (remainingDeps[i] > 0) {
                continue;
            }
            final long rid = compiled.regionId(i);
            // 注：SystemGraph 编译时对 GLOBAL_REGION_ID 节点不调用 setRegionId，
            // 未设置节点在 CompiledDag 中默认 0 —— 两者均视为全局。
            if (rid <= 0 || owned.contains(rid)) {
                ready.add(i);
            }
        }
        return new ReadyNodeSet(ready, System.nanoTime());
    }

    /** 标记节点已完成（执行器回调；单写者） */
    public void markCompleted(int nodeId) {
        if (nodeId < 0 || nodeId >= completed.length || completed[nodeId]) {
            return;
        }
        completed[nodeId] = true;
        final it.unimi.dsi.fastutil.ints.IntList successors = compiled.successors(nodeId);
        if (successors != null) {
            for (int s : successors) {
                if (remainingDeps[s] > 0) {
                    remainingDeps[s]--;
                }
            }
        }
    }

    public boolean isCompleted(int nodeId) {
        return nodeId >= 0 && nodeId < completed.length && completed[nodeId];
    }

    public boolean isDone() {
        for (boolean c : completed) {
            if (!c) {
                return false;
            }
        }
        return compiled.nodeCount() > 0;
    }

    /** 本 tick 周期结束：重置执行状态（下一个 tick 重新执行） */
    public void reset() {
        final int[] indeg = compiled.copyInDegrees();
        for (int i = 0; i < remainingDeps.length; i++) {
            completed[i] = false;
            remainingDeps[i] = indeg != null && i < indeg.length ? indeg[i] : 0;
        }
    }

    public TickDagNode node(int nodeId) {
        return nodeId >= 0 && nodeId < nodes.length ? nodes[nodeId] : null;
    }

    public CompiledDag compiled() {
        return compiled;
    }

    public int nodeCount() {
        return compiled.nodeCount();
    }

    public List<Long> regionIds() {
        final List<Long> ids = new ArrayList<>(compiled.nodeCount());
        for (int i = 0; i < compiled.nodeCount(); i++) {
            ids.add(compiled.regionId(i));
        }
        return ids;
    }

    public Map<Integer, TickTaskType> nodeTypes() {
        final Map<Integer, TickTaskType> map = new java.util.HashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            map.put(i, nodes[i] != null ? nodes[i].type() : TickTaskType.ENTITY);
        }
        return map;
    }

    /**
     * 可运行集（不可变记录）：nodeIds + 生成时间戳。
     *
     * <p>签名调整说明（不改语义）：设计文档的 ReadyNodeSet 为
     * {@code (int[] readyNodeIds, long[] estimatedNanosPerNode)}；本实施用
     * {@code Set<Integer>} 承载无序就绪集合（语义等价：依赖就绪的节点 id 集合，
     * 不含顺序/预算 —— 那正是 D-12 要求剥离的部分），估算耗时由
     * {@link TickDagNode#estimatedNanos()} 提供，故不再重复携带数组。
     */
    public record ReadyNodeSet(Set<Integer> nodeIds, long nanosSnapshot) {
        public boolean isEmpty() {
            return nodeIds.isEmpty();
        }

        public int size() {
            return nodeIds.size();
        }
    }
}
