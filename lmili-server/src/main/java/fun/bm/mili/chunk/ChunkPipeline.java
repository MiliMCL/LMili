package fun.bm.mili.chunk;

import com.mojang.logging.LogUtils;
import fun.bm.mili.chunk.phase.ChunkPhase;
import org.bukkit.World;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk 生命周期管线协调器 —— 按阶段顺序处理 chunk 数据。
 *
 * <p>管线阶段：
 * <ol>
 *   <li>热度更新 (HotnessUpdatePhase)</li>
 *   <li>视距优化 (ViewDistancePhase)</li>
 *   <li>生命周期管理 (LifecyclePhase)</li>
 * </ol>
 */
public final class ChunkPipeline {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<World, WorldChunkData> worldData = new ConcurrentHashMap<>();
    private final List<ChunkPhase> phases;

    public ChunkPipeline(List<ChunkPhase> phases) {
        this.phases = new ArrayList<>(phases);
    }

    /**
     * 注册世界。
     */
    public void registerWorld(World world) {
        worldData.computeIfAbsent(world, WorldChunkData::new);
        LOGGER.debug("[ChunkPipeline] World registered: {}", world.getName());
    }

    /**
     * 注销世界。
     */
    public void unregisterWorld(World world) {
        worldData.remove(world);
        LOGGER.debug("[ChunkPipeline] World unregistered: {}", world.getName());
    }

    /**
     * 执行一次管线 tick —— 对每个世界依次执行所有阶段。
     */
    public void tick() {
        for (var entry : worldData.entrySet()) {
            World world = entry.getKey();
            WorldChunkData data = entry.getValue();
            try {
                for (ChunkPhase phase : phases) {
                    phase.execute(world, data);
                }
            } catch (Throwable throwable) {
                LOGGER.warn("[ChunkPipeline] Phase execution failed for world {}", world.getName(), throwable);
            }
        }
    }

    /**
     * 获取世界区块数据（只读访问）。
     */
    public WorldChunkData getWorldData(World world) {
        return worldData.get(world);
    }

    /**
     * 获取注册的世界数量。
     */
    public int getWorldCount() {
        return worldData.size();
    }

    /**
     * 清除所有世界数据（关闭时使用）。
     */
    public void clear() {
        worldData.clear();
    }
}
