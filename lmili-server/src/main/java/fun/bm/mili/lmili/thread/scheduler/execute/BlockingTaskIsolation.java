package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.MiliTickThread;
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
 *
 * <h3>RISK-01 修复</h3>
 * <p>BlockingWorker 接管 token 后，会把 token 的 regionId + generation 写入
 * 当前线程的 MiliTickThread.setRegionOwnership。BlockingWorker 不是 MiliTickThread
 * （普通平台线程），因此 ownsRegion() 会返回 false —— 但这不影响功能，因为
 * BlockingWorker 不需要通过 Folia 的 TickThread 身份校验，它只是执行阻塞 IO
 * 并释放 token。token 释放时同时清除 ownership（虽然 BlockingWorker 线程没有
 * MiliTickThread 字段，但这种"双重释放"在 BlockingWorker 退出后是无害的）。
 *
 * <p>对于 region 数据的访问（例如 BlockingWorker 间接触发的 region 读写），仍然
 * 由 caller 负责在合适的 worker 线程上完成；BlockingWorker 不应直接访问 region
 * 数据，只执行阻塞 IO。</p>
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
            // RISK-01：写入 ownership（即使 BlockingWorker 不是 MiliTickThread 也不影响；
            // 平台线程访问 MiliTickThread 字段会落入 instanceof 检查为 false 的分支，
            // 自动跳过写入。这与 BlockingWorker 不需要 Folia TickThread 身份的语义一致）
            writeOwnership(finalToken);
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("[BlockingTaskIsolation] Blocking task failed for region #{}",
                        task.regionId(), e);
            } finally {
                // R2-03 关键修复：BlockingWorker 完成时才释放 Region execution token
                finalToken.release();
                clearOwnership();
                activeBlockingTasks.decrementAndGet();
            }
        });
    }

    /**
     * RISK-01：把 token ownership 写入当前 BlockingWorker 线程（若其是 MiliTickThread）。
     */
    private void writeOwnership(RegionState.ExecutionToken token) {
        Thread self = Thread.currentThread();
        if (self instanceof MiliTickThread mtt) {
            mtt.setRegionOwnership(token.regionId(), token.generation());
        }
    }

    private void clearOwnership() {
        Thread self = Thread.currentThread();
        if (self instanceof MiliTickThread mtt) {
            mtt.clearRegionOwnership();
        }
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
