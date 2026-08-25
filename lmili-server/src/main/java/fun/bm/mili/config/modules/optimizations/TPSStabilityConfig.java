package fun.bm.mili.config.modules.optimizations;

import org.jetbrains.annotations.NotNull;

/**
 * TPS 稳定配置 —— 控制 TPS 稳定器的行为。
 *
 * <p>TPS 稳定器通过动态调整 tick 预算来保持 TPS 稳定在 20。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@link #enabled} - 是否启用 TPS 稳定器</li>
 *   <li>{@link #targetTps} - 目标 TPS</li>
 *   <li>{@link #degradeThreshold} - 降级阈值（TPS 低于此值开始降级）</li>
 *   <li>{@link #recoveryThreshold} - 恢复阈值（TPS 高于此值恢复正常）</li>
 *   <li>{@link #maxEntityLimitRatio} - 实体数量限制比例</li>
 *   <li>{@link #enableDynamicViewDistance} - 是否启用动态视距调整</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class TPSStabilityConfig {

    /**
     * 是否启用 TPS 稳定器。
     */
    public static volatile boolean enabled = true;

    /**
     * 目标 TPS。
     */
    public static volatile double targetTps = 20.0;

    /**
     * 降级阈值：TPS 低于此值开始降级。
     */
    public static volatile double degradeThreshold = 19.0;

    /**
     * 恢复阈值：TPS 高于此值恢复正常。
     */
    public static volatile double recoveryThreshold = 19.5;

    /**
     * 轻度降级阈值：TPS 低于此值进入轻度降级。
     */
    public static volatile double lightDegradeThreshold = 18.0;

    /**
     * 中度降级阈值：TPS 低于此值进入中度降级。
     */
    public static volatile double moderateDegradeThreshold = 15.0;

    /**
     * 重度降级阈值：TPS 低于此值进入重度降级。
     */
    public static volatile double severeDegradeThreshold = 12.0;

    /**
     * 实体数量限制比例（0.0-1.0）。
     * 当 TPS 低于中度降级阈值时，限制实体数量。
     */
    public static volatile double maxEntityLimitRatio = 0.75;

    /**
     * 是否启用动态视距调整。
     * 当 TPS 降低时，自动降低视距以减少负载。
     */
    public static volatile boolean enableDynamicViewDistance = true;

    /**
     * 最低视距（当 TPS 降低时不会低于此值）。
     */
    public static volatile int minViewDistance = 4;

    /**
     * 最高视距（正常情况下的视距）。
     */
    public static volatile int maxViewDistance = 12;

    /**
     * 配置路径。
     */
    public static final String CONFIG_PATH = "optimizations.tps-stability";

    private TPSStabilityConfig() {}

    /**
     * 从配置加载。
     *
     * @param config 配置映射
     */
    public static void load(@NotNull java.util.Map<String, Object> config) {
        if (config.containsKey("enabled")) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
        }
        if (config.containsKey("target-tps")) {
            Object val = config.get("target-tps");
            if (val instanceof Number) {
                targetTps = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("degrade-threshold")) {
            Object val = config.get("degrade-threshold");
            if (val instanceof Number) {
                degradeThreshold = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("recovery-threshold")) {
            Object val = config.get("recovery-threshold");
            if (val instanceof Number) {
                recoveryThreshold = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("light-degrade-threshold")) {
            Object val = config.get("light-degrade-threshold");
            if (val instanceof Number) {
                lightDegradeThreshold = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("moderate-degrade-threshold")) {
            Object val = config.get("moderate-degrade-threshold");
            if (val instanceof Number) {
                moderateDegradeThreshold = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("severe-degrade-threshold")) {
            Object val = config.get("severe-degrade-threshold");
            if (val instanceof Number) {
                severeDegradeThreshold = ((Number) val).doubleValue();
            }
        }
        if (config.containsKey("max-entity-limit-ratio")) {
            Object val = config.get("max-entity-limit-ratio");
            if (val instanceof Number) {
                maxEntityLimitRatio = Math.max(0.0, Math.min(1.0, ((Number) val).doubleValue()));
            }
        }
        if (config.containsKey("enable-dynamic-view-distance")) {
            enableDynamicViewDistance = Boolean.TRUE.equals(config.get("enable-dynamic-view-distance"));
        }
        if (config.containsKey("min-view-distance")) {
            Object val = config.get("min-view-distance");
            if (val instanceof Number) {
                minViewDistance = Math.max(2, ((Number) val).intValue());
            }
        }
        if (config.containsKey("max-view-distance")) {
            Object val = config.get("max-view-distance");
            if (val instanceof Number) {
                maxViewDistance = Math.max(minViewDistance, ((Number) val).intValue());
            }
        }
    }

    /**
     * 获取配置映射。
     *
     * @return 配置映射
     */
    @NotNull
    public static java.util.Map<String, Object> getConfig() {
        java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("enabled", enabled);
        config.put("target-tps", targetTps);
        config.put("degrade-threshold", degradeThreshold);
        config.put("recovery-threshold", recoveryThreshold);
        config.put("light-degrade-threshold", lightDegradeThreshold);
        config.put("moderate-degrade-threshold", moderateDegradeThreshold);
        config.put("severe-degrade-threshold", severeDegradeThreshold);
        config.put("max-entity-limit-ratio", maxEntityLimitRatio);
        config.put("enable-dynamic-view-distance", enableDynamicViewDistance);
        config.put("min-view-distance", minViewDistance);
        config.put("max-view-distance", maxViewDistance);
        return config;
    }

    @Override
    @NotNull
    public String toString() {
        return "TPSStabilityConfig{" +
                "enabled=" + enabled +
                ", targetTps=" + targetTps +
                ", degradeThreshold=" + degradeThreshold +
                ", recoveryThreshold=" + recoveryThreshold +
                ", enableDynamicViewDistance=" + enableDynamicViewDistance +
                ", viewDistance=[" + minViewDistance + "-" + maxViewDistance +
                "]}";
    }
}
