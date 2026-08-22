package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

/**
 * Discrete lifecycle milestones the runtime tracks per plugin. Used by the
 * bootstrap to fire lifecycle events in the right order and by the
 * observability subsystem to record state transitions.
 */
public enum LifecycleState {
    /** Jar found on disk; metadata not yet read. */
    DISCOVERED,
    /** Reading and parsing {@code lmili.json} (or plugin.yml fallback). */
    READING,
    /** Validating id, parent, metadata. */
    VALIDATING,
    /** Registering identity with {@link PluginIdentityManager}. */
    REGISTERING,
    /** Building runtime context, scheduler domain, permission context, quota. */
    CONTEXT_BUILDING,
    /** Invoking the Bukkit plugin's {@code onEnable()}. */
    ENABLING,
    /** Plugin fully active. */
    ACTIVE,
    /** Invoking the Bukkit plugin's {@code onDisable()}. */
    DISABLING,
    /** Unregistered from the identity manager. */
    UNLOADED,
    /** Loading or runtime failed; plugin will not run. */
    FAILED;

    public boolean isTerminal() {
        return this == UNLOADED || this == FAILED;
    }

    @NotNull
    public String logPrefix() {
        return "[Lifecycle:" + name() + "]";
    }
}