package fun.bm.mili.lmili.thread.regiontick.dag;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 稀疏冲突图 — 替代原有的 boolean[n][n] 矩阵。
 *
 * <p>只存储实际存在冲突的边，空间复杂度从 O(n²) 降至 O(e)，其中 e 为冲突边数。
 * 使用 fastutil 的 IntSet 避免装箱开销。
 *
 * <h3>性能对比：</h3>
 * <ul>
 *   <li>原方案（boolean[][]）：100 系统 → 100×100 = 10,000 个 boolean ≈ 10KB</li>
 *   <li>本方案（稀疏图）：100 系统，每系统平均冲突 3 个 → 约 300 条边 ≈ 2.4KB</li>
 * </ul>
 *
 * <p>此类为不可变对象，构建完成后不可修改，可安全跨线程共享。
 */
public final class ConflictGraph {

    /**
     * 邻接表：nodeId → 与其冲突的节点集合。
     * 仅存储 nodeId < neighborId 的边（上三角），减少一半存储。
     */
    private final Int2ObjectMap<IntSet> adjacency;

    /** 节点总数 */
    private final int nodeCount;

    private ConflictGraph(final Int2ObjectMap<IntSet> adjacency, final int nodeCount) {
        this.adjacency = adjacency;
        this.nodeCount = nodeCount;
    }

    /**
     * 检查两个节点是否存在冲突。
     *
     * @param nodeId    第一个节点 ID
     * @param otherId   第二个节点 ID
     * @return 如果存在冲突返回 true
     */
    public boolean hasConflict(final int nodeId, final int otherId) {
        if (nodeId == otherId) return false;
        // 确保查找上三角（较小 ID 作为 key）
        int key = Math.min(nodeId, otherId);
        int value = Math.max(nodeId, otherId);
        IntSet neighbors = adjacency.get(key);
        return neighbors != null && neighbors.contains(value);
    }

    /**
     * 获取指定节点的所有冲突邻居。
     *
     * @param nodeId 节点 ID
     * @return 冲突节点集合（不可修改视图）
     */
    public @NotNull IntSet getConflicts(final int nodeId) {
        IntSet result = new IntOpenHashSet();
        // 检查 nodeId 作为较大 ID 的情况（邻居的邻接表中包含此节点）
        for (var entry : adjacency.int2ObjectEntrySet()) {
            int key = entry.getIntKey();
            IntSet values = entry.getValue();
            if (key == nodeId) {
                result.addAll(values);
            } else if (values.contains(nodeId)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * 获取节点总数。
     */
    public int nodeCount() {
        return nodeCount;
    }

    /**
     * 获取冲突边总数。
     */
    public int edgeCount() {
        int count = 0;
        for (IntSet neighbors : adjacency.values()) {
            count += neighbors.size();
        }
        return count;
    }

    /**
     * 创建构建器。
     *
     * @param nodeCount 节点总数
     * @return 新的构建器实例
     */
    public static @NotNull Builder builder(final int nodeCount) {
        return new Builder(nodeCount);
    }

    /**
     * ConflictGraph 构建器。
     */
    public static final class Builder {
        private final Int2ObjectOpenHashMap<IntSet> adjacency;
        private final int nodeCount;

        Builder(final int nodeCount) {
            this.nodeCount = nodeCount;
            this.adjacency = new Int2ObjectOpenHashMap<>(Math.min(nodeCount, 16));
        }

        /**
         * 添加一条冲突边。
         *
         * @param nodeId  第一个节点
         * @param otherId 第二个节点
         * @return this（链式调用）
         */
        public @NotNull Builder addConflict(final int nodeId, final int otherId) {
            if (nodeId == otherId) return this;
            int key = Math.min(nodeId, otherId);
            int value = Math.max(nodeId, otherId);
            IntSet neighbors = adjacency.computeIfAbsent(key, k -> new IntOpenHashSet(2));
            neighbors.add(value);
            return this;
        }

        /**
         * 批量添加冲突边（基于 SystemProfile 的类型冲突检测）。
         *
         * @param profiles 系统声明数组
         * @return this（链式调用）
         */
        public @NotNull Builder addTypeConflicts(final SystemProfile @NotNull [] profiles) {
            Objects.requireNonNull(profiles, "profiles");
            for (int i = 0; i < profiles.length; i++) {
                if (profiles[i] == null) continue;
                for (int j = i + 1; j < profiles.length; j++) {
                    if (profiles[j] == null) continue;
                    if (profiles[i].hasWriteConflictWith(profiles[j])) {
                        addConflict(i, j);
                    }
                }
            }
            return this;
        }

        /**
         * 构建不可变的 ConflictGraph。
         *
         * @return 新的 ConflictGraph 实例
         */
        public @NotNull ConflictGraph build() {
            // 冻结所有邻接表
            for (IntSet neighbors : adjacency.values()) {
                if (neighbors instanceof IntOpenHashSet) {
                    ((IntOpenHashSet) neighbors).trim();
                }
            }
            adjacency.trim();
            return new ConflictGraph(adjacency, nodeCount);
        }
    }
}
