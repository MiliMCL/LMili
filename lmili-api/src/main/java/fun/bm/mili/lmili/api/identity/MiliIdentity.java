package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin-author entry point to the LMili Identity System.
 *
 * <p>Usage from a Bukkit plugin:</p>
 * <pre>{@code
 *   PluginRuntimeContext ctx = MiliIdentity.of(this);
 *   PluginId id = ctx.identity().id();
 *   ctx.requirePermission(PermissionLevel.SCHEDULER);
 * }</pre>
 */
public final class MiliIdentity {

    private MiliIdentity() {}

    /**
     * Resolve the runtime context for a Bukkit plugin. Falls back to building
     * a {@link PluginIdentityFallback#fromBukkitPlugin} identity if the
     * bootstrap hasn't registered the plugin yet (e.g. during {@code onLoad}).
     */
    @Nullable
    public static PluginRuntimeContext of(@Nullable final Object bukkitPlugin) {
        if (bukkitPlugin == null) return null;
        final String name = readName(bukkitPlugin);
        if (name == null) return null;
        final PluginRuntimeContext ctx = PluginRuntimeContext.forBukkitPlugin(name);
        if (ctx != null) return ctx;
        final PluginIdentity fallback = PluginIdentityFallback.fromBukkitPlugin(bukkitPlugin);
        if (fallback == null) return null;
        return PluginRuntimeContext.forIdentity(fallback);
    }

    @Nullable
    public static PluginIdentity identityOf(@Nullable final Object bukkitPlugin) {
        if (bukkitPlugin == null) return null;
        final PluginRuntimeContext ctx = of(bukkitPlugin);
        return ctx == null ? null : ctx.identity();
    }

    @NotNull
    public static java.util.Optional<PluginIdentity> byId(@NotNull final String id) {
        final PluginId pid = PluginId.parseNullable(id);
        if (pid == null) return java.util.Optional.empty();
        return LMili.getPluginIdentityManager().find(pid);
    }

    public static boolean exists(@NotNull final String id) {
        final PluginId pid = PluginId.parseNullable(id);
        return pid != null && LMili.getPluginIdentityManager().contains(pid);
    }

    @Nullable
    private static String readName(@NotNull final Object plugin) {
        try {
            final Class<?> pluginCls = Class.forName("org.bukkit.plugin.Plugin");
            if (!pluginCls.isInstance(plugin)) return null;
            return (String) pluginCls.getMethod("getName").invoke(plugin);
        } catch (final Throwable t) {
            return null;
        }
    }
}