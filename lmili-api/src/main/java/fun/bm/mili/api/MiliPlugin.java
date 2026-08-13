package fun.bm.mili.api;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Utility class for interacting with plugin.yml metadata.
 *
 * <p>Plugin developers can declare mili-supported: true in plugin.yml (optional),
 * to indicate to Mili and users that the plugin is aware of and uses the
 * Mili suspend-style API.</p>
 */
public final class MiliPlugin {

    private static final ConcurrentHashMap<String, Boolean> SUPPORT_CACHE = new ConcurrentHashMap<>();

    private final Plugin plugin;

    private MiliPlugin(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get the Mili helper instance for a plugin.
     *
     * @param plugin the plugin to check
     * @return MiliPlugin helper
     */
    public static @NotNull MiliPlugin of(@NotNull final Plugin plugin) {
        return new MiliPlugin(Objects.requireNonNull(plugin, "plugin"));
    }

    /**
     * Check if a plugin declares mili-supported: true in plugin.yml.
     *
     * <p>This is a weak-constraint declaration, used as informational marker,
     * does not affect actual plugin execution. Mili framework does not require
     * this declaration to use Mili.scheduler().</p>
     *
     * @param plugin the plugin to check
     * @return true if plugin.yml has mili-supported: true
     */
    public static boolean isMiliSupported(@NotNull final Plugin plugin) {
        return SUPPORT_CACHE.computeIfAbsent(plugin.getName(), name -> checkPluginYml(plugin));
    }

    /**
     * Invalidate cache for a plugin (typically called on plugin reload).
     *
     * @param pluginName plugin name
     */
    public static void invalidateCache(@NotNull final String pluginName) {
        SUPPORT_CACHE.remove(pluginName);
    }

    /**
     * Invalidate all cached data.
     */
    public static void invalidateAllCache() {
        SUPPORT_CACHE.clear();
    }

    /**
     * Check whether this plugin declares mili-supported.
     *
     * @return true if mili-supported is declared
     */
    public boolean isMiliSupported() {
        return isMiliSupported(plugin);
    }

    /**
     * Check whether this plugin is actually using Mili API (by detecting call records).
     * Always returns false if Mili is not loaded.
     *
     * @return true if this plugin has submitted tasks via Mili.scheduler()
     */
    public boolean isMiliInUse() {
        if (!Mili.isSupported()) return false;
        return MiliUsageTracker.isPluginInUse(plugin.getName());
    }

    /**
     * @return Bukkit Plugin object
     */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * @return plugin Logger
     */
    public @NotNull Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public String toString() {
        return "MiliPlugin{plugin=" + plugin.getName() + ", miliSupported=" + isMiliSupported() + "}";
    }

    @SuppressWarnings("unchecked")
    private static boolean checkPluginYml(@NotNull final Plugin plugin) {
        try {
            PluginDescriptionFile desc = plugin.getDescription();
            java.lang.reflect.Method getRaw = desc.getClass().getMethod("getRaw");
            Object raw = getRaw.invoke(desc);
            if (raw instanceof java.util.Map<?, ?> map) {
                Object value = map.get("mili-supported");
                if (value == null) value = map.get("mili_supported");
                return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
            }
        } catch (NoSuchMethodException e) {
            try {
                for (String s : plugin.getDescription().getProvides()) {
                    if ("mili".equalsIgnoreCase(s) || "mili-supported".equalsIgnoreCase(s)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) { }
        return false;
    }
}
