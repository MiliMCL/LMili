package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.conflict.ConflictReason;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;

/**
 * Central registry of plugin identities. The runtime calls {@link #register}
 * during plugin load; this is the single authoritative source for "who is
 * registered under id X".
 *
 * <h2>Concurrency</h2>
 * <p>{@link #register(PluginIdentity)} is atomic with respect to other registrations
 * of the same id. Two threads racing to register the same id produce exactly one
 * successful registration; the loser is recorded as a {@link PluginIdentityConflict}.</p>
 *
 * <h2>Plugins cannot</h2>
 * <ul>
 *   <li>{@code forceReplace} an existing identity</li>
 *   <li>call any {@code replace*} variant</li>
 *   <li>change the owning id of an existing entry</li>
 * </ul>
 * These methods are intentionally absent from the interface.
 */
public interface PluginIdentityManager {

    /**
     * Atomically register an identity.
     *
     * <p>If no identity is registered under {@code identity.id()}, this succeeds
     * and the identity enters the registry with status {@link PluginStatus#DISCOVERED}.
     * The caller is expected to drive further status transitions.</p>
     *
     * <p>If an identity is already registered:</p>
     * <ul>
     *   <li>If both have the same id AND the same version, the existing entry is
     *       returned unchanged (re-registration is the runtime's signal for
     *       plugin hot-reload).</li>
     *   <li>Otherwise, the incoming identity is rejected and a
     *       {@link PluginIdentityConflict} is recorded with reason
     *       {@link ConflictReason#DUPLICATE_ID}. The conflict object is returned
     *       so the caller can log it; the existing identity remains untouched.</li>
     * </ul>
     *
     * <p>Implementations may also reject malformed inputs (e.g. addon without
     * matching parent) with {@link ConflictReason#INVALID_PARENT} or
     * {@link ConflictReason#INVALID_METADATA}.</p>
     *
     * @return the existing identity (on conflict, this is the one that stays);
     *         or the incoming identity (on first-time registration).
     */
    @NotNull
    PluginIdentity register(@NotNull PluginIdentity identity);

    /**
     * Find an identity by id. O(1).
     */
    @NotNull
    Optional<PluginIdentity> find(@NotNull PluginId id);

    /**
     * Convenience: same as {@code find(id).isPresent()}.
     */
    boolean contains(@NotNull PluginId id);

    /**
     * @return every registered identity. Order is implementation-defined but stable.
     */
    @NotNull
    Collection<PluginIdentity> getAll();

    /**
     * @return the current status of the identity under {@code id}, or
     *         {@link Optional#empty()} if not registered.
     */
    @NotNull
    Optional<PluginStatus> getStatus(@NotNull PluginId id);

    /**
     * Update the status of an already-registered identity. Returns the new
     * identity (replacement is by-value; the registry index updates atomically),
     * or empty if the id is not registered.
     *
     * <p>Server-side only. Plugins must never call this.</p>
     */
    @NotNull
    Optional<PluginIdentity> setStatus(@NotNull PluginId id, @NotNull PluginStatus status);

    /**
     * @return a snapshot of recorded conflicts, in insertion order.
     */
    @NotNull
    Collection<PluginIdentityConflict> getConflicts();

    /**
     * @return conflicts scoped to one id.
     */
    @NotNull
    Collection<PluginIdentityConflict> getConflicts(@NotNull PluginId id);

    /**
     * Look up by Bukkit plugin name (the second argument of the runtime's
     * bootstrap registration).
     */
    @NotNull
    Optional<PluginIdentity> findByBukkitName(@NotNull String bukkitPluginName);

    /**
     * Remove a registration. Idempotent. Intended for plugin unload / server
     * shutdown. Server-side only.
     */
    void unregister(@NotNull PluginId id);

    /**
     * Wipe the entire registry. Intended for tests.
     */
    void clear();

    /**
     * Snapshot count.
     */
    int size();
}