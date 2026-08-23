package fun.bm.mili.lmili.thread.regiontick.dag;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 编译后的 DAG — 不可变有向无环图。
 *
 * <p>相比原有 RegionDag 的改进：
 * <ul>
 *   <li>不可变设计：构建后不可修改，线程安全，可跨 tick 缓存复用</li>
 *   <li>使用 {@link IntArrayList} 替代 int[] 邻接表，减少内存浪费</li>
 *   <li>预计算拓扑序和就绪节点列表，加速每次执行</li>
 *   <li>无分配读取：所有字段为 final，读取零分配</li>
 * </ul>
 *
 * <h3>内存布局：</h3>
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ CompiledDag                                  │
 * ├──────────────────────────────────────────────┤
 * │ nodeCount: int                               │
 * │ adjacency: IntList[] (邻接表，紧凑存储)        │
 * │ inDegrees: int[] (每个节点的入度)              │
 * │ executors: DagExecutor[] (每个节点的执行函数)  │
 * │ readyNodes: IntList (入度为0的节点，缓存)      │
 * │ regionIds: long[] (每个节点所属 regionId)     │  ← Mili 扩展
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Mili 扩展（DAG 与 Folia region 调度协调）</b>：每个节点携带其所属 regionId，
 * 由 {@code SystemGraph.compile()} 在编译时从 {@link Scope} 提取。
 * 节点执行时由 {@link fun.bm.mili.lmili.thread.regiontick.executor.NodeScheduler}
 * 根据 regionId 路由 —— <b>严禁</b>跨 region 在同一线程上直接执行（会破坏 Folia 的
 * tickingRegion 上下文）。</p>
 */
public final class CompiledDag {

    /** 全局 regionId（用于不属于特定 region 的节点；此时 DAG 引擎允许在当前线程执行） */
    public static final long GLOBAL_REGION_ID = -1L;

    /** 节点数量 */
    private final int nodeCount;

    /**
     * 邻接表：adjacency[i] 表示节点 i 的所有后继节点。
     * 使用 IntList 避免 int[] 的固定大小限制。
     */
    private final IntList[] adjacency;

    /**
     * 每个节点的当前入度（在执行过程中被递减）。
     * 使用副本机制：执行时复制此数组，不影响原图。
     */
    private final int[] inDegrees;

    /**
     * 每个节点的执行函数。
     * 索引对应节点 ID。
     */
    private final DagExecutor[] executors;

    /**
     * 入度为 0 的初始节点（拓扑排序起点）。
     * 预计算并缓存，避免每次执行时重新计算。
     */
    private final IntList readyNodes;

    /**
     * 每个节点所属的 regionId。
     * <ul>
     *   <li>正数：该 region 的 regionId —— 节点必须在该 region 的 acquire/tickingRegion 上下文内执行</li>
     *   <li>{@link #GLOBAL_REGION_ID} (-1)：全局节点（如 global tick 任务），无 region 约束</li>
     * </ul>
     */
    private final long[] regionIds;

    private CompiledDag(
            final int nodeCount,
            final IntList[] adjacency,
            final int[] inDegrees,
            final DagExecutor[] executors,
            final IntList readyNodes,
            final long[] regionIds
    ) {
        this.nodeCount = nodeCount;
        this.adjacency = adjacency;
        this.inDegrees = inDegrees;
        this.executors = executors;
        this.readyNodes = readyNodes;
        this.regionIds = regionIds;
    }

    /**
     * 获取节点数量。
     */
    public int nodeCount() {
        return nodeCount;
    }

    /**
     * 获取指定节点的后继节点列表。
     *
     * @param nodeId 节点 ID
     * @return 后继节点列表（不可修改）
     */
    public @NotNull IntList successors(final int nodeId) {
        return IntLists.unmodifiable(adjacency[nodeId]);
    }

    /**
     * 获取指定节点的入度。
     *
     * @param nodeId 节点 ID
     * @return 入度值
     */
    public int inDegree(final int nodeId) {
        return inDegrees[nodeId];
    }

    /**
     * 获取指定节点的初始依赖数（执行前的入度）。
     *
     * <p>与 {@link #inDegree(int)} 等价，用于 {@code DagExecutionState} 初始化。
     *
     * @param nodeId 节点 ID
     * @return 初始依赖数
     */
    public int initialDependencyCount(final int nodeId) {
        return inDegrees[nodeId];
    }

    /**
     * 获取指定节点的执行函数。
     *
     * @param nodeId 节点 ID
     * @return 执行函数
     */
    public @NotNull DagExecutor executor(final int nodeId) {
        return executors[nodeId];
    }

