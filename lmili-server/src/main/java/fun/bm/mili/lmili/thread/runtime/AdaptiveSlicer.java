package fun.bm.mili.lmili.thread.runtime;

/**
 * Adaptive Slicer —— 根据历史执行时间动态调整 slice 大小。
 *
 * <p>最终目标不是固定的 chunks/slice，而是约 2~4ms estimated work/slice。
 *
 * <p>根据历史执行时间动态拆分：
 * <pre>
 * Slice A: 4 chunks  → 3.2ms
 * Slice B: 16 chunks → 3.8ms
 * Slice C: 2 chunks  → 2.9ms
 * </pre>
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>使用 EWMA 预测每个 Region 的执行时间</li>
 *   <li>根据目标时间（2~4ms）计算 slice 数量</li>
 *   <li>动态调整：如果实际执行时间偏离目标，修正下次的 slice 大小</li>
 * </ol>
 */
public final class AdaptiveSlicer {

    /** 默认目标：3ms/slice */
    public static final double DEFAULT_TARGET_MILLIS = 3.0;

    /** 最小目标：2ms/slice */
    public static final double MIN_TARGET_MILLIS = 2.0;

    /** 最大目标：4ms/slice */
    public static final double MAX_TARGET_MILLIS = 4.0;

    /** 最小 chunks/slice */
    public static final int MIN_CHUNKS_PER_SLICE = 1;

    /** 最大 chunks/slice */
    public static final int MAX_CHUNKS_PER_SLICE = 32;

    /** EWMA 学习率 */
    private static final double EWMA_ALPHA = 0.2;

    /** 目标执行时间（毫秒） */
    private final double targetTimeMillis;

    public AdaptiveSlicer(double targetTimeMillis) {
        this.targetTimeMillis = Math.max(MIN_TARGET_MILLIS, Math.min(MAX_TARGET_MILLIS, targetTimeMillis));
    }

    public AdaptiveSlicer() {
        this(DEFAULT_TARGET_MILLIS);
    }

    /**
     * 计算 slice 划分。
     *
     * @param totalChunks       总 chunk 数
     * @param predictedTimePerChunk 每个 chunk 的预测执行时间（毫秒）
     * @return slice 划分结果
     */
    public SlicePlan calculateSlices(int totalChunks, double predictedTimePerChunk) {
        if (totalChunks <= 0) {
            return new SlicePlan(0, 0, 0);
        }

        // 每个 slice 应有的 chunk 数量
        int chunksPerSlice = calculateChunksPerSlice(predictedTimePerChunk);

        // slice 数量
        int sliceCount = (int) Math.ceil((double) totalChunks / chunksPerSlice);

        // 实际每个 slice 的执行时间估计
        double estimatedTimePerSlice = chunksPerSlice * predictedTimePerChunk;

        return new SlicePlan(sliceCount, chunksPerSlice, estimatedTimePerSlice);
    }

    /**
     * 计算每个 slice 应有的 chunk 数量。
     *
     * @param timePerChunk 每个 chunk 的执行时间（毫秒）
     * @return 每个 slice 的 chunk 数量
     */
    public int calculateChunksPerSlice(double timePerChunk) {
        if (timePerChunk <= 0) return MAX_CHUNKS_PER_SLICE;

        int chunks = (int) (targetTimeMillis / timePerChunk);
        return Math.max(MIN_CHUNKS_PER_SLICE, Math.min(MAX_CHUNKS_PER_SLICE, chunks));
    }

    /**
     * 更新预测。
     *
     * @param previousTimePerChunk 上次的预测值
     * @param actualTimePerChunk   实际的每 chunk 执行时间
     * @return 新的预测值
     */
    public static double updatePrediction(double previousTimePerChunk, double actualTimePerChunk) {
        return previousTimePerChunk * (1 - EWMA_ALPHA) + actualTimePerChunk * EWMA_ALPHA;
    }

    public double getTargetTimeMillis() {
        return targetTimeMillis;
    }

    /**
     * Slice 计划。
     */
    public static final class SlicePlan {

        /** slice 数量 */
        public final int sliceCount;

        /** 每个 slice 的 chunk 数量 */
        public final int chunksPerSlice;

        /** 每个 slice 的预估执行时间（毫秒） */
        public final double estimatedTimeMillis;

        public SlicePlan(int sliceCount, int chunksPerSlice, double estimatedTimeMillis) {
            this.sliceCount = sliceCount;
            this.chunksPerSlice = chunksPerSlice;
            this.estimatedTimeMillis = estimatedTimeMillis;
        }

        @Override
        public String toString() {
            return "SlicePlan{slices=" + sliceCount +
                    ", chunksPerSlice=" + chunksPerSlice +
                    ", estTime=" + String.format("%.2f", estimatedTimeMillis) + "ms}";
        }
    }

    /**
     * Region 自适应切片器 —— 维护每个 Region 的切片历史。
     */
    public static final class RegionAdaptiveSlicer {

        private final long regionId;
        private double timePerChunkMillis;
        private int currentChunksPerSlice;
        private int totalSlices;

        public RegionAdaptiveSlicer(long regionId, double initialTimePerChunkMillis) {
            this.regionId = regionId;
            this.timePerChunkMillis = initialTimePerChunkMillis;
            this.currentChunksPerSlice = 16; // 默认值
            this.totalSlices = 0;
        }

        /**
         * 记录一次 slice 的实际执行时间。
         *
         * @param chunksInSlice    本次 slice 包含的 chunk 数
         * @param executionTimeMillis 实际执行时间（毫秒）
         */
        public void recordSliceExecution(int chunksInSlice, double executionTimeMillis) {
            if (chunksInSlice <= 0) return;

            double actualTimePerChunk = executionTimeMillis / chunksInSlice;
            timePerChunkMillis = updatePrediction(timePerChunkMillis, actualTimePerChunk);

            // 更新 chunksPerSlice 以接近目标时间
            currentChunksPerSlice = (int) (DEFAULT_TARGET_MILLIS / timePerChunkMillis);
            currentChunksPerSlice = Math.max(MIN_CHUNKS_PER_SLICE,
                    Math.min(MAX_CHUNKS_PER_SLICE, currentChunksPerSlice));

            totalSlices++;
        }

        /**
         * 获取当前推荐的 chunks per slice。
         */
        public int getChunksPerSlice() {
            return currentChunksPerSlice;
        }

        /**
         * 获取当前预测的每 chunk 执行时间。
         */
        public double getPredictedTimePerChunk() {
            return timePerChunkMillis;
        }

        public long getRegionId() { return regionId; }
        public int getTotalSlices() { return totalSlices; }

        @Override
        public String toString() {
            return "RegionAdaptiveSlicer{regionId=" + regionId +
                    ", timePerChunk=" + String.format("%.4f", timePerChunkMillis) +
                    "ms, chunksPerSlice=" + currentChunksPerSlice +
                    ", totalSlices=" + totalSlices + "}";
        }
    }
}
