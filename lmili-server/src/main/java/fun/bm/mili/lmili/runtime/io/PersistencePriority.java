package fun.bm.mili.lmili.runtime.io;

import org.jetbrains.annotations.Nullable;

/**
 * 持久化优先级合成器（需求 #5 修订，D-22）（ARCHITECTURE_AdaptiveRuntime.md §3.11 / §4.4）。
 *
 * <p>最终 FlushPriority 由多因子合成，<strong>不写死</strong>：
 * <pre>
 * PersistencePriority =
 *     RegionPriority   （region 类型：HOT/NORMAL/IDLE）
 *   + DirtyAge         （未同步脏数据量/时间：越脏越高）
 *   + PlayerPresence   （活跃玩家数：非决定性，仅加权）
 *   + PluginHint       （插件显式申请：需 PolicyController 裁决）
 *   + ShutdownUrgency  （shutdown/紧急保存：强制 CRITICAL）
 * </pre>
 */
public final class PersistencePriority {

    private PersistencePriority() {
    }

    /** 各分量权重（TickBudgetConfig 同款配置，可调）（§4.4 合成权重表） */
    public record Weights(
            double regionWeight,      // 默认 1.0
            double dirtyAgeWeight,    // 默认 0.8
            double playerWeight,      // 默认 0.5（非决定性）
            double pluginHintWeight,  // 默认 0.6
            double shutdownWeight     // 默认 2.0（压过一切）
    ) {
        public static final Weights DEFAULTS = new Weights(1.0, 0.8, 0.5, 0.6, 2.0);
    }

    /**
     * 合成打分（0~4，映射到 FlushPriority 等级）。
     *
     * @param regionLoad RegionPriority / PlayerPresence 输入
     * @param dirtyAge   DirtyAge 输入
     * @param pluginHint 插件申请（可为 null）
     * @param shutdown   shutdown/紧急保存 → 强制 CRITICAL
     */
    public static FlushPriority evaluate(
            long regionId,
            RegionLoadSnapshot regionLoad,
            DirtyAgeSnapshot dirtyAge,
            @Nullable PluginHint pluginHint,
            boolean shutdown,
            Weights weights
    ) {
        if (shutdown) {
            return FlushPriority.CRITICAL;
        }
        final double regionScore = regionLoad.regionType().priorityRank() * weights.regionWeight(); // 1..3
        final double dirtyScore = dirtyAge.dirtyFactor() * 4.0 * weights.dirtyAgeWeight();          // 0..3.2
        final double playerScore = Math.min(1.0, regionLoad.playerCount() / 8.0) * weights.playerWeight(); // 0..0.5
        double pluginScore = 0.0;
        if (pluginHint != null && pluginHint.requested().atLeast(FlushPriority.HIGH)) {
            pluginScore = 3.0 * weights.pluginHintWeight(); // 0..1.8
        }
        final double total = regionScore + dirtyScore + playerScore + pluginScore;

        // 打分区间 0~4 映射（§4.4 映射表）：
        // ≥ 3.5 → CRITICAL；≥ 2.5 → HIGH；≥ 1.5 → NORMAL；≥ 0.5 → LOW；< 0.5 → DEFERRED
        if (total >= 3.5) {
            return FlushPriority.CRITICAL;
        }
        if (total >= 2.5) {
            return FlushPriority.HIGH;
        }
        if (total >= 1.5) {
            return FlushPriority.NORMAL;
        }
        if (total >= 0.5) {
            return FlushPriority.LOW;
        }
        return FlushPriority.DEFERRED;
    }

    /**
     * 插件 hint 申请（进 PolicyController 裁决队列，非即时生效）。
     * 对外唯一入口：{@code IOController.requestFlushPriority(regionId, requested, pluginId, reason)}。
     */
    public static PluginHint pluginRequest(String pluginId, long regionId, FlushPriority requested, String reason) {
        return new PluginHint(pluginId, regionId, requested, reason, System.nanoTime());
    }
}
