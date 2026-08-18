package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RegionTickWorker implements Runnable {

    private final String name;
    private final BlockingQueue<RegionTickSlice> taskQueue = new LinkedBlockingQueue<>();
    private final AtomicReference<RegionTickContext> currentContext = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    // Mili start - fix: use volatile for cross-thread visibility (written on worker thread, read from any thread)
    private volatile long slicesExecuted;
    private volatile long chunksTicked;
    // Mili end

    public RegionTickWorker(final String name) { this.name = name; }

    public void submit(@NotNull final RegionTickSlice slice) {
        if (slice.context != null) this.currentContext.set(slice.context);
        this.taskQueue.offer(slice);
    }

    public void shutdown() { this.running.set(false); }

    @Override
    public void run() {
        while (this.running.get()) {
            try {
                RegionTickSlice slice = this.taskQueue.poll(100, TimeUnit.MILLISECONDS);
                if (slice == null) continue;
                executeSlice(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable throwable) {
                com.mojang.logging.LogUtils.getClassLogger().error(
                        "[RegionTickWorker] {} failed", this.name, throwable);
            }
        }
    }

    private void executeSlice(final RegionTickSlice slice) {
        RegionTickContext context = slice.context;
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor == null) { context.arriveSlice(); return; }

        int count = slice.size();
        if (count == 0) { context.arriveSlice(); return; }

        try {
            executor.executeSlice(this, slice, context);
            this.chunksTicked += count;
            this.slicesExecuted++;
        } catch (Throwable throwable) {
            com.mojang.logging.LogUtils.getClassLogger().error(
                    "[RegionTickWorker] slice {} failed in region #{}", slice.sliceIndex, context.regionId, throwable);
        } finally {
            context.arriveSlice();
        }
    }

    public @Nullable RegionTickContext getCurrentContext() { return this.currentContext.get(); }
    public long getSlicesExecuted() { return this.slicesExecuted; }
    public long getChunksTicked() { return this.chunksTicked; }
    public int getPendingTasks() { return this.taskQueue.size(); }
    public String getWorkerName() { return this.name; }

    @Override
    public String toString() {
        return "RegionTickWorker{name='" + name + "', slices=" + slicesExecuted + ", chunks=" + chunksTicked + "}";
    }
}