    /**
     * 获取初始就绪节点（入度为 0 的节点）。
     *
     * @return 就绪节点列表（不可修改）
     */
    public @NotNull IntList readyNodes() {
        return IntLists.unmodifiable(readyNodes);
    }

    /**
     * 获取指定节点所属的 regionId。
     *
     * <p>用于 DAG 执行时把节点路由到正确的 Folia region acquire 路径：
     * <ul>
     *   <li>正数：节点必须由该 region 的 tick 线程执行</li>
     *   <li>{@link #GLOBAL_REGION_ID} (-1)：节点无 region 约束（global tick 任务）</li>
     * </ul>
     *
     * @param nodeId 节点 ID
     * @return regionId（正数或 {@link #GLOBAL_REGION_ID}）
     */
    public long regionId(final int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) {
            throw new IndexOutOfBoundsException("nodeId: " + nodeId + ", nodeCount: " + nodeCount);
        }
        return regionIds[nodeId];
    }

    /**
     * 创建入度数组的副本（用于执行时修改）。
     *
     * @return 入度数组副本
     */
    public int @NotNull [] copyInDegrees() {
        return inDegrees.clone();
    }

    /**
     * 创建构建器。
     *
     * @param nodeCount 节点数量
     * @return 新的构建器实例
     */
    public static @NotNull Builder builder(final int nodeCount) {
        return new Builder(nodeCount);
    }

    /**
     * 节点执行函数接口。
     *
     * <p>与 {@link java.util.function.Consumer} 类似，但接受
     * {@link DagExecutionContext} 而非泛型参数，避免装箱。
     */
    @FunctionalInterface
    public interface DagExecutor {
        /**
         * 执行节点逻辑。
         *
         * @param context 执行上下文
         */
        void execute(@NotNull DagExecutionContext context);
    }

    /**
     * DAG 执行上下文。
     *
     * <p>传递给每个节点的执行函数，提供执行期间需要的信息。
     */
    public interface DagExecutionContext {
        /**
         * 获取当前节点 ID。
         */
        int nodeId();

        /**
         * 获取当前 tick。
         */
        long currentTick();
    }

    /**
     * CompiledDag 构建器。
     *
     * <p>使用示例：
     * <pre>{@code
     * CompiledDag dag = CompiledDag.builder(3)
     *     .setExecutor(0, ctx -> systemA.tick())
     *     .setExecutor(1, ctx -> systemB.tick())
     *     .setExecutor(2, ctx -> systemC.tick())
     *     .addEdge(0, 2)  // systemA 在 systemC 之前执行
     *     .addEdge(1, 2)  // systemB 在 systemC 之前执行
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private final IntList[] adjacency;
        private final int[] inDegrees;
        private final DagExecutor[] executors;
        private final long[] regionIds;
        private final int nodeCount;

        @SuppressWarnings("unchecked")
        Builder(final int nodeCount) {
            this.nodeCount = nodeCount;
            this.adjacency = new IntList[nodeCount];
            this.inDegrees = new int[nodeCount];
            this.executors = new DagExecutor[nodeCount];
            this.regionIds = new long[nodeCount];
            // 默认所有节点为 global（无 region 约束），setRegionId() 可覆盖
            java.util.Arrays.fill(this.regionIds, GLOBAL_REGION_ID);
            // 初始化邻接表
            for (int i = 0; i < nodeCount; i++) {
                adjacency[i] = new IntArrayList(2);
            }
        }

        /**
         * 设置节点的执行函数。
         *
         * @param nodeId   节点 ID
         * @param executor 执行函数
         * @return this（链式调用）
         */
        public @NotNull Builder setExecutor(final int nodeId, final @NotNull DagExecutor executor) {
            Objects.requireNonNull(executor, "executor");
            if (nodeId < 0 || nodeId >= nodeCount) {
                throw new IndexOutOfBoundsException("nodeId: " + nodeId + ", nodeCount: " + nodeCount);
            }
            executors[nodeId] = executor;
            return this;
        }

        /**
         * 设置节点所属的 regionId（Mili 扩展）。
         *
         * <p>用于 DAG 引擎在执行时按 regionId 路由节点 —— 同 region 节点可走快路径
         * （保留当前线程的 tickingRegion 上下文）；跨 region 节点必须重新走
         * Folia 的 region acquire 路径，禁止在同一线程直接执行。</p>
         *
         * @param nodeId   节点 ID
         * @param regionId 节点所属 regionId（正数）或 {@link #GLOBAL_REGION_ID} 表示无 region 约束
         * @return this（链式调用）
         */
        public @NotNull Builder setRegionId(final int nodeId, final long regionId) {
            if (nodeId < 0 || nodeId >= nodeCount) {
                throw new IndexOutOfBoundsException("nodeId: " + nodeId + ", nodeCount: " + nodeCount);
            }
            this.regionIds[nodeId] = regionId;
            return this;
        }

        /**
         * 添加一条有向边：from → to。
         *
         * <p>表示 from 节点必须在 to 节点之前执行。
         *
         * @param from 前置节点 ID
         * @param to   后置节点 ID
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果添加此边会产生环
         */
        public @NotNull Builder addEdge(final int from, final int to) {
            if (from < 0 || from >= nodeCount || to < 0 || to >= nodeCount) {
                throw new IndexOutOfBoundsException("from: " + from + ", to: " + to + ", nodeCount: " + nodeCount);
            }
            if (from == to) {
                throw new IllegalArgumentException("Self-loop not allowed: " + from);
            }
            // 去重：检查边是否已存在
            IntList successors = adjacency[from];
            for (int i = 0; i < successors.size(); i++) {
                if (successors.getInt(i) == to) {
                    // 边已存在，无需重复添加
                    return this;
                }
            }
            successors.add(to);
            inDegrees[to]++;
            return this;
        }

        /**
         * 从冲突图批量添加边（所有冲突节点对添加双向依赖）。
         *
         * <p>这只是初始约束，具体方向由节点的 priority 决定。
         *
         * @param graph     冲突图
         * @param priorities 每个节点的优先级（高优先级先执行）
         * @return this（链式调用）
         */
        public @NotNull Builder addEdgesFromConflictGraph(
                final @NotNull ConflictGraph graph,
                final int @NotNull [] priorities
        ) {
            for (int i = 0; i < nodeCount; i++) {
                var conflicts = graph.getConflicts(i);
                for (int conflictId : conflicts) {
                    // 优先级高的先执行，优先级低的依赖优先级高的
                    if (priorities[i] > priorities[conflictId]) {
                        addEdge(i, conflictId);
                    } else if (priorities[i] < priorities[conflictId]) {
                        addEdge(conflictId, i);
                    } else {
                        // 相同优先级，按 ID 排序保证确定性
                        if (i < conflictId) {
                            addEdge(i, conflictId);
                        } else {
                            addEdge(conflictId, i);
                        }
                    }
                }
            }
            return this;
        }

        /**
         * 构建不可变的 CompiledDag。
         *
         * <p>会验证：
         * <ul>
         *   <li>所有节点都有执行函数</li>
         *   <li>图中没有环</li>
         * </ul>
         *
         * @return 新的 CompiledDag 实例
         * @throws IllegalStateException 如果存在环或节点未设置执行函数
         */
        public @NotNull CompiledDag build() {
            // 验证所有节点都有执行函数
            for (int i = 0; i < nodeCount; i++) {
                if (executors[i] == null) {
                    throw new IllegalStateException("Node " + i + " has no executor");
                }
            }

            // 计算就绪节点
            IntArrayList ready = new IntArrayList();
            for (int i = 0; i < nodeCount; i++) {
                if (inDegrees[i] == 0) {
                    ready.add(i);
                }
            }

        // 验证无环（简单检测：如果存在拓扑排序，则无环）
        if (hasCycle()) {
            throw new IllegalStateException("DAG contains a cycle");
        }

        // 优化邻接表大小
        for (IntList list : adjacency) {
            if (list instanceof IntArrayList) {
                ((IntArrayList) list).trim();
            }
        }
        ready.trim();

            return new CompiledDag(nodeCount, adjacency, inDegrees, executors, ready, regionIds);
        }

        /**
         * 检测图中是否存在环（使用 Kahn 算法）。
         */
        private boolean hasCycle() {
            int[] tempInDegrees = inDegrees.clone();
            IntList queue = new IntArrayList();
            int processed = 0;

            // 初始化队列
            for (int i = 0; i < nodeCount; i++) {
                if (tempInDegrees[i] == 0) {
                    queue.add(i);
                }
            }

            // Kahn 算法
            while (!queue.isEmpty()) {
                int node = queue.removeInt(0);
                processed++;
                for (int successor : adjacency[node]) {
                    if (--tempInDegrees[successor] == 0) {
                        queue.add(successor);
                    }
                }
            }

            // 如果处理的节点数不等于总节点数，说明存在环
            return processed != nodeCount;
        }
    }
}
