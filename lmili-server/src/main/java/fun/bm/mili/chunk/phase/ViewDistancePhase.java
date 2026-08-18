package fun.bm.mili.chunk.phase;

import fun.bm.mili.chunk.ChunkHotness;
import fun.bm.mili.config.modules.optimizations.ChunkSystemConfig;
import fun.bm.mili.chunk.WorldChunkData;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * 视距优化阶段 —— 根据 chunk 平均负载动态调整世界视距。
 */
public final class ViewDistancePhase implements ChunkPhase {

    @Override
    public String getName() {
        return "view-distance-optimization";
    }

    @Override
    public void execute(World world, WorldChunkData data) {
        if (!ChunkSystemConfig.dynamicViewDistance) return;
        if (world.getPlayers().isEmpty()) return;

        Chunk[] loadedChunks = world.getLoadedChunks();
        double avgLoad = 0;
        int sampleCount = 0;

        for (Chunk chunk : loadedChunks) {
            ChunkHotness hotness = data.getHotness(chunk.getX(), chunk.getZ());
            if (hotness != null && hotness.isActive()) {
                avgLoad += hotness.getAccessCount();
                sampleCount++;
            }
        }

        if (sampleCount == 0) return;
        avgLoad /= sampleCount;

        int currentVD = world.getViewDistance();
        int targetVD = currentVD;

        if (avgLoad > ChunkSystemConfig.vdDecreaseThreshold && currentVD > ChunkSystemConfig.minViewDistance) {
            targetVD = currentVD - 1;
        } else if (avgLoad < ChunkSystemConfig.vdIncreaseThreshold && currentVD < ChunkSystemConfig.maxViewDistance) {
            targetVD = currentVD + 1;
        }

        if (targetVD != currentVD && data.canAdjustViewDistance()) {
            world.setViewDistance(targetVD);
            data.recordViewDistanceAdjustment();
        }
    }
}
