package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-plugin runtime context bundled from the orthogonal subsystems in V2 §16:
 *
 * <pre>
 *   PluginIdentity    -- who am I?
 *   SchedulerDomain   -- when may I run?
 *   PermissionContext -- what am I allowed to do?
 *   ResourceQuota     -- how much may I consume?
 *   LifecycleState    -- where am I in the load sequence?
 *   ObservabilityContext -- what am I doing right now?
 * </pre>
 *
 * <p>Created by the runtime during plugin load. Plugins may <em>read</em> but
 * not modify their own context.</p>
 */
public final class PluginRuntimeContext {

    /** Key for {@code Plugin#getServicesManager().register(...)}. */
    public static final Class<PluginRuntimeContext> SERVICE_TYPE = PluginRuntimeContext.class;

    private final PluginIdentity identity;
    private final SchedulerDomain schedulerDomain;
    private final PermissionContext permissionContext;
    private final ResourceQuota resourceQuota;
    private final ObservabilityContext observability;
    private volatile LifecycleState lifecycleState;

    private PluginRuntimeContext(@NotNull final PluginIdentity identity,
                                 @NotNull final SchedulerDomain schedulerDomain,
                                 @NotNull final PermissionContext permissionContext,
                                 @NotNull final ResourceQuota resourceQuota,
                                 @NotNull final ObservabilityContext observability,
                                 @NotNull final LifecycleState initialState) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.schedulerDomain = Objects.requireNonNull(schedulerDomain, "schedulerDomain");
        this.permissionContext = Objects.requireNonNull(permissionContext, "permissionContext");
        this.resourceQuota = Objects.requireNonNull(resourceQuota, "resourceQuota");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.lifecycleState = initialState;
    }

    /**
     * Build the default context bundle for the supplied identity, choosing subsystem
     * defaults based on identity status and type.
     *
     * <p>Addons share their parent's SchedulerDomain (V2 §20). If the parent
     * domain is not registered yet, the addon receives a fresh domain anyway;
     * the runtime re-binds when the parent arrives.</p>
     */
    @NotNull
    public static PluginRuntimeContext forIdentity(@NotNull final PluginIdentity identity) {
        return forIdentity(identity, null);
    }

    @NotNull
    public static PluginRuntimeContext forIdentity(@NotNull final PluginIdentity identity,
                                                   @org.jetbrains.annotations.Nullable
                                                   final SchedulerDomain inheritedDomain) {
        Objects.requireNonNull(identity, "identity");
        final PluginId id = identity.id();
        final PluginStatus status = identity.status();
        final PluginType type = identity.type();

        final SchedulerDomain domain;
        if (inheritedDomain != null) {
            domain = inheritedDomain;
        } else if (type == PluginType.ADDON) {
            final Optional<PluginId> parent = identity.parentId();
            domain = parent.map(p -> SchedulerDomain.forAddon(id, p))
                    .orElseGet(() -> SchedulerDomain.forActive(id));
        } else {
            domain = switch (status) {
                case ACTIVE   -> SchedulerDomain.forActive(id);
                case OBSERVE  -> SchedulerDomain.forObserve(id);
                case CONFLICT -> SchedulerDomain.forConflict(id);
                default       -> SchedulerDomain.forRejected(id);
            };
        }

        final ResourceQuota quota = switch (status) {
            case ACTIVE   -> ResourceQuota.unlimited(id);
            case OBSERVE  -> ResourceQuota.observe(id);
            case CONFLICT -> ResourceQuota.strict(id);
            default       -> ResourceQuota.denied(id);
        };

        return new PluginRuntimeContext(
                identity,
                domain,
                PermissionContext.forStatus(status),
                quota,
                new ObservabilityContext(),
                LifecycleState.CONTEXT_BUILDING);
    }

    @NotNull public PluginIdentity identity() { return identity; }
    @NotNull public SchedulerDomain schedulerDomain() { return schedulerDomain; }
    @NotNull public PermissionContext permissionContext() { return permissionContext; }
    @NotNull public ResourceQuota resourceQuota() { return resourceQuota; }
    @NotNull public ObservabilityContext observability() { return observability; }
    @NotNull public LifecycleState lifecycleState() { return lifecycleState; }

    /**
     * Server-side: advance the lifecycle state. Plugins must never call this.
     */
    public void setLifecycleState(@NotNull final LifecycleState next) {
        this.lifecycleState = Objects.requireNonNull(next, "next");
    }

    public void requirePermission(@NotNull final PermissionLevel required) {
        if (!permissionContext.covers(required)) {
            observability.recordPermissionDenied();
            throw new SecurityException(
                    "Plugin " + identity.id().value() + " (status=" + identity.status() + ") "
                            + "lacks permission " + required + "; has "
                            + permissionContext.level());
        }
    }

    public void requireScheduleSlot() {
        if (!schedulerDomain.acceptsSubmissions()) {
            observability.recordPermissionDenied();
            throw new SecurityException(
                    "Plugin " + identity.id().value() + " in status " + identity.status()
                            + " cannot submit scheduler tasks");
        }
        if (!resourceQuota.canAdmit((int) resourceQuota.tasksRunning(),
                (int) resourceQuota.tasksQueued())) {
            observability.recordPermissionDenied();
            throw new SecurityException(
                    "Plugin " + identity.id().value() + " exceeded scheduler quota");
        }
    }

    @Override
    public String toString() {
        return "PluginRuntimeContext{identity=" + identity
                + ", scheduler=" + schedulerDomain
                + ", permission=" + permissionContext.level()
                + ", quota=" + resourceQuota
                + ", lifecycle=" + lifecycleState + "}";
    }

    // ----------------------------------------------------------------------
    // Index by PluginId for runtime lookups (V2 §18 closed-loop).
    // ----------------------------------------------------------------------

    private static final ConcurrentMap<PluginId, PluginRuntimeContext> BY_PLUGIN_ID = new ConcurrentHashMap<>();

    /** Server-side hook. Returns the previously registered context, if any. */
    @org.jetbrains.annotations.Nullable
    public static PluginRuntimeContext registerForPluginId(@NotNull final PluginId pluginId,
                                                           @NotNull final PluginRuntimeContext ctx) {
        return BY_PLUGIN_ID.put(pluginId, ctx);
    }

    /** Server-side hook. */
    public static void unregisterForPluginId(@NotNull final PluginId pluginId) {
        BY_PLUGIN_ID.remove(pluginId);
    }

    @org.jetbrains.annotations.Nullable
    public static PluginRuntimeContext forPluginId(@NotNull final PluginId pluginId) {
        return BY_PLUGIN_ID.get(pluginId);
    }

    public static boolean hasContextFor(@NotNull final PluginId pluginId) {
        return BY_PLUGIN_ID.containsKey(pluginId);
    }

    // ----------------------------------------------------------------------
    // Index by Bukkit plugin name for runtime lookups.
    // ----------------------------------------------------------------------

    private static final ConcurrentMap<String, PluginRuntimeContext> BY_PLUGIN_NAME = new ConcurrentHashMap<>();

    /** Server-side hook. */
    public static void registerForPlugin(@NotNull final String bukkitPluginName,
                                         @NotNull final PluginRuntimeContext ctx) {
        BY_PLUGIN_NAME.put(bukkitPluginName, ctx);
    }

    /** Server-side hook. */
    public static void unregisterForPlugin(@NotNull final String bukkitPluginName) {
        BY_PLUGIN_NAME.remove(bukkitPluginName);
    }

    @org.jetbrains.annotations.Nullable
    public static PluginRuntimeContext forBukkitPlugin(@NotNull final String bukkitPluginName) {
        return BY_PLUGIN_NAME.get(bukkitPluginName);
    }

    public static boolean hasContextFor(@NotNull final String bukkitPluginName) {
        return BY_PLUGIN_NAME.containsKey(bukkitPluginName);
    }

    public static void clearIndex() {
        BY_PLUGIN_ID.clear();
        BY_PLUGIN_NAME.clear();
    }
}