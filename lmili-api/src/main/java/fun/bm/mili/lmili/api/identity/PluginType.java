package fun.bm.mili.lmili.api.identity;

/**
 * Distinguishes formal LMili plugins from legacy Bukkit/Paper/Folia jars
 * that have not migrated to {@code lmili.json}.
 *
 * <p>Per the V2 spec, LEGACY plugins are isolated into the {@code legacy.*}
 * namespace so they cannot impersonate a formal plugin id.</p>
 */
public enum PluginType {
    /** Formal LMili plugin declared via {@code lmili.json} (or default). */
    PLUGIN,
    /** Formal LMili addon — must declare {@code parent}. */
    ADDON,
    /** Legacy Bukkit/Paper/Folia plugin without {@code lmili.json}. */
    LEGACY;

    /** @return true if this plugin has a parent (always true for ADDON, never for others). */
    public boolean hasParent() { return this == ADDON; }
}