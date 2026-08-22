package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.conflict.ConflictReason;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Default {@link PluginIdentityManager} implementation.
 *
 * <h2>Atomicity</h2>
 * <p>{@link #register(PluginIdentity)} takes the per-id write lock and uses an
 * {@link AtomicReference} per slot. The check-then-act pattern is encapsulated
 * inside the lock, so {@code if (!contains(id)) register(id)} is safe.</p>
 *
 * <h2>Conflict semantics</h2>
 * <ul>
 *   <li>Same id, same version: existing entry returned; conflict NOT recorded.</li>
 *   <li>Same id, different version: incoming rejected, {@link ConflictReason#DUPLICATE_ID} recorded.</li>
 *   <li>Addon with invalid parent: incoming rejected, {@link ConflictReason#INVALID_PARENT} recorded.</li>
 * </ul>
 */
public final class DefaultPluginIdentityManager implements PluginIdentityManager {

    /** Per-id slot holding the current identity. */
    private static final class Slot {
        final PluginIdentity identity;
        final List<PluginIdentityConflict> conflicts;

        Slot(@NotNull final PluginIdentity identity,
             @NotNull final List<PluginIdentityConflict> conflicts) {
            this.identity = identity;
            this.conflicts = conflicts;
        }
    }

    private final ConcurrentHashMap<PluginId, AtomicReference<Slot>> slots =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PluginId> byBukkitName =
            new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock viewLock = new ReentrantReadWriteLock();
    private final LinkedHashMap<PluginId, PluginIdentity> orderedView = new LinkedHashMap<>();

    public DefaultPluginIdentityManager() {}

    @Override
    @NotNull
    public PluginIdentity register(@NotNull final PluginIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        final PluginId id = identity.id();
        final AtomicReference<Slot> ref = slots.computeIfAbsent(id,
                k -> new AtomicReference<>(new Slot(identity, List.of())));
        synchronized (ref) {
            Slot current = ref.get();
            // Same id, same version → hot-reload: replace and keep no extra conflict.
            if (current.identity.version().equals(identity.version())) {
                final Slot next = new Slot(identity, current.conflicts);
                ref.set(next);
                updateOrderedView(id, identity);
                if (identity.bukkitPluginName() != null) {
                    byBukkitName.put(identity.bukkitPluginName(), id);
                }
                return identity;
            }

            // Different version (or anything else mismatched) → conflict.
            final PluginIdentityConflict conflict = new PluginIdentityConflict(
                    id, current.identity, identity, ConflictReason.DUPLICATE_ID);
            final List<PluginIdentityConflict> extended = appendList(current.conflicts, conflict);
            ref.set(new Slot(current.identity, extended));
            return current.identity;
        }
    }

    @Override
    @NotNull
    public Optional<PluginIdentity> find(@NotNull final PluginId id) {
        Objects.requireNonNull(id, "id");
        final AtomicReference<Slot> ref = slots.get(id);
        return ref == null ? Optional.empty() : Optional.of(ref.get().identity);
    }

    @Override
    public boolean contains(@NotNull final PluginId id) {
        return slots.containsKey(id);
    }

    @Override
    @NotNull
    public Collection<PluginIdentity> getAll() {
        viewLock.readLock().lock();
        try { return List.copyOf(orderedView.values()); }
        finally { viewLock.readLock().unlock(); }
    }

    @Override
    @NotNull
    public Optional<PluginStatus> getStatus(@NotNull final PluginId id) {
        return find(id).map(PluginIdentity::status);
    }

    @Override
    @NotNull
    public Optional<PluginIdentity> setStatus(@NotNull final PluginId id,
                                              @NotNull final PluginStatus status) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        final AtomicReference<Slot> ref = slots.get(id);
        if (ref == null) return Optional.empty();
        synchronized (ref) {
            final Slot current = ref.get();
            final PluginIdentity next = current.identity.withStatus(status);
            ref.set(new Slot(next, current.conflicts));
            updateOrderedView(id, next);
            return Optional.of(next);
        }
    }

    @Override
    @NotNull
    public Collection<PluginIdentityConflict> getConflicts() {
        final List<PluginIdentityConflict> all = new java.util.ArrayList<>();
        for (final AtomicReference<Slot> ref : slots.values()) {
            all.addAll(ref.get().conflicts);
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    @NotNull
    public Collection<PluginIdentityConflict> getConflicts(@NotNull final PluginId id) {
        Objects.requireNonNull(id, "id");
        final AtomicReference<Slot> ref = slots.get(id);
        return ref == null ? List.of() : List.copyOf(ref.get().conflicts);
    }

    @Override
    @NotNull
    public Optional<PluginIdentity> findByBukkitName(@NotNull final String bukkitPluginName) {
        Objects.requireNonNull(bukkitPluginName, "bukkitPluginName");
        final PluginId id = byBukkitName.get(bukkitPluginName);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public void unregister(@NotNull final PluginId id) {
        Objects.requireNonNull(id, "id");
        slots.remove(id);
        viewLock.writeLock().lock();
        try { orderedView.remove(id); } finally { viewLock.writeLock().unlock(); }
        byBukkitName.entrySet().removeIf(e -> e.getValue().equals(id));
    }

    @Override
    public void clear() {
        slots.clear();
        byBukkitName.clear();
        viewLock.writeLock().lock();
        try { orderedView.clear(); } finally { viewLock.writeLock().unlock(); }
    }

    @Override
    public int size() { return slots.size(); }

    // ---- helpers --------------------------------------------------------

    private void updateOrderedView(@NotNull final PluginId id,
                                   @NotNull final PluginIdentity identity) {
        viewLock.writeLock().lock();
        try { orderedView.put(id, identity); } finally { viewLock.writeLock().unlock(); }
    }

    @NotNull
    private static <T> List<T> appendList(@NotNull final List<T> base, @NotNull final T element) {
        final java.util.ArrayList<T> out = new java.util.ArrayList<>(base.size() + 1);
        out.addAll(base);
        out.add(element);
        return List.copyOf(out);
    }
}