package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 调度器 Worker 线程 —— 从 WorkStealingCoordinator 获取任务并执行。
 *
 * <h3>R2-01/R2-03 修复</h3>
 * <ul>
 *   <li>通过 {@link WorkStealingCoordinator.PollResult} 获取 ExecutionToken</li>
 *   <li>任务完成后调用 {@link WorkStealingCoordinator.PollResult#release()} 释放 token</li>
 *   <li>阻塞任务通过 {@link WorkStealingCoordinator.PollResult#transferTo(int)} 转移 token</li>
 * </ul>
 */
public final class SchedulerWorker implements Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int workerId;
    private final WorkStealingCoordinator coordinator;
    private final BlockingTaskIsolation blockingIsolation;

    private volatile Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean parked = new AtomicBoolean(false);

    private long tasksExecuted = 0;
    private long totalExecutionTimeNanos = 0;

    public SchedulerWorker(int workerId,
                           WorkStealingCoordinator coordinator,
                           BlockingTaskIsolation blockingIsolation) {
        this.workerId = workerId;
        this.coordinator = coordinator;
        this.blockingIsolation = blockingIsolation;
    }

    public void start() {
        Thread thread = new Thread(this, "MiliScheduler-Worker-" + workerId);
        thread.setDaemon(true);
        this.workerThread = thread;
        thread.start();
    }

    public void stop() {
        running.set(false);
        unpark();
    }

    public void unpark() {
        if (parked.compareAndSet(true, false)) {
            LockSupport.unpark(workerThread);
        }
    }

    public boolean isRunning() { return running.get(); }
    public int workerId() { return workerId; }

    @Override
    public void run() {
        LOGGER.debug("[Worker-{}] Started", workerId);

        while (running.get()) {
            try {
                // 1. 获取下一个任务（包含 ExecutionToken）
                WorkStealingCoordinator.PollResult result = coordinator.poll(workerId);

                if (result == null) {
                    parkLoop();
                    continue;
                }

                RegionTask task = result.task;
                long regionId = task.regionId();
                long startNanos = System.nanoTime();

                try {
                    if (task.isBlocking()) {
                        // R2-03 修复：将 ExecutionToken 转移给 BlockingWorker
                        // BlockingWorker 完成任务后会释放 token
                        blockingIsolation.executeBlocking(task, result, workerId);
                    } else {
                        // 普通任务：直接执行，完成后释放 token
                        task.execute();
                    }
                } catch (Exception e) {
                    LOGGER.error("[Worker-{}] Error executing task for region #{}",
                            workerId, regionId, e);
                } finally {
                    // 释放 token（如果是非阻塞任务；阻塞任务已由 BlockingWorker 释放）
                    if (!task.isBlocking()) {
                        result.release();
                    }
                    tasksExecuted++;
                    totalExecutionTimeNanos += System.nanoTime() - startNanos;
                }

            } catch (Exception e) {
                LOGGER.error("[Worker-{}] Unexpected error in worker loop", workerId, e);
                LockSupport.parkNanos(1_000_000);
            }
        }

        LOGGER.debug("[Worker-{}] Stopped (executed {} tasks)", workerId, tasksExecuted);
    }

    private void parkLoop() {
        if (coordinator.totalPendingTasks() > 0) return;
        parked.set(true);
        if (coordinator.totalPendingTasks() > 0) {
            parked.set(false);
            return;
        }
        LockSupport.park(this);
        parked.set(false);
    }

    public long tasksExecuted() { return tasksExecuted; }
    public long totalExecutionTimeNanos() { return totalExecutionTimeNanos; }
    public long averageExecutionTimeNanos() {
        return tasksExecuted > 0 ? totalExecutionTimeNanos / tasksExecuted : 0;
    }

    @Override
    public String toString() {
        return "SchedulerWorker-" + workerId +
                "{executed=" + tasksExecuted + ", avgTimeNs=" + averageExecutionTimeNanos() + "}";
    }
}
