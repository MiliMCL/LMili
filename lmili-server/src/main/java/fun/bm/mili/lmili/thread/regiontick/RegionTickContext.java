package fun.bm.mili.lmili.thread.regiontick;

import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RegionTickContext {

    public final long regionId;
    public final io.papermc.paper.threadedregions.ThreadedRegionizer
            .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;

    private final AtomicReference<LongList> ownedChunks = new AtomicReference<>(new LongArrayList());
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private volatile Phaser tickBarrier;
    private volatile long tickStartNanos;
    private volatile long lastTickDurationNanos;

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

    public void awaitTickCompletion() {
        Phaser barrier = this.tickBarrier;
        if (barrier != null) barrier.arriveAndAwaitAdvance();
    }

    public void arriveSlice() {
        Phaser barrier = this.tickBarrier;
        if (barrier != null) barrier.arrive();
    }

    public void endTick() { this.lastTickDurationNanos = System.nanoTime() - this.tickStartNanos; }
    public long getLastTickDurationNanos() { return this.lastTickDurationNanos; }
    public int getWorkerCount() { return this.workerCount.get(); }
    public void setWorkerCount(int count) { this.workerCount.set(count); }

    public int computeDesiredWorkers(final int maxWorkersPerRegion, final int parallelismThreshold) {
        int chunkCount = this.ownedChunks.get().size();
        if (chunkCount < parallelismThreshold) return 1;
        int desired = (chunkCount + parallelismThreshold - 1) / parallelismThreshold;
        return Math.min(desired, maxWorkersPerRegion);
    }

    @Override
    public String toString() {
        return "RegionTickContext{regionId=" + regionId + ", chunks=" + ownedChunks.get().size() + ", workers=" + workerCount.get() + "}";
    }
}
