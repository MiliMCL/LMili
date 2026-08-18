package fun.bm.mili.lmili.thread.scheduler.execute;

import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度器 Worker —— 从 {@link WorkStealingCoordinator} 获取任务并执行。
 *
 * <p>Worker 的职责只有：
 * <ol>
 *   <li>获取任务（从本地队列或窃取）</li>
 *   <li>执行任务</li>
 *   <li>报告结果</li>
 * </ol>
 *
 * <p>不要让 Worker 维护 Minecraft Region 状态。
 *
 * <h3>Work-Stealing 执行路径</h3>
 * <pre>
 * submit()
 *     ↓
 * WorkStealingCoordinator
 *     ↓
 * RegionQueue
 *     ↓
 * Worker (poll/steal)
 *     ↓
 * ExecutionBackend
 * </pre>
 *
 * <p>所有真正执行的任务必须经过 Scheduler（Coordinator），而不是旁路执行。
 */
public final class SchedulerWorker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerWorker.class);

    private final int workerId;
    private final WorkStealingCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 任务执行回调 —— 用于报告任务完成/失败。
     */
    @FunctionalInterface
    public interface TaskCallback {
        /**
         * 任务执行完成后的回调。
         *
         * @param task     执行的任务
         * @param throwable 异常（null 表示成功）
         */
        void onComplete(@NotNull RegionTask task, Throwable throwable);
    }

    private final TaskCallback callback;

    /**
     * 创建调度器 Worker。
     *
     * @param workerId    worker ID
     * @param coordinator 工作窃取协调器
     * @param callback    任务完成回调
     */
    public SchedulerWorker(final int workerId,
                           final @NotNull WorkStealingCoordinator coordinator,
                           final @NotNull TaskCallback callback) {
        this.workerId = workerId;
        this.coordinator = coordinator;
        this.callback = callback;
    }

    /**
     * 创建无回调的 Worker（仅记录异常）。
     *
     * @param workerId    worker ID
     * @param coordinator 工作窃取协调器
     */
    public SchedulerWorker(final int workerId,
                           final @NotNull WorkStealingCoordinator coordinator) {
        this(workerId, coordinator, (task, throwable) -> {
            if (throwable != null) {
                LOGGER.error("[SchedulerWorker-{}] Task '{}' failed", workerId, task.name(), throwable);
            }
        });
    }

    @Override
    public void run() {
        int idleSpins = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            RegionTask task = coordinator.poll(workerId);
            if (task == null) {
                // Mili start - fix: add progressive backoff instead of busy-spinning.
                // Short idle: spin wait. Medium idle: yield. Long idle: sleep briefly.
                idleSpins++;
                if (idleSpins < 100) {
                    Thread.onSpinWait();
                } else if (idleSpins < 1000) {
                    Thread.yield();
                } else {
                    try {
                        Thread.sleep(1); // 1ms sleep to avoid CPU waste during idle periods
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                continue;
                // Mili end
            }

            idleSpins = 0; // reset on successful poll
            try {
                task.execute();
                callback.onComplete(task, null);
            } catch (Throwable throwable) {
                callback.onComplete(task, throwable);
            }
        }
    }

    /**
     * 停止 Worker。
     */
    public void shutdown() {
        running.set(false);
    }

    /**
     * 获取 Worker ID。
     */
    public int workerId() {
        return workerId;
    }
}
