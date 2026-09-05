package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
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
 * <p><b>P0-2</b>：worker 线程使用 {@link RegionTickExecutor#executeSliceStage} 非阻塞提交，
 * 把 slice barrier 完成通知交由 DAG 完成回调驱动；worker 线程不再因内部 join 而阻塞。</p>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>每个 Worker 运行在独立线程上</li>
 *   <li>submit 可以从任意线程调用</li>
 *   <li>executeSlice 在 Worker 线程上执行</li>
 *   <li>DAG 节点执行在 MiliScheduler worker 线程上（由 NodeScheduler 路由）</li>
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
     *
     * <p>P0-2 重构：使用 {@link RegionTickExecutor#executeSliceStage} 非阻塞提交，
     * 把 slice barrier 完成通知交由 DAG 完成回调驱动；worker 线程自身不再同步阻塞。
     * </p>
     */
    private void executeSlice(final RegionTickSlice slice) {
        RegionTickContext context = slice.context;
        if (context == null) return;

        // 捕获 slice 的 generationId —— 禁止依赖当前 Context generation
        final long generationId = slice.getGenerationId();

        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor == null) {
            // 没有执行器可用 → 同步登记到达（与未注册 executor 兼容）
            context.arriveSlice(generationId);
            return;
        }

        int count = slice.size();
        if (count == 0) {
            context.arriveSlice(generationId);
            return;
        }

        CompletionStage<Void> sliceStage = null;
        try {
            // 更新 slice 状态
            slice.state = RegionTickSlice.SliceState.RUNNING;

            // P0-2：非阻塞提交。完成阶段在 DAG 真正完成时（在 scheduler worker 上）触发，
            // 由 wireSliceCompletion 驱动 arriveSlice/failSlice —— 不再依赖 executor 内部 join。
            sliceStage = executor.executeSliceStage(this, slice, context);
            // 修复：使用 LongAdder 保证原子递增
            this.chunksTicked.add(count);
            this.slicesExecuted.increment();

            // 标记完成（slice 提交完成，节点执行由 DAG 引擎 + scheduler 驱动）
            slice.state = RegionTickSlice.SliceState.COMPLETED;
        } catch (Throwable throwable) {
            com.mojang.logging.LogUtils.getClassLogger().error(
                    "[RegionTickWorker] slice {} failed for region #{}", slice.sliceIndex, context.regionId, throwable);
            context.failSlice(generationId, throwable);
            slice.state = RegionTickSlice.SliceState.CANCELLED;
        } finally {
            // P0-2：arriveSlice 不再由 worker 线程同步调用 —— 改为 DAG 完成回调驱动。
            // 但如果 executor 抛出同步异常 / stage 为 null，则仍需同步登记到达以避免 slice 永久挂起。
            if (sliceStage != null) {
                wireSliceCompletion(context, generationId, sliceStage);
            } else {
                context.arriveSlice(generationId);
            }
        }
    }

    /**
     * P0-2 辅助：把 DAG 完成阶段连接到 slice barrier。{@code arriveSlice} 内部
     * 校验 generationId，旧 generation 的迟到完成自动作为 late completion 丢弃。
     */
    private static void wireSliceCompletion(final RegionTickContext context,
                                            final long generationId,
                                            final CompletionStage<Void> stage) {
        stage.whenComplete((result, error) -> {
            if (error != null) {
                context.failSlice(generationId, error);
            } else {
                context.arriveSlice(generationId);
            }
        });
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