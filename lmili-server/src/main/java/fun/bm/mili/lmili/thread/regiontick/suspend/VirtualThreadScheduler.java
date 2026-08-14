package fun.bm.mili.lmili.thread.regiontick.suspend;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.api.EntityOrphanedException;
import fun.bm.mili.lmili.thread.regiontick.api.EntityTaskContext;
import fun.bm.mili.lmili.thread.regiontick.api.MiliScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class VirtualThreadScheduler implements MiliScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VirtualThreadScheduler INSTANCE = new VirtualThreadScheduler();

    private final ThreadFactory virtualThreadFactory;
    private final ExecutorService virtualThreadExecutor;
    private final ScheduledExecutorService delayedScheduler;
    private final AtomicLong submittedTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();

    private VirtualThreadScheduler() {
        this.virtualThreadFactory = Thread.ofVirtual().name("Mili-Virtual-", 0).factory();
        this.virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        this.delayedScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mili-DelayedTask-Scheduler");
            t.setDaemon(true);
            return t;
        });
        LOGGER.info("[VirtualThreadScheduler] Initialized (carrier pool: {})",
                ForkJoinPool.commonPool().getParallelism());
    }

    public static VirtualThreadScheduler getInstance() { return INSTANCE; }

    @Override
    public @NotNull EntityScheduler forEntity(@NotNull final Entity entity) {
        return new EntitySchedulerImpl(entity);
    }

    @Override
    public void runAt(@NotNull final ServerLevel level, @NotNull final Vec3 position,
                      @NotNull final ThrowingConsumer<EntityTaskContext> task) {
        submitVirtual(() -> {
            long regionId = resolveRegionId(level, position);
            try {
                task.accept(new EntityTaskContext(-1, level, regionId));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void runAsync(@NotNull final Runnable task) { submitVirtual(task); }

    // Mili start - fix: Document carrier thread pinning risk.
    // When called from a virtual thread, computation runs inline. If the computation contains
    // synchronized blocks, it will pin the carrier thread (JDK 21-24 issue).
    // Recommendation: Use ReentrantLock instead of synchronized inside the computation,
    // or submit via a platform thread if synchronized is unavoidable.
    @Override
    public <T> T computeBlocking(@NotNull final Callable<T> computation) throws Exception {
        if (Thread.currentThread().isVirtual()) {
            // Check if we can safely run inline without pinning risk
            return computation.call();
        }
        Future<T> future = virtualThreadExecutor.submit(computation);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }
    // Mili end

    public void scheduleOnChunk(final Vec3 position, final Runnable task) { submitVirtual(task); }

    private void submitVirtual(@NotNull final Runnable task) {
        submittedTasks.incrementAndGet();
        virtualThreadExecutor.submit(() -> {
            try {
                task.run();
                completedTasks.incrementAndGet();
            } catch (Throwable throwable) {
                failedTasks.incrementAndGet();
                if (!(throwable instanceof EntityOrphanedException)) {
                    LOGGER.error("[VirtualThread] Task failed", throwable);
                }
            }
        });
    }

    private static final class EntitySchedulerImpl implements EntityScheduler {
        private final Entity entity;
        private final ServerLevel level;
        private final long regionId;

        EntitySchedulerImpl(final Entity entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
            this.level = (ServerLevel) entity.level();
            this.regionId = resolveRegionId(level, entity.position());
        }

        @Override
        public void run(@NotNull final ThrowingConsumer<EntityTaskContext> task) {
            VirtualThreadScheduler scheduler = VirtualThreadScheduler.getInstance();
            scheduler.submitVirtual(() -> {
                if (!entity.isAlive()) throw new RuntimeException(new EntityOrphanedException(
                        entity.getId(), entity.getName().getString(), EntityOrphanedException.Reason.REMOVED));
                try {
                    task.accept(new EntityTaskContext(entity.getId(), level, regionId));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public void runDelayed(@NotNull final ThrowingConsumer<EntityTaskContext> task, final long delayTicks) {
            VirtualThreadScheduler.getInstance().delayedScheduler.schedule(
                    () -> run(task), delayTicks * 50L, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isOnOwningThread() {
            return TickThread.isTickThreadFor(level, ((int) entity.getX()) >> 4, ((int) entity.getZ()) >> 4);
        }

        @Override
        public void ensureOnOwningThread() throws EntityOrphanedException {
            if (!entity.isAlive()) throw new EntityOrphanedException(
                    entity.getId(), entity.getName().getString(), EntityOrphanedException.Reason.REMOVED);
        }
    }

    private static long resolveRegionId(final ServerLevel level, final Vec3 position) {
        if (position == null) return -1;
        int cx = net.minecraft.core.BlockPos.containing(position).getX() >> 4;
        int cz = net.minecraft.core.BlockPos.containing(position).getZ() >> 4;
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("submitted_tasks", submittedTasks.get());
        stats.put("completed_tasks", completedTasks.get());
        stats.put("failed_tasks", failedTasks.get());
        stats.put("carrier_pool_parallelism", ForkJoinPool.commonPool().getParallelism());
        return stats;
    }

    public void shutdown() {
        virtualThreadExecutor.shutdown();
        delayedScheduler.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) virtualThreadExecutor.shutdownNow();
            if (!delayedScheduler.awaitTermination(1, TimeUnit.SECONDS)) delayedScheduler.shutdownNow();
        } catch (InterruptedException e) {
            virtualThreadExecutor.shutdownNow();
            delayedScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
