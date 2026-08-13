package fun.bm.mili.lmili.thread.regiontick.migration;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TickMigrationQueue {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile TickMigrationQueue instance;

    private final ConcurrentLinkedQueue<MigrationRequest> pendingMigrations = new ConcurrentLinkedQueue<>();
    private final List<MigrationProcessor> processors = new ArrayList<>();
    private long totalMigrations;

    private TickMigrationQueue() {}

    public static TickMigrationQueue getInstance() {
        TickMigrationQueue existing = instance;
        if (existing != null) return existing;
        synchronized (TickMigrationQueue.class) {
            if (instance == null) instance = new TickMigrationQueue();
            return instance;
        }
    }

    public void enqueueMigration(final @NotNull Entity entity,
                                  final @Nullable Long fromRegion,
                                  final @Nullable Long toRegion,
                                  final @NotNull ChunkPos targetPos) {
        pendingMigrations.add(new MigrationRequest(entity.getId(), fromRegion, toRegion, targetPos, System.nanoTime()));
    }

    public void enqueueDeferred(final @NotNull Entity entity) {
        pendingMigrations.add(new MigrationRequest(entity.getId(), null, null, null, System.nanoTime()));
    }

    public int commitAll() {
        int committed = 0;
        MigrationRequest request;
        while ((request = pendingMigrations.poll()) != null) {
            try {
                commitOne(request);
                committed++;
            } catch (Throwable throwable) {
                LOGGER.error("[MigrationQueue] Failed to commit migration for entity #{}", request.entityId, throwable);
            }
        }
        this.totalMigrations += committed;
        return committed;
    }

    private void commitOne(final MigrationRequest request) {
        for (MigrationProcessor processor : processors) {
            if (processor.canHandle(request)) {
                processor.process(request);
                return;
            }
        }
        LOGGER.warn("[MigrationQueue] No processor for migration type {} of entity #{}", request.type, request.entityId);
    }

    public void registerProcessor(final @NotNull MigrationProcessor processor) {
        synchronized (processors) {
            processors.add(processor);
            processors.sort(Comparator.comparingInt(MigrationProcessor::priority).reversed());
        }
    }

    public int pendingCount() { return pendingMigrations.size(); }
    public long totalMigrations() { return this.totalMigrations; }

    record MigrationRequest(
            int entityId,
            @Nullable Long fromRegionId,
            @Nullable Long toRegionId,
            @Nullable ChunkPos targetPos,
            long enqueueTimeNanos,
            MigrationType type
    ) {
        MigrationRequest(final int entityId, final @Nullable Long fromRegionId,
                        final @Nullable Long toRegionId, final @Nullable ChunkPos targetPos,
                        final long enqueueTimeNanos) {
            this(entityId, fromRegionId, toRegionId, targetPos, enqueueTimeNanos, MigrationType.REGION_CHANGE);
        }
    }

    enum MigrationType { REGION_CHANGE, CHUNK_ONLY, DEFERRED }

    public interface MigrationProcessor {
        boolean canHandle(@NotNull MigrationRequest request);
        void process(@NotNull MigrationRequest request);
        default int priority() { return 0; }
    }
}
