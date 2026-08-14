package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RegionTickContext {

    private static final Logger LOGGER = LogUtils.getLogger();
    public final long regionId;
    public final io.papermc.paper.threadedregions.ThreadedRegionizer
            .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;

    private final AtomicReference<LongList> ownedChunks = new AtomicReference<>(new LongArrayList());
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private volatile Phaser tickBarrier;
    private volatile long tickStartNanos;
    private volatile long lastTickDurationNanos;
    private volatile long currentTick;
    // Mili start - fix: timeout for Phaser to prevent permanent block if a worker crashes
    private static final long AWAIT_TIMEOUT_SECONDS = 30;
    // Mili end

    public RegionTickContext(
            final long regionId,
            final io.papermc.paper.threadedregions.ThreadedRegionizer
                    .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        this.regionId = regionId;
        this.region = Objects.requireNonNull(region, "region");
    }

    public void refreshOwnedChunks(@NotNull final LongList chunks) { this.ownedChunks.set(chunks); }
    public LongList getOwnedChunks() { return this.ownedChunks.get(); }

    public void beginTick(final int parties) {
        this.tickBarrier = new Phaser(parties);
        this.tickStartNanos = System.nanoTime();
    }

    // Mili start - fix: Phaser with timeout to prevent permanent block if a worker thread crashes.
    // Previously arriveAndAwaitAdvance() would block forever, freezing the region tick thread.
    // Note: Phaser.arriveAndAwaitAdvance() does NOT support timeout, so we use arrive() +
    // awaitAdvanceInterruptibly() to achieve the same effect with timeout support.
    public void awaitTickCompletion() {
        Phaser barrier = this.tickBarrier;
        if (barrier == null) return;
        int phase = barrier.arrive();
        try {
            barrier.awaitAdvanceInterruptibly(phase, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("[RegionTickContext] Region #{} tick timed out after {}s — forcing advance. " +
                            "Possible worker thread crash. Registered={}, Arrived={}, Unarrived={}",
                    regionId, AWAIT_TIMEOUT_SECONDS,
                    barrier.getRegisteredParties(), barrier.getArrivedParties(), barrier.getUnarrivedParties());
            barrier.forceTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    // Mili end

    public void arriveSlice() {
        Phaser barrier = this.tickBarrier;
        if (barrier != null) barrier.arrive();
    }

    public void endTick() { this.lastTickDurationNanos = System.nanoTime() - this.tickStartNanos; }
    public long getLastTickDurationNanos() { return this.lastTickDurationNanos; }
    public int getWorkerCount() { return this.workerCount.get(); }
    public void setWorkerCount(int count) { this.workerCount.set(count); }
    public long getCurrentTick() { return this.currentTick; }
    public void setCurrentTick(final long tick) { this.currentTick = tick; }

    public int computeDesiredWorkers(final int maxWorkersPerRegion, final int parallelismThreshold) {
        int chunkCount = this.ownedChunks.get().size();
        if (chunkCount < parallelismThreshold) return 1;
        int desired = (chunkCount + parallelismThreshold - 1) / parallelismThreshold;
        return Math.min(desired, maxWorkersPerRegion);
    }

    /**
     * 计算保证的最小 worker 数——与期望值取较大者。
     * 确保即使 region 负载不高，也能获得最低限度的并行度。
     */
    public int computeGuaranteedWorkers(final int maxWorkersPerRegion,
                                         final int minWorkersPerRegion,
                                         final int parallelismThreshold) {
        return Math.max(computeDesiredWorkers(maxWorkersPerRegion, parallelismThreshold), minWorkersPerRegion);
    }

    @Override
    public String toString() {
        return "RegionTickContext{regionId=" + regionId + ", chunks=" + ownedChunks.get().size() + ", workers=" + workerCount.get() + "}";
    }
}
