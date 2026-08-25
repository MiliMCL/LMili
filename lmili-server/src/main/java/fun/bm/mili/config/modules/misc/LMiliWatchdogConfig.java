package fun.bm.mili.config.modules.misc;

import org.jetbrains.annotations.NotNull;

/**
 * LMili 看门狗配置 —— 使用 LMili API 实现 tick region 超时检测。
 *
 * <p><b>设计目标</b>：替代 Folia 的看门狗配置，使用 LMili 统一调度 API
 * 实现 tick region 超时检测和报告。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@link #tickRegionTimeOutMs} - tick region 超时时间（毫秒）</li>
 *   <li>{@link #enableWatchdog} - 是否启用看门狗</li>
 *   <li>{@logOnTimeout} - 超时时是否记录日志</li>
 *   <li>{@link #maxOverTickMs} - 最大 over-tick 时间（毫秒）</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class LMiliWatchdogConfig {

    /**
     * tick region 超时时间（毫秒）。
     *
     * <p>如果 tick region 执行时间超过此值，将触发超时警告。
     */
    public static volatile long tickRegionTimeOutMs = 5000;

    /**
     * 是否启用看门狗。
     */
    public static volatile boolean enableWatchdog = true;

    /**
     * 超时时是否记录日志。
     */
    public static volatile boolean logOnTimeout = true;

    /**
     * 最大 over-tick 时间（毫秒）。
     *
     * <p>如果 tick 执行时间超过此值，将触发严重警告。
     */
    public static volatile long maxOverTickMs = 10000;

    /**
     * 配置路径。
     */
    public static final String CONFIG_PATH = "misc.watchdog";

    private LMiliWatchdogConfig() {}

    /**
     * 检查给定的执行时间是否超时。
     *
     * @param elapsedMs 执行时间（毫秒）
     * @return true 如果超时
     */
    public static boolean isTimeout(long elapsedMs) {
        return elapsedMs > tickRegionTimeOutMs;
    }

    /**
     * 检查给定的执行时间是否严重超时。
     *
     * @param elapsedMs 执行时间（毫秒）
     * @return true 如果严重超时
     */
    public static boolean isSevereTimeout(long elapsedMs) {
        return elapsedMs > maxOverTickMs;
    }

    /**
     * 从配置加载。
     *
     * @param config 配置映射
     */
    public static void load(@NotNull java.util.Map<String, Object> config) {
        if (config.containsKey("tick-region-timeout-ms")) {
            Object val = config.get("tick-region-timeout-ms");
            if (val instanceof Number) {
                tickRegionTimeOutMs = ((Number) val).longValue();
            }
        }
        if (config.containsKey("enable-watchdog")) {
            enableWatchdog = Boolean.TRUE.equals(config.get("enable-watchdog"));
        }
        if (config.containsKey("log-on-timeout")) {
            logOnTimeout = Boolean.TRUE.equals(config.get("log-on-timeout"));
        }
        if (config.containsKey("max-over-tick-ms")) {
            Object val = config.get("max-over-tick-ms");
            if (val instanceof Number) {
                maxOverTickMs = ((Number) val).longValue();
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
        config.put("tick-region-timeout-ms", tickRegionTimeOutMs);
        config.put("enable-watchdog", enableWatchdog);
        config.put("log-on-timeout", logOnTimeout);
        config.put("max-over-tick-ms", maxOverTickMs);
        return config;
    }

    @Override
    @NotNull
    public String toString() {
        return "LMiliWatchdogConfig{" +
                "tickRegionTimeOutMs=" + tickRegionTimeOutMs +
                ", enableWatchdog=" + enableWatchdog +
                ", logOnTimeout=" + logOnTimeout +
                ", maxOverTickMs=" + maxOverTickMs +
                '}';
    }
}
