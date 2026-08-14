package fun.bm.mili.utils;

import fun.bm.mili.config.modules.optimizations.CrossDimensionTeleportQueueConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CrossDimensionTeleportQueue {
    private static volatile boolean enabled = false;
    private static final ConcurrentLinkedQueue<TeleportRequest> queue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static final AtomicLong totalWaitTime = new AtomicLong();
    // Mili start - fix: use AtomicInteger counter instead of O(n) queue.size()
    private static final AtomicInteger queueSize = new AtomicInteger();
    // Mili end

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    public static boolean enqueueTeleport(org.bukkit.entity.Entity entity, Location destination) {
        if (!enabled) return false;
        // Mili start - fix: use AtomicInteger counter instead of O(n) queue.size()
        if (queueSize.incrementAndGet() > CrossDimensionTeleportQueueConfig.maxQueueSize) {
            queueSize.decrementAndGet();
            return false;
        }
        // Mili end

        Entity nmsEntity = ((CraftEntity) entity).getHandle();
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> destKey =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(destination.getWorld().getName()));
        ServerLevel destLevel = MinecraftServer.getServer().getLevel(destKey);

        if (destLevel == null) {
            // Mili start - fix: decrement counter since enqueue failed
            queueSize.decrementAndGet();
            // Mili end
            return false;
        }

        boolean isPlayer = entity instanceof org.bukkit.entity.Player;
        long createTime = System.nanoTime();

        queue.offer(new TeleportRequest(nmsEntity, destLevel,
                new net.minecraft.world.phys.Vec3(destination.getX(), destination.getY(), destination.getZ()),
                destination.getYaw(), destination.getPitch(),
                isPlayer, createTime));

        return true;
    }

    // Mili start - fix: Process only up to the per-tick limit instead of draining
    // the entire queue and re-queuing excess items (which caused O(n) wasted work per tick).
    public static void processQueue() {
        if (!enabled) return;

        int processedThisTick = 0;
        int maxPerTick = 10;
        TeleportRequest request;

        List<TeleportRequest> playerRequests = new ArrayList<>();
        List<TeleportRequest> entityRequests = new ArrayList<>();

        // Only poll up to maxPerTick alive requests; leave the rest in the queue
        while (processedThisTick < maxPerTick && (request = queue.poll()) != null) {
            queueSize.decrementAndGet();
            if (!request.entity.isAlive()) {
                failed.incrementAndGet();
                continue;
            }

            if (request.isPlayer) {
                playerRequests.add(request);
            } else {
                entityRequests.add(request);
            }
            processedThisTick++;
        }

        if (CrossDimensionTeleportQueueConfig.priorityPlayers) {
            playerRequests.sort(Comparator.comparingLong(r -> -r.createTime));
        }

        int processedCount = 0;
        for (TeleportRequest req : playerRequests) {
            if (!executeTeleport(req)) break;
            processedCount++;
        }
        for (TeleportRequest req : entityRequests) {
            if (!executeTeleport(req)) break;
            processedCount++;
        }
    }

    private static boolean executeTeleport(TeleportRequest req) {
        try {
            long waitNanos = System.nanoTime() - req.createTime;
            totalWaitTime.addAndGet(waitNanos / 1_000_000);

            boolean success = req.entity.teleportAsync(req.destLevel, req.pos, (float) req.yaw, (float) req.pitch,
                    req.entity.getDeltaMovement(),
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN, 0L,
                    null // callback not needed; result checked via return value
            );
            // Mili start - fix: check teleport result
            if (!success) {
                failed.incrementAndGet();
                return false;
            }
            // Mili end
            processed.incrementAndGet();
            return true;
        } catch (Throwable e) {
            failed.incrementAndGet();
            return false;
        }
    }
    // Mili end

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("Enabled", enabled);
        // Mili start - fix: use AtomicInteger counter instead of O(n) queue.size()
        stats.put("Queue Size", queueSize.get());
        // Mili end
        stats.put("Processed", processed.get());
        stats.put("Failed", failed.get());
        long total = processed.get();
        stats.put("Avg Wait (ms)", total > 0 ?
                String.format("%.1f", (double) totalWaitTime.get() / total) : "0");
        return stats;
    }

    private record TeleportRequest(
            Entity entity,
            ServerLevel destLevel,
            net.minecraft.world.phys.Vec3 pos,
            float yaw, float pitch,
            boolean isPlayer,
            long createTime
    ) {}
}
