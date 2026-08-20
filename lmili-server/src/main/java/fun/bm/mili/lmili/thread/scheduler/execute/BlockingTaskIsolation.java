package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阻塞任务隔离器 —— 将阻塞任务从调度器 worker 线程隔离开。
 *
 * <h3>R2-03/R2-04 修复</h3>
 * <p>阻塞任务不再导致 SchedulerWorker 提前释放 Region execution token。
 * 通过 {@link WorkStealingCoordinator.PollResult#transferTo(int)} 将 token
 * 转移给 BlockingWorker，只有 BlockingWorker 完成任务后才释放 token。
 *
 * <h3>R2-14 修复</h3>
 * <p>shutdown 使用 awaitTermination 等待所有阻塞任务完成。
 */
public final class BlockingTaskIsolation {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService blockingExecutor;
    private final AtomicInteger activeBlockingTasks = new AtomicInteger(0);
    private final AtomicInteger totalBlockingTasks = new AtomicInteger(0);

    public BlockingTaskIsolation() {
        this.blockingExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MiliBlocking-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        });
        LOGGER.info("[BlockingTaskIsolation] Initialized with dedicated thread pool");
    }

    /**
     * 执行一个阻塞任务。
     *
     * <p>R2-03 修复：接收 PollResult，将 ExecutionToken 转移给 BlockingWorker。
     * BlockingWorker 完成任务后负责释放 token。
     *
     * @param task   要执行的阻塞任务
     * @param result 轮询结果（包含待转移的 ExecutionToken）
     * @param originalWorker 原始 worker ID
     */
    public void executeBlocking(@NotNull RegionTask task,
                                 @NotNull WorkStealingCoordinator.PollResult result,
                                 int originalWorker) {
        activeBlockingTasks.incrementAndGet();
        totalBlockingTasks.incrementAndGet();

        // R2-03 修复：转移 ExecutionToken 给 BlockingWorker
        // 使用一个特殊 worker id (-1) 表示 blocking pool
        RegionState.ExecutionToken token = result.transferTo(-1);

        if (token == null) {
            // token 转移失败（可能已被释放），直接执行
            LOGGER.debug("[BlockingTaskIsolation] Token transfer failed for region #{}, executing immediately",
                    task.regionId());
            blockingExecutor.submit(() -> {
                try {
                    task.execute();
                } catch (Exception e) {
                    LOGGER.error("[BlockingTaskIsolation] Blocking task failed for region #{}",
                            task.regionId(), e);
                } finally {
                    activeBlockingTasks.decrementAndGet();
                }
            });
            return;
        }

        final RegionState.ExecutionToken finalToken = token;
        blockingExecutor.submit(() -> {
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("[BlockingTaskIsolation] Blocking task failed for region #{}",
                        task.regionId(), e);
            } finally {
                // R2-03 关键修复：BlockingWorker 完成时才释放 Region execution token
                finalToken.release();
                activeBlockingTasks.decrementAndGet();
            }
        });
    }

    /**
     * @deprecated 使用 {@link #executeBlocking(RegionTask, WorkStealingCoordinator.PollResult, int)} 代替。
     */
    @Deprecated
    public void executeBlocking(@NotNull RegionTask task) {
        activeBlockingTasks.incrementAndGet();
        totalBlockingTasks.incrementAndGet();
        blockingExecutor.submit(() -> {
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("[BlockingTaskIsolation] Blocking task failed for region #{}",
                        task.regionId(), e);
            } finally {
                activeBlockingTasks.decrementAndGet();
            }
        });
    }

    public int activeBlockingTasks() { return activeBlockingTasks.get(); }
    public int totalBlockingTasks() { return totalBlockingTasks.get(); }

    /**
     * R2-14 修复：shutdown 等待所有阻塞任务完成。
     */
    public void shutdown() {
        blockingExecutor.shutdown();
        try {
            if (!blockingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[BlockingTaskIsolation] Forcing shutdown with {} active tasks",
                        activeBlockingTasks.get());
                blockingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            blockingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[BlockingTaskIsolation] Shutdown (total: {})", totalBlockingTasks.get());
    }

    @Override
    public String toString() {
        return "BlockingTaskIsolation{active=" + activeBlockingTasks.get() +
                ", total=" + totalBlockingTasks.get() + "}";
    }
}
