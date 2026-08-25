package fun.bm.mili.config.modules.unsupported;

import org.jetbrains.annotations.NotNull;

/**
 * LMili 禁用 Folia 兼容性检查配置。
 *
 * <p><b>设计目标</b>：提供配置选项来控制是否禁用 Folia 兼容性检查。
 * 在纯 Paper/Bukkit 环境中，可以安全地禁用此检查。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@link #disableForPaper} - 是否在 Paper 环境中禁用 Folia 检查</li>
 *   <li>{@link #disableForBukkit} - 是否在 Bukkit 环境中禁用 Folia 检查</li>
 *   <li>{@link #logWhenDisabled} - 禁用时是否记录日志</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class LMiliDisableFoliaCheckConfig {

    /**
     * 是否在 Paper 环境中禁用 Folia 兼容性检查。
     *
     * <p>在纯 Paper 环境中，Folia 检查是不必要的，可以安全禁用。
     */
    public static volatile boolean disableForPaper = true;

    /**
     * 是否在 Bukkit 环境中禁用 Folia 兼容性检查。
     */
    public static volatile boolean disableForBukkit = true;

    /**
     * 当检查被禁用时是否记录日志。
     */
    public static volatile boolean logWhenDisabled = false;

    /**
     * 配置路径。     */
    public static final String CONFIG_PATH = "unsupported.disable-folia-check";

    private LMiliDisableFoliaCheckConfig() {}

    /**
     * 检查是否应该禁用 Folia 兼容性检查。
     *
     * @return true 如果应该禁用
     */
    public static boolean shouldDisableCheck() {
        return disableForPaper || disableForBukkit;
    }

    /**
     * 从配置加载。
     *
     * @param config 配置映射
     */
    public static void load(@NotNull java.util.Map<String, Object> config) {
        if (config.containsKey("disable-for-paper")) {
            disableForPaper = Boolean.TRUE.equals(config.get("disable-for-paper"));
        }
        if (config.containsKey("disable-for-bukkit")) {
            disableForBukkit = Boolean.TRUE.equals(config.get("disable-for-bukkit"));
        }
        if (config.containsKey("log-when-disabled")) {
            logWhenDisabled = Boolean.TRUE.equals(config.get("log-when-disabled"));
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
        config.put("disable-for-paper", disableForPaper);
        config.put("disable-for-bukkit", disableForBukkit);
        config.put("log-when-disabled", logWhenDisabled);
        return config;
    }

    @Override
    @NotNull
    public String toString() {
        return "LMiliDisableFoliaCheckConfig{" +
                "disableForPaper=" + disableForPaper +
                ", disableForBukkit=" + disableForBukkit +
                ", logWhenDisabled=" + logWhenDisabled +
                '}';
    }
}
