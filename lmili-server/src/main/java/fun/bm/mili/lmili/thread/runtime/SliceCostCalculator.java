package fun.bm.mili.lmili.thread.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Slice Cost 计算器 —— 动态计算 Region tick 的预估执行成本。
 *
 * <p>初期模型：
 * <pre>
 * sliceCost =
 *     entityCount * ENTITY_WEIGHT
 *   + blockEntityCount * BLOCK_ENTITY_WEIGHT
 *   + scheduledTicks * SCHEDULED_TICK_WEIGHT
 *   + randomTicks * RANDOM_TICK_WEIGHT
 *   + neighborUpdates * NEIGHBOR_WEIGHT
 *   + redstoneCost * REDSTONE_WEIGHT
 * </pre>
 *
 * <h3>使用 EWMA 进行预测</h3>
 * <pre>
 * prediction = previousPrediction * 0.8 + currentCost * 0.2
 * </pre>
 */
public final class SliceCostCalculator {

    /** 权重配置 */
    public static final double ENTITY_WEIGHT = 0.005;        // 每个实体 0.005ms
    public static final double BLOCK_ENTITY_WEIGHT = 0.01;   // 每个方块实体 0.01ms
    public static final double SCHEDULED_TICK_WEIGHT = 0.002; // 每个计划刻 0.002ms
    public static final double RANDOM_TICK_WEIGHT = 0.001;   // 每个随机刻 0.001ms
    public static final double NEIGHBOR_WEIGHT = 0.003;      // 每个邻居更新 0.003ms
    public static final double REDSTONE_WEIGHT = 0.02;       // 每次红石更新 0.02ms

    /** EWMA 权重 */
    private static final double EWMA_ALPHA = 0.2;

    /** 每 slice 目标执行时间（毫秒） */
    private final double targetSliceTimeMillis;

    /** 每个 chunk 的基础开销（毫秒） */
    private static final double CHUNK_BASE_TIME = 0.05;

    /**
     * 创建 SliceCostCalculator。
     *
     * @param targetSliceTimeMillis 每个 slice 的目标执行时间（毫秒）
     */
    public SliceCostCalculator(double targetSliceTimeMillis) {
        this.targetSliceTimeMillis = targetSliceTimeMillis;
    }

    /**
     * 创建默认 SliceCostCalculator（目标 3ms/slice）。
     */
    public SliceCostCalculator() {
        this(3.0);
    }

    /**
     * 计算 Region 的总预估成本。
     *
     * @param entityCount       实体数量
     * @param blockEntityCount  方块实体数量
     * @param scheduledTicks    计划刻数量
     * @param randomTicks       随机刻数量
     * @param neighborUpdates   邻居更新次数
     * @param redstoneUpdates   红石更新次数
     * @return 预估成本（毫秒）
     */
    public double calculateCost(int entityCount, int blockEntityCount,
                                 int scheduledTicks, int randomTicks,
                                 int neighborUpdates, int redstoneUpdates) {
        return entityCount * ENTITY_WEIGHT
                + blockEntityCount * BLOCK_ENTITY_WEIGHT
                + scheduledTicks * SCHEDULED_TICK_WEIGHT
                + randomTicks * RANDOM_TICK_WEIGHT
                + neighborUpdates * NEIGHBOR_WEIGHT
                + redstoneUpdates * REDSTONE_WEIGHT;
    }

    /**
     * 计算 slice 数量。
     *
     * @param totalCost         总成本（毫秒）
     * @param chunksPerSlice    每个 slice 的 chunk 数上限
     * @param totalChunks       总 chunk 数
     * @return 推荐的 slice 数量
     */
    public int calculateSliceCount(double totalCost, int chunksPerSlice, int totalChunks) {
        if (totalChunks == 0) return 0;

        // 基于成本的 slice 数量
        int costBasedSlices = (int) Math.ceil(totalCost / targetSliceTimeMillis);

        // 基于 chunk 数量的 slice 数量
        int chunkBasedSlices = (int) Math.ceil((double) totalChunks / chunksPerSlice);

        // 取较大值（确保不会过大）
        return Math.max(1, Math.max(costBasedSlices, chunkBasedSlices));
    }

    /**
     * 计算每个 slice 应有的 chunk 数量。
     *
     * @param totalChunks     总 chunk 数
     * @param sliceCount      slice 数量
     * @return 每个 slice 的 chunk 数量
     */
    public int chunksPerSlice(int totalChunks, int sliceCount) {
        if (sliceCount <= 0) return totalChunks;
        return (int) Math.ceil((double) totalChunks / sliceCount);
    }

    /**
     * 获取目标 slice 时间。
     */
    public double getTargetSliceTimeMillis() {
        return targetSliceTimeMillis;
    }

    /**
     * 使用 EWMA 预测下次成本。
     *
     * @param previousPrediction 上次预测值
     * @param currentCost        当前实际成本
     * @return 新的预测值
     */
    public static double predictNextCost(double previousPrediction, double currentCost) {
        return previousPrediction * (1 - EWMA_ALPHA) + currentCost * EWMA_ALPHA;
    }

    /**
     * Region 成本预测器 —— 维护每个 Region 的成本历史。
     */
    public static final class RegionCostPredictor {

        private final AtomicLong predictedCost = new AtomicLong(Double.doubleToLongBits(1.0));
        private final AtomicLong lastActualCost = new AtomicLong(Double.doubleToLongBits(0.0));
        private final LongAdder totalPredictions = new LongAdder();
        private final LongAdder totalErrors = new LongAdder();

        /**
         * 记录实际成本并更新预测。
         *
         * @param actualCost 实际成本（毫秒）
         */
        public void recordActualCost(double actualCost) {
            double prevPrediction = Double.longBitsToDouble(predictedCost.get());
            double newPrediction = predictNextCost(prevPrediction, actualCost);

            predictedCost.set(Double.doubleToLongBits(newPrediction));
            lastActualCost.set(Double.doubleToLongBits(actualCost));
            totalPredictions.increment();

            // 记录预测误差
            double error = Math.abs(newPrediction - actualCost);
            totalErrors.add((long) (error * 1000)); // 存储为微秒
        }

        /**
         * 获取预测的下一次成本。
         */
        public double getPredictedCost() {
            return Double.longBitsToDouble(predictedCost.get());
        }

        /**
         * 获取上次实际成本。
         */
        public double getLastActualCost() {
            return Double.longBitsToDouble(lastActualCost.get());
        }

        /**
         * 获取平均预测误差（毫秒）。
         */
        public double getAverageError() {
            long predictions = totalPredictions.sum();
            return predictions > 0 ? totalErrors.sum() / (predictions * 1000.0) : 0;
        }

        @Override
        public String toString() {
            return "RegionCostPredictor{predicted=" + String.format("%.3f", getPredictedCost()) +
                    "ms, actual=" + String.format("%.3f", getLastActualCost()) +
                    "ms, avgError=" + String.format("%.3f", getAverageError()) + "ms}";
        }
    }
}
