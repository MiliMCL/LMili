package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * P0-4：slice ownership 静态合同。
 *
 * <p>对应 {@code LMili_Next_Phase_Development_Plan.md} §4.2 —— 每个 Slice 必须明确：
 * <ul>
 *   <li>owned chunks —— 可读可写（来自 {@link RegionTickSlice#getChunkPositions()}）</li>
 *   <li>read-only chunks —— 来自邻居区域或同 region 其他 slice，可读但禁止写</li>
 *   <li>mutable external state —— 外部可变更状态（按 {@link MutableExternalCategory} 标识），
 *       允许经由跨 owner 转发机制修改</li>
 *   <li>forbidden chunks —— 本 slice 完全禁止访问</li>
 * </ul>
 *
 * <p>该合同作为纯数据对象伴随 slice 运行期检查使用 —— Slice worker 在写入 chunkPos 前
 * 必须调用 {@link #canWrite(long)} / {@link #canRead(long)} 等方法验证。
 *
 * <p>违反合同（{@link #canWrite(long)} 返回 false 但仍写）的写入会被下一 tick 的 deferred
 * queue 拒绝或按 unsafe 路径发出警告（见 §4.3 跨 Chunk 操作）。
 */
public final class SliceOwnershipContract {

    /** mutable external state 类别 —— 标识可经由跨 owner 转发修改的外部状态。 */
    public enum MutableExternalCategory {
        /** 跨 chunk 邻居 block update（neighbor update） */
        NEIGHBOR_BLOCK_UPDATE,
        /** 跨 chunk scheduled tick（红石 / block tick） */
        SCHEDULED_TICK,
        /** 跨 region entity 移动 / riding / projectile */
        ENTITY_CROSS_OWNER,
        /** 跨 region 流 tick（fluid level） */
        FLUID_TICK,
        /** 跨 region 路径寻找 */
        PATHFINDING
    }

    private final long[] readOnlyChunks;
    private final long[] forbiddenChunks;
    private final java.util.EnumSet<MutableExternalCategory> mutableExternalCategories;

    private SliceOwnershipContract(long[] readOnlyChunks,
                                   long[] forbiddenChunks,
                                   java.util.EnumSet<MutableExternalCategory> mutableExternalCategories) {
        this.readOnlyChunks = readOnlyChunks;
        this.forbiddenChunks = forbiddenChunks;
        this.mutableExternalCategories = mutableExternalCategories;
    }

    /**
     * 创建默认合同：无可读邻居 chunk、无外部可变状态、无禁止 chunk。
     */
    public static SliceOwnershipContract defaults() {
        return new SliceOwnershipContract(
                new long[0], new long[0], java.util.EnumSet.noneOf(MutableExternalCategory.class));
    }

    /**
     * 完整构造。
     */
    public static SliceOwnershipContract of(@NotNull long[] readOnlyChunks,
                                            @NotNull long[] forbiddenChunks,
                                            @NotNull java.util.EnumSet<MutableExternalCategory> mutableExternalCategories) {
        Objects.requireNonNull(readOnlyChunks, "readOnlyChunks");
        Objects.requireNonNull(forbiddenChunks, "forbiddenChunks");
        Objects.requireNonNull(mutableExternalCategories, "mutableExternalCategories");
        return new SliceOwnershipContract(
                readOnlyChunks.clone(), forbiddenChunks.clone(),
                java.util.EnumSet.copyOf(mutableExternalCategories));
    }

    /**
     * 返回只读 chunkPos 数组的克隆（防止外部修改）。
     */
    public long @NotNull [] getReadOnlyChunks() { return this.readOnlyChunks.clone(); }

    /**
     * 返回禁止 chunkPos 数组的克隆。
     */
    public long @NotNull [] getForbiddenChunks() { return this.forbiddenChunks.clone(); }

    /**
     * 返回 mutable external 类别集合（拷贝）。
     */
    public @NotNull java.util.EnumSet<MutableExternalCategory> getMutableExternalCategories() {
        return java.util.EnumSet.copyOf(this.mutableExternalCategories);
    }

    /**
     * 是否允许读取给定 chunkPos（owned ∪ read-only 范围内允许读取）。
     *
     * <p>注：owned chunks 由 {@link RegionTickSlice} 自身持有；read-only 由本合同持有。</p>
     *
     * @param chunkPos chunk position（如 {@code LevelChunk.asLong(x, z)}）
     * @return false 当且仅当 chunkPos 在 forbidden 列表中
     */
    public boolean canRead(long chunkPos) {
        if (contains(forbiddenChunks, chunkPos)) return false;
        return true;
    }

    /**
     * 是否允许写入给定 chunkPos。
     *
     * <p>写入仅允许发生在 slice owned chunks 集合内（chunks 不在 owned 内时只能经由
     * 跨 owner 转发机制修改并显式纳入 {@link #mutableExternalCategories}）。
     *
     * @param ownedChunks  本 slice 的 owned chunks（由 caller 传入；本类不重复持有）</p>
     * @param chunkPos     待校验 chunkPos
     * @return true 表示可直接写入（必须在 owned 范围内）
     */
    public boolean canWrite(long @NotNull [] ownedChunks, long chunkPos) {
        Objects.requireNonNull(ownedChunks, "ownedChunks");
        if (contains(forbiddenChunks, chunkPos)) return false;
        if (contains(readOnlyChunks, chunkPos)) return false;
        if (!contains(ownedChunks, chunkPos)) return false;
        return true;
    }

    /**
     * 是否允许在某一外部状态类别下经由跨 owner 转发修改 chunkPos。
     *
     * <p>用法：caller 检测到 chunkPos 不在 owned 内，但确实需要修改（neighbor update / fluid /
     * scheduled tick），先检查 {@link #hasMutableExternal(MutableExternalCategory)}，
     * 然后走 {@code RegionTickContext.scheduleCrossChunkForward(chunkPos, op, category)}。</p>
     */
    public boolean hasMutableExternal(@NotNull MutableExternalCategory category) {
        Objects.requireNonNull(category, "category");
        return this.mutableExternalCategories.contains(category);
    }

    private static boolean contains(long @NotNull [] arr, long value) {
        for (long v : arr) {
            if (v == value) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "SliceOwnershipContract{readOnly=" + Arrays.toString(readOnlyChunks)
                + ", forbidden=" + Arrays.toString(forbiddenChunks)
                + ", mutableExternal=" + mutableExternalCategories + "}";
    }
}