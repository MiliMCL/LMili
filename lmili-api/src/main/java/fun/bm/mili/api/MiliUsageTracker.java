package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which plugins are using the Mili API.
 *
 * <p>Internal utility class, used by {@link MiliPlugin#isMiliInUse()} queries.
 * Task submissions automatically record plugin origin, no manual registration needed.</p>
 */
public final class MiliUsageTracker {

    private static final Set<String> PLUGINS_IN_USE = ConcurrentHashMap.newKeySet();
    private static volatile String currentPluginName;

    private MiliUsageTracker() {}

    /**
     * 设置当前线程关联的插件名称。
     * 在任务执行前调用，用于追踪插件使用情况。
     *
     * @param pluginName 插件名称
     */
    public static void setCurrentPlugin(@Nullable String pluginName) {
        currentPluginName = pluginName;
    }

    /**
     * 清除当前线程关联的插件名称。
     */
    public static void clearCurrentPlugin() {
        currentPluginName = null;
    }

    /**
     * 标记插件正在使用 Mili API。
     * 在任务提交时自动调用。
     */
    public static void markUsage() {
        String name = currentPluginName;
        if (name != null) PLUGINS_IN_USE.add(name);
    }

    /**
     * 检查指定插件是否在通过 Mili API 提交任务。
     *
     * @param pluginName 插件名称
     * @return true 如果有任务提交
     */
    public static boolean isPluginInUse(@NotNull String pluginName) {
        return PLUGINS_IN_USE.contains(pluginName);
    }

    /**
     * 清除所有记录（通常在 shutdown 时）。
     */
    public static void reset() {
        PLUGINS_IN_USE.clear();
        currentPluginName = null;
    }
}
