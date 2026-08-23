package fun.bm.mili.lmili.api;

import fun.bm.mili.lmili.api.identity.DefaultPluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Top-level entry point to the LMili runtime. Plugin authors access the
 * identity system via {@code LMili.getPluginIdentityManager()} and
 * {@code LMili.getRuntimeContext(id)}.
 *
 * <p>The manager is process-wide and lives for the lifetime of the JVM.
 * Tests should call {@link #resetForTests()} between scenarios.</p>
 */
public final class LMili {

    /** System owner id used for tasks that originate in LMili itself (bootstrap, etc.). */
    public static final PluginId SYSTEM_OWNER_ID = PluginId.parse("lmili.system");

    private static final AtomicReference<PluginIdentityManager> MANAGER_REF =
            new AtomicReference<>(new DefaultPluginIdentityManager());

    /**
     * Per-thread "current task owner" used by the scheduler adapter to stamp
     * every submitted task with its owning PluginId (V2 §18).
     */
    private static final ThreadLocal<PluginId> CURRENT_OWNER = new ThreadLocal<>();

    private LMili() {}

    @NotNull
    public static PluginIdentityManager getPluginIdentityManager() {
        return MANAGER_REF.get();
    }

    /**
     * Replace the manager. Server-side only — used to install a configured
     * implementation during bootstrap. Subsequent calls to
     * {@link #getPluginIdentityManager()} return the new instance.
     */
    public static void setPluginIdentityManager(@NotNull final PluginIdentityManager mgr) {
        MANAGER_REF.set(mgr);
    }

    /**
     * Look up a runtime context by id. Convenience wrapper around
     * {@code LMili.getPluginIdentityManager().find(id)}.
     */
    @NotNull
    public static Optional<PluginIdentity> getIdentity(@NotNull final PluginId id) {
        return MANAGER_REF.get().find(id);
    }

    /**
     * Look up the runtime context for a plugin id. The runtime context bundles
     * the identity with scheduler domain, permission, quota and observability.
     *
     * <p>The runtime builds one context per registered plugin during bootstrap.
     * This method first checks the {@link PluginRuntimeContext#forPluginId}
     * index (the authoritative live context), then falls back to building a
     * fresh context from the identity record.</p>
     *
     * @return the live registered context, or a fresh one from the identity,
     *         or {@code null} if the id is unknown.
     */
    @Nullable
    public static PluginRuntimeContext getRuntimeContext(@NotNull final PluginId id) {
        // 1. Live registered context (authoritative counters, domain, lifecycle).
        final PluginRuntimeContext live = PluginRuntimeContext.forPluginId(id);
        if (live != null) return live;

        // 2. Fallback: build a fresh context from the identity record.
        final PluginIdentity identity = MANAGER_REF.get().find(id).orElse(null);
        if (identity == null) return null;
        return PluginRuntimeContext.forIdentity(identity);
    }

    /**
     * Reset the runtime to a fresh manager. Intended for tests.
     */
    public static void resetForTests() {
        MANAGER_REF.set(new DefaultPluginIdentityManager());
    }

    /**
     * Bind the current thread's task owner to {@code id}. Tasks submitted via
     * {@code Mili.scheduler()} inside this thread will carry this id as the
     * owner. Pair with {@link #clearCurrentOwner()} in a finally block.
     */
    public static void bindCurrentOwner(@NotNull final PluginId id) {
        CURRENT_OWNER.set(id);
    }

    public static void clearCurrentOwner() {
        CURRENT_OWNER.remove();
    }

    /**
     * @return the current thread's bound owner, or {@link #SYSTEM_OWNER_ID} if none.
     */
    @NotNull
    public static PluginId currentOwnerOrSystem() {
        final PluginId id = CURRENT_OWNER.get();
        return id != null ? id : SYSTEM_OWNER_ID;
    }

    /**
     * @return the current thread's bound owner, or {@code null} if none.
     */
    @Nullable
    public static PluginId currentOwner() {
        return CURRENT_OWNER.get();
    }
}