package fun.bm.mili.config.modules.fixes;

import org.jetbrains.annotations.NotNull;

/**
 * LMili 实体移动修复配置 —— 使用 LMili API 实现实体跨区域移动修复。
 *
 * <p><b>设计目标</b>：替代 Folia 的实体移动修复配置，使用 LMili 统一调度 API
 * 实现安全的实体跨区域移动处理。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@link #enabled} - 是否启用实体移动修复</li>
 *   <li>{@link #warnOnDetected} - 检测到跨区域移动时是否警告</li>
 *   <li>{@link #teleportBack} - 是否将实体传送回原位置</li>
 *   <li>{@link #maxMoveDistance} - 最大允许移动距离</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class LMiliEntityMovingFixConfig {

    /**
     * 是否启用实体移动修复。
     */
    public static volatile boolean enabled = true;

    /**
     * 检测到跨区域移动时是否警告。
     */
    public static volatile boolean warnOnDetected = true;

    /**
     * 是否将实体传送回原位置。
     *
     * <p>如果启用，当检测到实体试图移动到另一个 region 时，
     * 会将实体传送回原位置而不是允许移动。
     */
    public static volatile boolean teleportBack = false;

    /**
     * 最大允许移动距离。
     *
     * <p>如果实体移动距离超过此值，将触发修复逻辑。
     */
    public static volatile double maxMoveDistance = 16.0;

    /**
     * 配置路径。
     */
    public static final String CONFIG_PATH = "fixes.entity-moving";

    private LMiliEntityMovingFixConfig() {}

    /**
     * 检查移动距离是否超过阈值。
     *
     * @param distance 移动距离
     * @return true 如果超过阈值
     */
    public static boolean isExcessiveMovement(double distance) {
        return Math.abs(distance) > maxMoveDistance;
    }

    /**
     * 从配置加载。
     *
     * @param config 配置映射
     */
    public static void load(@NotNull java.util.Map<String, Object> config) {
        if (config.containsKey("enabled")) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
        }
        if (config.containsKey("warn-on-detected")) {
            warnOnDetected = Boolean.TRUE.equals(config.get("warn-on-detected"));
        }
        if (config.containsKey("teleport-back")) {
            teleportBack = Boolean.TRUE.equals(config.get("teleport-back"));
        }
        if (config.containsKey("max-move-distance")) {
            Object val = config.get("max-move-distance");
            if (val instanceof Number) {
                maxMoveDistance = ((Number) val).doubleValue();
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
        config.put("warn-on-detected", warnOnDetected);
        config.put("teleport-back", teleportBack);
        config.put("max-move-distance", maxMoveDistance);
        return config;
    }

    @Override
    @NotNull
    public String toString() {
        return "LMiliEntityMovingFixConfig{" +
                "enabled=" + enabled +
                ", warnOnDetected=" + warnOnDetected +
                ", teleportBack=" + teleportBack +
                ", maxMoveDistance=" + maxMoveDistance +
                '}';
    }
}
