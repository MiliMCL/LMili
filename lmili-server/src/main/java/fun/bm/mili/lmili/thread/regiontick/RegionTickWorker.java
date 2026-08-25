package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Region Tick Worker —— 从队列获取并执行 slice。
 *
 * <h3>Generation 安全</h3>
 * <ul>
 *   <li>Worker 执行 slice 时使用 slice 携带的 generationId</li>
 *   <li>完成时使用 {@code arriveSlice(generationId)} 验证</li>
 *   <li>旧 Generation 的迟到完成不会影响新 Generation</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>每个 Worker 运行在独立线程上</li>
 *   <li>submit 可以从任意线程调用</li>
 *   <li>executeSlice 在 Worker 线程上执行</li>
 * </ul>
 */
public final class RegionTickWorker implements Runnable {

    /** 修复：限制任务队列最大容量，防止无界增长导致 OOM */
    private static final int MAX_QUEUE_CAPACITY = 256;

    private final String name;
    private final BlockingQueue<RegionTickSlice> taskQueue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
    private final AtomicReference<RegionTickContext> currentContext = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    // Mili start - fix: use volatile for cross-thread visibility (written on worker thread, read from any thread)
    // 修复：使用 LongAdder 替代 volatile，保证原子递增
    private final java.util.concurrent.atomic.LongAdder slicesExecuted = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder chunksTicked = new java.util.concurrent.atomic.LongAdder();
    // Mili end

    public RegionTickWorker(final String name) { this.name = name; }

    public void submit(@NotNull final RegionTickSlice slice) {
        if (slice.context != null) this.currentContext.set(slice.context);
        // 修复：使用 offer 并检查返回值，队列满时丢弃最旧任务而不是无界增长
        if (!this.taskQueue.offer(slice)) {
            // 队列已满，丢弃最旧任务并尝试重新提交
            this.taskQueue.poll();
            if (!this.taskQueue.offer(slice)) {
                // 极端情况：仍然失败，直接执行
                com.mojang.logging.LogUtils.getClassLogger().warn(
                        "[RegionTickWorker] {} queue full, dropping slice for region #{}",
                        this.name, slice.context != null ? slice.context.regionId : -1);
            }
        }
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

    /**
     * 执行 slice —— 使用 slice 携带的 generationId 进行完成通知。
     */
    private void executeSlice(final RegionTickSlice slice) {
        RegionTickContext context = slice.context;
        if (context == null) return;

        // 捕获 slice 的 generationId —— 禁止依赖当前 Context generation
        final long generationId = slice.getGenerationId();

        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor == null) { context.arriveSlice(generationId); return; }

        int count = slice.size();
        if (count == 0) { context.arriveSlice(generationId); return; }

        try {
            // 更新 slice 状态
            slice.state = RegionTickSlice.SliceState.RUNNING;

            executor.executeSlice(this, slice, context);
            // 修复：使用 LongAdder 保证原子递增
            this.chunksTicked.add(count);
            this.slicesExecuted.increment();

            // 标记完成
            slice.state = RegionTickSlice.SliceState.COMPLETED;
        } catch (Throwable throwable) {
            com.mojang.logging.LogUtils.getClassLogger().error(
                    "[RegionTickWorker] slice {} failed in region #{}", slice.sliceIndex, context.regionId, throwable);
            context.failSlice(generationId, throwable);
            slice.state = RegionTickSlice.SliceState.CANCELLED;
        } finally {
            // 使用 slice 携带的 generationId —— 不依赖"当前" Context generation
            context.arriveSlice(generationId);
        }
    }

    public @Nullable RegionTickContext getCurrentContext() { return this.currentContext.get(); }
    public long getSlicesExecuted() { return this.slicesExecuted.sum(); }
    public long getChunksTicked() { return this.chunksTicked.sum(); }
    public int getPendingTasks() { return this.taskQueue.size(); }
    public String getWorkerName() { return this.name; }

    @Override
    public String toString() {
        return "RegionTickWorker{name='" + name + "', slices=" + slicesExecuted.sum() + ", chunks=" + chunksTicked.sum() + "}";
    }
}
