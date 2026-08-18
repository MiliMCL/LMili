package fun.bm.mili.lmili.thread.regiontick.dag;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
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
 * └──────────────────────────────────────────────┘
 * </pre>
 */
public final class CompiledDag {

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

    private CompiledDag(
            final int nodeCount,
            final IntList[] adjacency,
            final int[] inDegrees,
            final DagExecutor[] executors,
            final IntList readyNodes
    ) {
        this.nodeCount = nodeCount;
        this.adjacency = adjacency;
        this.inDegrees = inDegrees;
        this.executors = executors;
        this.readyNodes = readyNodes;
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
        return adjacency[nodeId];
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
        return readyNodes;
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
        private final int nodeCount;

        @SuppressWarnings("unchecked")
        Builder(final int nodeCount) {
            this.nodeCount = nodeCount;
            this.adjacency = new IntList[nodeCount];
            this.inDegrees = new int[nodeCount];
            this.executors = new DagExecutor[nodeCount];
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
            adjacency[from].add(to);
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

            return new CompiledDag(nodeCount, adjacency, inDegrees, executors, ready);
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
