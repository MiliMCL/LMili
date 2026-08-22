package fun.bm.mili.lmili.api.identity;

/**
 * Lifecycle / governance state of a plugin identity.
 *
 * <h2>Happy path</h2>
 * <pre>
 *   DISCOVERED &rarr; LOADING &rarr; ACTIVE
 * </pre>
 *
 * <h2>Failure path</h2>
 * <pre>
 *   LOADING &rarr; FAILED
 * </pre>
 *
 * <h2>Conflict path</h2>
 * <pre>
 *   DISCOVERED &rarr; CONFLICT &rarr; OBSERVE
 * </pre>
 *
 * <h2>Operator transitions</h2>
 * <pre>
 *   OBSERVE &rarr; ACTIVE
 *   OBSERVE &rarr; DISABLED
 * </pre>
 *
 * <h2>Shutdown path</h2>
 * <pre>
 *   ACTIVE &rarr; UNLOADED
 * </pre>
 */
public enum PluginStatus {

    /** Jar discovered, metadata parsed, not yet validated. */
    DISCOVERED(false),

    /** Validation in progress (parent check, conflict check, signature check). */
    LOADING(false),

    /** Fully loaded, runtime context attached, scheduler domain bound. */
    ACTIVE(true),

    /** Running in restricted mode with full audit log. */
    OBSERVE(true),

    /** Identity rejected because another plugin owns it. */
    CONFLICT(false),

    /** Operator has disabled this plugin. */
    DISABLED(false),

    /** Loading failed. Plugin will not run. */
    FAILED(false),

    /** Plugin has been unloaded (e.g. server shutdown, reload). */
    UNLOADED(false);

    private final boolean running;

    PluginStatus(final boolean running) {
        this.running = running;
    }

    /** @return true if the plugin's {@code onEnable} should run / is running. */
    public boolean isRunning() { return running; }

    /**
     * @return true if the runtime accepts task submissions from this plugin.
     */
    public boolean canSchedule() {
        return this == ACTIVE || this == OBSERVE;
    }

    /**
     * @return true if the plugin's tasks should be auditable.
     */
    public boolean isObservable() {
        return this == OBSERVE || this == CONFLICT;
    }

    /**
     * @return true if the plugin can still transition back to ACTIVE
     *         (only {@link #OBSERVE} qualifies — {@link #CONFLICT} requires resolution).
     */
    public boolean canBePromoted() {
        return this == OBSERVE;
    }
}