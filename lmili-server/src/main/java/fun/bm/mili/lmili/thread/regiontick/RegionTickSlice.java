package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 一个 tick 切片——分配给单个 worker 的最小执行单元。
 *
 * <p><b>Generation 绑定</b>：每个 slice 创建时必须捕获 generationId，
 * 完成时通过 generation 验证确保不会影响新 Tick。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>创建线程：Region Tick 线程</li>
 *   <li>执行线程：Worker 线程</li>
 *   <li>完成通知：Worker 线程（通过 arriveSlice）</li>
 * </ul>
 *
 * <h3>Generation 隔离</h3>
 * <p>Slice 必须携带 generationId。禁止依赖"当前 Context generation"推断任务身份。
 * 旧 Generation 的迟到完成不会影响新 Generation。
 */
public final class RegionTickSlice {

    final RegionTickContext context;
    final long @NotNull [] chunkPositions;
    final int sliceIndex;

    /** Generation ID —— 创建时绑定，用于验证完成时的 generation 匹配 */
    final long generationId;

    /**
     * P0-4：slice ownership 合同（read-only chunks / forbidden chunks / mutable external）。
     * 默认为 {@link SliceOwnershipContract#defaults()} —— 任何不可触发的写入路径都需
     * 显式校验 {@link #canWrite(long)}。
     */
    final SliceOwnershipContract ownershipContract;

    /**
     * Slice 状态 —— 追踪本 slice 的执行状态。
     */
    public enum SliceState {
        /** Slice 已创建，尚未开始执行 */
        CREATED,
        /** Slice 正在执行 */
        RUNNING,
        /** Slice 已完成 */
        COMPLETED,
        /** Slice 已取消 */
        CANCELLED
    }

    volatile SliceState state = SliceState.CREATED;

    public RegionTickSlice(final RegionTickContext context,
                           final long @NotNull [] chunkPositions,
                           final int sliceIndex,
                           final long generationId) {
        this(context, chunkPositions, sliceIndex, generationId, SliceOwnershipContract.defaults());
    }

    /**
     * P0-4：携带 ownership 合同的构造器。
     */
    public RegionTickSlice(final RegionTickContext context,
                           final long @NotNull [] chunkPositions,
                           final int sliceIndex,
                           final long generationId,
                           final SliceOwnershipContract ownershipContract) {
        this.context = Objects.requireNonNull(context, "context");
        this.chunkPositions = Objects.requireNonNull(chunkPositions, "chunkPositions");
        this.sliceIndex = sliceIndex;
        this.generationId = generationId;
        this.ownershipContract = Objects.requireNonNull(ownershipContract, "ownershipContract");
    }

    /**
     * 获取本 slice 的 generation ID。
     */
    public long getGenerationId() {
        return generationId;
    }

    /**
     * 获取 slice 状态。
     */
    public SliceState getState() {
        return state;
    }

    public static RegionTickSlice[] fromChunkList(final RegionTickContext context,
                                                   final List<Long> chunks,
                                                   final int sliceSize,
                                                   final long generationId) {
        int total = chunks.size();
        if (total == 0) {
            return new RegionTickSlice[] { new RegionTickSlice(context, new long[0], 0, generationId) };
        }
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);
        RegionTickSlice[] slices = new RegionTickSlice[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            long[] pos = new long[to - from];
            for (int j = from; j < to; j++) {
                pos[j - from] = chunks.get(j);
            }
            slices[i] = new RegionTickSlice(context, pos, i, generationId);
        }
        return slices;
    }

    public static RegionTickSlice[] fromChunkArray(final RegionTickContext context,
                                                   final long @NotNull [] chunkArray,
                                                   final int sliceSize,
                                                   final long generationId) {
        int total = chunkArray.length;
        if (total == 0) {
            return new RegionTickSlice[] { new RegionTickSlice(context, new long[0], 0, generationId) };
        }
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);
        RegionTickSlice[] slices = new RegionTickSlice[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            slices[i] = new RegionTickSlice(context, Arrays.copyOfRange(chunkArray, from, to), i, generationId);
        }
        return slices;
    }

    public int size() { return this.chunkPositions.length; }
    public long getChunkPos(int index) { return this.chunkPositions[index]; }
    public long @NotNull [] getChunkPositions() { return this.chunkPositions; }

    /**
     * P0-4：获取本 slice 的 ownership 合同（用于运行期校验 / 跨 chunk 转发路径）。
     */
    public SliceOwnershipContract getOwnershipContract() { return this.ownershipContract; }

    /**
     * P0-4 §4.2 验收：是否允许直接写入 chunkPos（own 内 + 不在 forbidden / read-only）。
     */
    public boolean canWrite(long chunkPos) {
        return this.ownershipContract.canWrite(this.chunkPositions, chunkPos);
    }

    /**
     * P0-4 §4.2 验收：是否允许读取 chunkPos（own ∪ read-only 范围内；不在 forbidden）。
     */
    public boolean canRead(long chunkPos) {
        return this.ownershipContract.canRead(chunkPos);
    }

    /**
     * P0-4：是否允许在外部状态类别下经由跨 owner 转发修改。
     */
    public boolean hasMutableExternal(SliceOwnershipContract.MutableExternalCategory category) {
        return this.ownershipContract.hasMutableExternal(category);
    }

    @Override
    public String toString() {
        return "RegionTickSlice{regionId=" + context.regionId +
                ", sliceIndex=" + sliceIndex +
                ", chunkCount=" + chunkPositions.length +
                ", gen=" + generationId + "}";
    }
}
