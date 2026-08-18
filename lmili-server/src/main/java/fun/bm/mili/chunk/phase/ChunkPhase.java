package fun.bm.mili.chunk.phase;

import fun.bm.mili.chunk.WorldChunkData;
import org.bukkit.World;

/**
 * Chunk 生命周期阶段接口 —— 定义 chunk 管线中每个阶段的执行契约。
 */
public interface ChunkPhase {

    /**
     * 返回阶段名称（用于诊断和日志）。
     */
    String getName();

    /**
     * 执行该阶段逻辑。
     *
     * @param world 当前世界
     * @param data  世界区块数据
     */
    void execute(World world, WorldChunkData data);
}
