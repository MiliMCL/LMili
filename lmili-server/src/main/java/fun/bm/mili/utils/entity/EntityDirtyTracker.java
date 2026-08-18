package fun.bm.mili.utils.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class EntityDirtyTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean enabled = false;

    private static final ConcurrentHashMap<Integer, EntityState> states = new ConcurrentHashMap<>();
    private static final AtomicInteger totalChecks = new AtomicInteger();
    private static final AtomicInteger skippedEntities = new AtomicInteger();
    private static final AtomicLong savedTicks = new AtomicLong();
    // Mili start - fix: periodic cleanup to prevent unbounded map growth
    private static final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());
    private static final long CLEANUP_INTERVAL_MS = 60_000; // cleanup every 60 seconds
    private static final int MAX_STATES_SIZE = 32768; // hard cap to prevent OOM
    // Mili end

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    public static boolean shouldSkipTick(Entity entity) {
        if (!enabled) return false;

        // Mili start - fix: periodic cleanup to prevent unbounded map growth
        maybeCleanupStaleEntries(entity);
        // Mili end

        int id = entity.getId();
        // Mili start - fix: check size BEFORE computeIfAbsent to actually enforce the cap.
        // The previous implementation always returned a new EntityState regardless of cap,
        // so the entry was always inserted. Now we skip tracking when over limit.
        if (states.size() >= MAX_STATES_SIZE) {
            return false; // over cap, don't track this entity
        }
        EntityState state = states.computeIfAbsent(id, k -> new EntityState());
        // Mili end
        totalChecks.incrementAndGet();

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        float yRot = entity.getYRot();
        float xRot = entity.getXRot();

        double dx = x - state.lastX;
        double dy = y - state.lastY;
        double dz = z - state.lastZ;

        boolean positionChanged = (dx * dx + dy * dy + dz * dz) >
                fun.bm.mili.config.modules.optimizations.EntityDirtyTrackingConfig.positionThreshold *
                        fun.bm.mili.config.modules.optimizations.EntityDirtyTrackingConfig.positionThreshold;
        boolean rotationChanged = yRot != state.lastYRot || xRot != state.lastXRot;
        boolean velocityChanged = entity.getDeltaMovement().lengthSqr() > 0.0001;
        boolean onGroundChanged = entity.onGround() != state.wasOnGround;

        boolean dirty = positionChanged || rotationChanged || velocityChanged || onGroundChanged;

        if (dirty) {
            state.lastX = x;
            state.lastY = y;
            state.lastZ = z;
            state.lastYRot = yRot;
            state.lastXRot = xRot;
            state.wasOnGround = entity.onGround();
            state.idleTicks = 0;
        } else {
            state.idleTicks++;
        }

        int skipThreshold = fun.bm.mili.config.modules.optimizations.EntityDirtyTrackingConfig.skipIdleAfterTicks;
        if (skipThreshold > 0 && state.idleTicks >= skipThreshold) {
            int total = totalChecks.get();
            int skipped = skippedEntities.get();
            double ratio = total > 0 ? (double) skipped / total : 0;
            double maxRatio = fun.bm.mili.config.modules.optimizations.EntityDirtyTrackingConfig.maxSkipRatio;

            if (ratio < maxRatio) {
                skippedEntities.incrementAndGet();
                savedTicks.incrementAndGet();
                return true;
            }
        }

        return false;
    }

    public static void removeEntity(Entity entity) {
        states.remove(entity.getId());
    }

    // Mili start - optimize: Periodically remove entries for entities no longer in the world.
    // Entity IDs are not reused by Minecraft, so without cleanup this map grows forever.
    // Optimized: use removeReason check to detect dead entities without scanning the whole world.
    private static void maybeCleanupStaleEntries(Entity currentEntity) {
        long now = System.currentTimeMillis();
        long last = lastCleanupTime.get();
        if (now - last < CLEANUP_INTERVAL_MS) return;
        if (!lastCleanupTime.compareAndSet(last, now)) return; // only one thread cleans

        // Optimized cleanup: check if entity is still alive by querying its removeReason.
        // This avoids building a full world entity set (which is O(n) world scan).
        int removed = 0;
        java.util.Iterator<Map.Entry<Integer, EntityState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, EntityState> entry = it.next();
            int entityId = entry.getKey();
            // Check if entity is still valid via the level's entity lookup
            if (currentEntity.level() != null) {
                net.minecraft.world.entity.Entity entity = currentEntity.level().getEntity(entityId);
                // Entity is null (removed) or has been removed (removeReason != null)
                if (entity == null || entity.isRemoved()) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            LOGGER.debug("[EntityDirtyTracker] Cleaned up {} stale entries, remaining: {}", removed, states.size());
        }
    }
    // Mili end

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("Tracked Entities", states.size());
        stats.put("Total Checks", totalChecks.get());
        stats.put("Skipped Entities", skippedEntities.get());
        stats.put("Saved Ticks", savedTicks.get());
        int total = totalChecks.get();
        stats.put("Skip Rate", total > 0 ?
                String.format("%.1f%%", (double) skippedEntities.get() / total * 100) : "0%");
        return stats;
    }

    public static void reset() {
        states.clear();
        totalChecks.set(0);
        skippedEntities.set(0);
        savedTicks.set(0);
    }

    private static class EntityState {
        volatile double lastX, lastY, lastZ;
        volatile float lastYRot, lastXRot;
        volatile boolean wasOnGround;
        volatile int idleTicks;
    }
}
