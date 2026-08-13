package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 一个 tick 切片——分配给单个 worker 的最小执行单元。
 */
public final class RegionTickSlice {

    final RegionTickContext context;
    final long @NotNull [] chunkPositions;
    final int sliceIndex;

    public RegionTickSlice(final RegionTickContext context,
                           final long @NotNull [] chunkPositions,
                           final int sliceIndex) {
        this.context = Objects.requireNonNull(context, "context");
        this.chunkPositions = Objects.requireNonNull(chunkPositions, "chunkPositions");
        this.sliceIndex = sliceIndex;
    }

    public static RegionTickSlice[] fromChunkList(final RegionTickContext context,
                                                   final List<Long> chunks,
                                                   final int sliceSize) {
        int total = chunks.size();
        if (total == 0) {
            return new RegionTickSlice[] { new RegionTickSlice(context, new long[0], 0) };
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
            slices[i] = new RegionTickSlice(context, pos, i);
        }
        return slices;
    }

    public static RegionTickSlice[] fromChunkArray(final RegionTickContext context,
                                                   final long @NotNull [] chunkArray,
                                                   final int sliceSize) {
        int total = chunkArray.length;
        if (total == 0) {
            return new RegionTickSlice[] { new RegionTickSlice(context, new long[0], 0) };
        }
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);
        RegionTickSlice[] slices = new RegionTickSlice[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            slices[i] = new RegionTickSlice(context, Arrays.copyOfRange(chunkArray, from, to), i);
        }
        return slices;
    }

    public int size() { return this.chunkPositions.length; }
    public long getChunkPos(int index) { return this.chunkPositions[index]; }
    public long @NotNull [] getChunkPositions() { return this.chunkPositions; }

    @Override
    public String toString() {
        return "RegionTickSlice{regionId=" + context.regionId + ", sliceIndex=" + sliceIndex + ", chunkCount=" + chunkPositions.length + "}";
    }
}
