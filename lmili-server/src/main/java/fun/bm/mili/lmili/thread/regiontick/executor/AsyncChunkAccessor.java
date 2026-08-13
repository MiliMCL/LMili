package fun.bm.mili.lmili.thread.regiontick.executor;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AsyncChunkAccessor {

    private AsyncChunkAccessor() {}

    public static @Nullable LevelChunk getLoadedChunk(final @NotNull ServerLevel level,
                                                       final int chunkX, final int chunkZ) {
        if (TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            return level.getChunkSource().getChunk(chunkX, chunkZ, false);
        }
        return level.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ);
    }

    public static @Nullable LevelChunk getLoadedChunk(final @NotNull ServerLevel level,
                                                       final long packedPos) {
        return getLoadedChunk(level, ChunkPos.getX(packedPos), ChunkPos.getZ(packedPos));
    }
}
