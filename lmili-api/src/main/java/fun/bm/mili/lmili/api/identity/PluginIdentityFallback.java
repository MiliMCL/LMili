package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Builds a {@link PluginIdentity} from a Bukkit plugin when no {@code lmili.json}
 * is present.
 *
 * <h2>§C 26.2+ 收紧</h2>
 * <p>本类不再被 {@code PluginIdentityBootstrap} 调用 —— 26.2 起没有
 * {@code lmili.json} 的 plugin 直接被禁用，不再走 fallback 路径。
 * 类本身保留仅为：</p>
 * <ol>
 *   <li>{@link #synthesizeLegacyId(String)} —— id 合成工具（其他路径可能用到）</li>
 *   <li>{@link #fromBukkitPlugin(Object)} —— 反射探测，仍给命令路径作查询用</li>
 * </ol>
 *
 * <p>{@link #forBukkit(String, String)} 被 deprecated（标 LMILI_REQUIRED 仅为占位；
 *   实际不会触发 plugin 加载）。
 *
 * <h2>历史</h2>
 * <p>本类曾是"兼容层"的核心：当 plugin 没声明 lmili.json 时自动合成 LEGACY 身份，
 * 避免 §18.9 "不破坏现有架构"。但导致观测失效、quota 失效、线程碎片——本次重构删除。
 */
@Deprecated
public final class PluginIdentityFallback {

    /** Synthetic namespace marker for legacy plugins. */
    public static final String LEGACY_NAMESPACE = "legacy";

    private PluginIdentityFallback() {}

    /**
     * @deprecated LMili 26.2+ 不再调用本方法；plugin 没有 lmili.json 即被禁用。
     * @return 不会被 PluginIdentityBootstrap 消费的占位身份
     */
    @NotNull
    @Deprecated
    public static PluginIdentity forBukkit(@NotNull final String bukkitPluginName,
                                           @Nullable final String pluginVersion) {
        final PluginId id = synthesizeLegacyId(bukkitPluginName);
        final String version = (pluginVersion == null || pluginVersion.isBlank())
                ? "0.0.0" : pluginVersion;
        return PluginIdentity.of(id, bukkitPluginName, version, LEGACY_NAMESPACE,
                PluginType.LEGACY, Optional.empty(),
                "plugin.yml", bukkitPluginName, SchedulerDelegation.LMILI_REQUIRED);
    }

    /**
     * Derive from any {@link org.bukkit.plugin.Plugin}-like object via reflection.
     * Keeps the API module free of a hard compile-time dep on Bukkit's Plugin class.
     *
     * @return null if the argument is null or doesn't implement {@code org.bukkit.plugin.Plugin}
     */
    @Nullable
    public static PluginIdentity fromBukkitPlugin(@Nullable final Object plugin) {
        if (plugin == null) return null;
        try {
            final Class<?> pluginCls = Class.forName("org.bukkit.plugin.Plugin");
            if (!pluginCls.isInstance(plugin)) return null;
            final String name = (String) pluginCls.getMethod("getName").invoke(plugin);
            String version = null;
            final Object desc = pluginCls.getMethod("getDescription").invoke(plugin);
            if (desc != null) {
                try {
                    final Object v = desc.getClass().getMethod("getVersion").invoke(desc);
                    version = v == null ? null : v.toString();
                } catch (final NoSuchMethodException ignored) { }
            }
            return forBukkit(name, version);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Build the canonical {@code legacy.<normalized>} id for a Bukkit plugin name.
     * Visible for testing.
     */
    @NotNull
    public static PluginId synthesizeLegacyId(@NotNull final String bukkitPluginName) {
        String normalized = bukkitPluginName.trim().toLowerCase();
        // Replace illegal characters (including dots) with '-'.
        final StringBuilder sb = new StringBuilder(normalized.length());
        boolean lastWasDash = false;
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok) {
                sb.append(c);
                lastWasDash = false;
            } else if (!lastWasDash) {
                sb.append('-');
                lastWasDash = true;
            }
        }
        // Strip leading/trailing '-'.
        normalized = stripEnds(sb.toString(), "-");
        if (normalized.isEmpty()) {
            normalized = "unknown-plugin";
        }
        final String candidate = LEGACY_NAMESPACE + "." + normalized;
        // PluginId.parse never fails on a properly normalized legacy name, but be defensive.
        try {
            return PluginId.parse(candidate);
        } catch (final RuntimeException e) {
            return PluginId.parse(LEGACY_NAMESPACE + ".unknown-plugin");
        }
    }

    private static String stripEnds(@NotNull final String s, @NotNull final String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }
}