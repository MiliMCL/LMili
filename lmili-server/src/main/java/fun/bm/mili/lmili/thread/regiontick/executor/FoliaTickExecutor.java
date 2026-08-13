package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public final class FoliaTickExecutor implements fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile int tickSpeed;

    public FoliaTickExecutor() { this.tickSpeed = 3; }
    public FoliaTickExecutor(final int tickSpeed) { this.tickSpeed = Math.max(0, tickSpeed); }
    public void setTickSpeed(final int speed) { this.tickSpeed = Math.max(0, speed); }

    @Override
    public void executeSlice(@NotNull RegionTickWorker worker,
                             @NotNull RegionTickSlice slice,
                             @NotNull RegionTickContext context) {
        int sliceSize = slice.size();
        if (sliceSize == 0) return;

        ServerLevel level = getServerLevel(context);
        if (level == null) {
            LOGGER.warn("[FoliaTickExecutor] Region #{} has no ServerLevel", context.regionId);
            return;
        }

        int tickSpeed = this.tickSpeed;
        for (int i = 0; i < sliceSize; i++) {
            LevelChunk chunk = AsyncChunkAccessor.getLoadedChunk(level, slice.getChunkPos(i));
            if (chunk == null) continue;
            try {
                level.tickChunk(chunk, tickSpeed);
            } catch (Throwable throwable) {
                LOGGER.error("[FoliaTickExecutor] Failed to tick chunk {} in region #{}",
                        chunk.getPos(), context.regionId, throwable);
            }
        }
    }

    private static @Nullable ServerLevel getServerLevel(final RegionTickContext context) {
        try {
            if (context.region != null && context.region.regioniser != null) {
                return (ServerLevel) context.region.regioniser.world;
            }
        } catch (Exception e) {
            LOGGER.error("[FoliaTickExecutor] Failed to get ServerLevel for region #{}", context.regionId, e);
        }
        return null;
    }
}
