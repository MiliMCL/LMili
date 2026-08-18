package fun.bm.mili.chunk.phase;

import com.mojang.logging.LogUtils;
import fun.bm.mili.chunk.ChunkHotness;
import fun.bm.mili.config.modules.optimizations.ChunkSystemConfig;
import fun.bm.mili.chunk.WorldChunkData;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生命周期管理阶段 —— 当 chunk 超过上限时，按热度从低到高卸载。
 */
public final class LifecyclePhase implements ChunkPhase {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AtomicLong totalUnloads;

    public LifecyclePhase(AtomicLong totalUnloads) {
        this.totalUnloads = totalUnloads;
    }

    @Override
    public String getName() {
        return "lifecycle-management";
    }

    @Override
    public void execute(World world, WorldChunkData data) {
        Chunk[] loadedChunks = world.getLoadedChunks();
        int loadedCount = loadedChunks.length;
        int maxLoaded = ChunkSystemConfig.maxLoadedChunks;

        if (loadedCount <= maxLoaded) return;

        List<CandidateChunk> candidates = new ArrayList<>();

        for (Chunk chunk : loadedChunks) {
            ChunkHotness hotness = data.getHotness(chunk.getX(), chunk.getZ());
            if (hotness == null) continue;
            if (!isChunkKeepAlive(chunk)) {
                candidates.add(new CandidateChunk(chunk, hotness));
            }
        }

        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingDouble(c -> c.hotness.getScore()));

        int toUnload = Math.min(
                candidates.size(),
                loadedCount - (int) (maxLoaded * ChunkSystemConfig.unloadSafetyMargin)
        );

        for (int i = 0; i < toUnload; i++) {
            CandidateChunk candidate = candidates.get(i);
            unloadChunkSafely(candidate.chunk);
            totalUnloads.incrementAndGet();
        }
    }

    private boolean isChunkKeepAlive(Chunk chunk) {
        if (chunk.getEntities().length > 0) return true;

        for (Player player : chunk.getWorld().getPlayers()) {
            Location eyeLoc = player.getEyeLocation();
            int dx = (eyeLoc.getBlockX() >> 4) - chunk.getX();
            int dz = (eyeLoc.getBlockZ() >> 4) - chunk.getZ();
            if (dx * dx + dz * dz <= 256) {
                return true;
            }
        }
        return false;
    }

    private void unloadChunkSafely(Chunk chunk) {
        try {
            if (chunk.isForceLoaded() || chunk.isLoaded()) {
                chunk.unload(true);
            }
        } catch (Throwable e) {
            LOGGER.debug("[Mili] Failed to unload chunk ({}, {})",
                    chunk.getX(), chunk.getZ());
        }
    }

    private static class CandidateChunk {
        final Chunk chunk;
        final ChunkHotness hotness;

        CandidateChunk(Chunk chunk, ChunkHotness hotness) {
            this.chunk = chunk;
            this.hotness = hotness;
        }
    }
}
