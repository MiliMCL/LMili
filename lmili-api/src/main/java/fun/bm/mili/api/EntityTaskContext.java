package fun.bm.mili.api;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Per-entity tick task execution context.
 *
 * <p>Obtained via Mili.scheduler().forEntity(entity).run(ctx -> {...}).
 * Inside the callback you can safely access the entity - if the entity is gone,
 * all methods throw EntityOrphanedException.</p>
 */
public final class EntityTaskContext {

    private final UUID entityUniqueId;
    private final Server server;
    private final String worldName;

    public EntityTaskContext(final UUID entityUniqueId, @NotNull final Server server, @NotNull final String worldName) {
        this.entityUniqueId = entityUniqueId;
        this.server = server;
        this.worldName = worldName;
    }

    /**
     * Get the currently bound entity.
     *
     * @return the entity (never null)
     * @throws EntityOrphanedException if entity no longer exists or has died
     */
    public @NotNull Entity getEntity() throws EntityOrphanedException {
        World world = server.getWorld(worldName);
        if (world == null) throw new EntityOrphanedException(-1, null, EntityOrphanedException.Reason.UNLOADED);
        Entity entity = world.getEntity(entityUniqueId);
        if (entity == null || !entity.isValid()) {
            throw new EntityOrphanedException(-1, null, EntityOrphanedException.Reason.REMOVED);
        }
        return entity;
    }

    /**
     * Execute an action while the entity is still alive.
     *
     * @param action the action to execute
     * @throws EntityOrphanedException if entity no longer exists
     */
    public <T> T withEntity(@NotNull final EntityFunction<Entity, T> action) throws EntityOrphanedException {
        return action.apply(getEntity());
    }

    /**
     * Check if the entity is still alive.
     *
     * @throws EntityOrphanedException if entity no longer exists
     */
    public void checkAlive() throws EntityOrphanedException {
        getEntity();
    }

    /**
     * Execute a computation at the target location, wait for result, return to current context.
     *
     * <p>If the target is in the same region, runs synchronously.
     * If the target is in another region, schedules to the target region and suspends the virtual thread.
     * If the entity disappears during the wait, EntityOrphanedException is thrown.</p>
     *
     * @param <T> return type
     * @param targetLocation target location
     * @param computation the computation to run
     * @return the computation result
     * @throws EntityOrphanedException if entity disappears during the wait
     */
    public <T> T awaitCrossRegion(@NotNull final Location targetLocation,
                                   @NotNull final EntityFunction<EntityTaskContext, T> computation)
            throws EntityOrphanedException {
        Objects.requireNonNull(targetLocation, "targetLocation");
        Objects.requireNonNull(computation, "computation");

        checkAlive();

        // Same region: execute directly
        if (isSameRegion(targetLocation)) {
            try {
                return computation.apply(this);
            } catch (EntityOrphanedException e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException("Computation failed", t);
            }
        }

        // Cross-region: schedule to target location, virtual thread suspends to wait
        CompletableFuture<T> future = new CompletableFuture<>();
        Location target = targetLocation.clone();

        Mili.scheduler().runAt(target, ctx -> {
            try {
                future.complete(computation.apply(this));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });

        try {
            return future.join();
        } catch (CancellationException e) {
            throw new EntityOrphanedException(-1, null, EntityOrphanedException.Reason.TELEPORTED_REGION, e);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof EntityOrphanedException) throw (EntityOrphanedException) cause;
            throw new EntityOrphanedException(-1, null, EntityOrphanedException.Reason.OTHER, cause);
        }
    }

    private boolean isSameRegion(@NotNull final Location target) {
        if (target.getWorld() == null || !worldName.equals(target.getWorld().getName())) return false;
        int cx1 = entityChunkX();
        int cz1 = entityChunkZ();
        int cx2 = target.getBlockX() >> 4;
        int cz2 = target.getBlockZ() >> 4;
        // Region = 32x32 chunk area
        return (cx1 >> 5) == (cx2 >> 5) && (cz1 >> 5) == (cz2 >> 5);
    }

    private int entityChunkX() {
        try {
            return getEntity().getLocation().getBlockX() >> 4;
        } catch (EntityOrphanedException e) {
            return 0;
        }
    }

    private int entityChunkZ() {
        try {
            return getEntity().getLocation().getBlockZ() >> 4;
        } catch (EntityOrphanedException e) {
            return 0;
        }
    }

    /**
     * @return bound entity UUID
     */
    public @NotNull UUID uniqueId() { return entityUniqueId; }

    /**
     * @return entity world name
     */
    public @NotNull String worldName() { return worldName; }

    /**
     * Functional interface for withEntity and awaitCrossRegion.
     *
     * @param <T> input type
     * @param <R> return type
     */
    @FunctionalInterface
    public interface EntityFunction<T, R> {
        R apply(T t) throws EntityOrphanedException;
    }
}
