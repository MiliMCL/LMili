package fun.bm.mili.lmili.thread.regiontick.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public final class EntityTaskContext {

    private final int entityId;
    private final ServerLevel level;
    private final long regionId;

    public EntityTaskContext(final int entityId, final ServerLevel level, final long regionId) {
        this.entityId = entityId;
        this.level = level;
        this.regionId = regionId;
    }

    private static long packChunk(final int x, final int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public @NotNull Entity getEntity() throws EntityOrphanedException {
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.isAlive()) {
            throw new EntityOrphanedException(entityId, null, EntityOrphanedException.Reason.REMOVED);
        }
        return entity;
    }

    public <T> T withEntity(final EntityFunction<Entity, T> action) throws EntityOrphanedException {
        return action.apply(getEntity());
    }

    public void checkAlive() throws EntityOrphanedException {
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.isAlive()) {
            throw new EntityOrphanedException(entityId, null, EntityOrphanedException.Reason.REMOVED);
        }
    }

    public <T> T awaitCrossRegion(final @NotNull Vec3 targetPos,
                                   final EntityFunction<EntityTaskContext, T> computation)
            throws EntityOrphanedException {
        checkAlive();
        ChunkPos currentChunk = getCurrentChunk();
        net.minecraft.core.BlockPos targetBlockPos = net.minecraft.core.BlockPos.containing(targetPos);
        ChunkPos targetChunk = new ChunkPos(targetBlockPos.getX() >> 4, targetBlockPos.getZ() >> 4);
        if (currentChunk != null && targetChunk.equals(currentChunk)) {
            return computation.apply(this);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        getScheduler().runAt(level, targetPos, ctx -> {
            try {
                future.complete(computation.apply(this));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });

        try {
            return future.join();
        } catch (Exception e) {
            throw new EntityOrphanedException(entityId, null, EntityOrphanedException.Reason.TELEPORTED_REGION);
        }
    }

    public @Nullable ChunkPos getCurrentChunk() {
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.isAlive()) return null;
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(entity.position());
        return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public int entityId() { return this.entityId; }
    public ServerLevel level() { return this.level; }
    public long regionId() { return this.regionId; }

    private MiliScheduler getScheduler() { return MiliScheduler.getInstance(); }

    @FunctionalInterface
    public interface EntityFunction<T, R> {
        R apply(T t) throws EntityOrphanedException;
    }
}
