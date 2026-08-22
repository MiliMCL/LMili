package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Builds a {@link PluginIdentity} from a Bukkit plugin when no {@code lmili.json}
 * is present. Produces a {@link PluginType#LEGACY} identity in the {@code legacy.*}
 * namespace so legacy jars cannot impersonate formal plugins.
 *
 * <h2>Normalization rules</h2>
 * <ol>
 *   <li>ASCII lower-case the plugin name.</li>
 *   <li>Replace any character outside {@code [a-z0-9._-]} with {@code -}.</li>
 *   <li>Collapse multiple {@code -} into a single one.</li>
 *   <li>Strip leading/trailing {@code -} and {@code .}.</li>
 *   <li>Prepend {@code legacy.}.</li>
 * </ol>
 *
 * <p>Example: {@code My_Plugin} becomes {@code legacy.my-plugin}.</p>
 *
 * <p>If normalization still yields an invalid id (e.g. an empty plugin name),
 * the fallback synthesizes {@code legacy.unknown-plugin}. The runtime never
 * refuses to register a plugin on identity grounds alone &mdash; that's the
 * whole point of the legacy namespace.</p>
 */
public final class PluginIdentityFallback {

    /** Synthetic namespace marker for legacy plugins. */
    public static final String LEGACY_NAMESPACE = "legacy";

    private PluginIdentityFallback() {}

    /**
     * Build a legacy identity from raw Bukkit plugin fields.
     *
     * @param bukkitPluginName the Bukkit plugin name (may contain uppercase, underscores, etc.)
     * @param pluginVersion    declared version, may be null
     * @return a non-null identity of type {@link PluginType#LEGACY}
     */
    @NotNull
    public static PluginIdentity forBukkit(@NotNull final String bukkitPluginName,
                                           @Nullable final String pluginVersion) {
        final PluginId id = synthesizeLegacyId(bukkitPluginName);
        final String version = (pluginVersion == null || pluginVersion.isBlank())
                ? "0.0.0" : pluginVersion;
        return PluginIdentity.of(id, bukkitPluginName, version, LEGACY_NAMESPACE,
                PluginType.LEGACY, Optional.empty(),
                "plugin.yml", bukkitPluginName);
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
        // Replace illegal characters with '-'.
        final StringBuilder sb = new StringBuilder(normalized.length());
        boolean lastWasDash = false;
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.';
            if (ok) {
                sb.append(c);
                lastWasDash = false;
            } else if (!lastWasDash) {
                sb.append('-');
                lastWasDash = true;
            }
        }
        // Strip leading/trailing '.' and '-'.
        normalized = stripEnds(sb.toString(), "-.");
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