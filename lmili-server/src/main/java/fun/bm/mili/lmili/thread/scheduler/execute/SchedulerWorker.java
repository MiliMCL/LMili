package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 调度器 Worker 线程 —— 从 WorkStealingCoordinator 获取并执行任务。
 *
 * <h3>设计原理</h3>
 * <p>每个 worker 是一个独立的线程，循环从 coordinator 获取任务并执行。
 * 当没有任务时，使用 LockSupport.park() 阻塞等待（修复 C-12）。
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-01</b>：Region 独占执行 —— 通过 RegionState.tryBeginExecution/endExecution 保证</li>
 *   <li><b>C-08</b>：unregister 与已取出任务冲突 —— 执行完成后调用 notifyTaskCompleted</li>
 *   <li><b>C-12</b>：idle 时使用 park/unpark 替代 spin/yield/sleep</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 *  ┌─────────────────────────────────────────────────────────┐
 *  │  Worker Thread Loop                                     │
 *  │  ┌───────────────────────────────────────────────────┐  │
 *  │  │ 1. coordinator.poll(workerId)                     │  │
 *  │  │    ├── 返回 null → park() 等待 (C-12)             │  │
 *  │  │    └── 返回 task → 执行                           │  │
 *  │  │ 2. task.run()                                     │  │
 *  │  │ 3. coordinator.notifyTaskCompleted(regionId)      │  │
 *  │  │    └── RegionState.executingCount--               │  │
 *  │  │ 4. 如果 coordinator 停止 → 退出                   │  │
 *  │  └───────────────────────────────────────────────────┘  │
 *  └─────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class SchedulerWorker implements Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final int workerId;
    private final WorkStealingCoordinator coordinator;
    private final BlockingTaskIsolation blockingIsolation;

    // ---- 线程控制 ----
    private volatile Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean parked = new AtomicBoolean(false);

    // ---- 统计 ----
    private long tasksExecuted = 0;
    private long totalExecutionTimeNanos = 0;

    /**
     * 创建调度器 Worker。
     *
     * @param workerId        worker ID（0-based）
     * @param coordinator     工作窃取协调器
     * @param blockingIsolation 阻塞任务隔离器
     */
    public SchedulerWorker(int workerId,
                           WorkStealingCoordinator coordinator,
                           BlockingTaskIsolation blockingIsolation) {
        this.workerId = workerId;
        this.coordinator = coordinator;
        this.blockingIsolation = blockingIsolation;
    }

    /**
     * 启动 worker 线程。
     */
    public void start() {
        Thread thread = new Thread(this, "MiliScheduler-Worker-" + workerId);
        thread.setDaemon(true);
        this.workerThread = thread;
        thread.start();
    }

    /**
     * 停止 worker。
     */
    public void stop() {
        running.set(false);
        unpark();
    }

    /**
     * 唤醒 worker（当有新任务时调用）。
     */
    public void unpark() {
        if (parked.compareAndSet(true, false)) {
            LockSupport.unpark(workerThread);
        }
    }

    /**
     * 检查 worker 是否正在运行。
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取 worker ID。
     */
    public int workerId() {
        return workerId;
    }

    @Override
    public void run() {
        LOGGER.debug("[Worker-{}] Started", workerId);

        while (running.get()) {
            try {
                // 1. 获取下一个任务
                RegionTask task = coordinator.poll(workerId);

                if (task == null) {
                    // 没有任务，park 等待 (C-12)
                    parkLoop();
                    continue;
                }

                // 2. 执行任务
                long regionId = task.regionId();
                long startNanos = System.nanoTime();

                try {
                    // 检查是否是阻塞任务，如果是则隔离执行
                    if (task.isBlocking()) {
                        blockingIsolation.executeBlocking(task);
                    } else {
                        task.execute();
                    }
                } catch (Exception e) {
                    LOGGER.error("[Worker-{}] Error executing task for region #{}",
                            workerId, regionId, e);
                } finally {
                    // 3. 通知任务完成 —— 关键：RegionState.executingCount--
                    // 这是 C-01/C-08 修复的核心
                    coordinator.notifyTaskCompleted(regionId);
                    tasksExecuted++;
                    totalExecutionTimeNanos += System.nanoTime() - startNanos;
                }

            } catch (Exception e) {
                LOGGER.error("[Worker-{}] Unexpected error in worker loop", workerId, e);
                // 短暂休息避免错误循环
                LockSupport.parkNanos(1_000_000); // 1ms
            }
        }

        LOGGER.debug("[Worker-{}] Stopped (executed {} tasks)", workerId, tasksExecuted);
    }

    /**
     * Park 循环 —— 使用 LockSupport.park() 替代 spin/yield/sleep (C-12)。
     *
     * <p>当没有任务时，worker 线程被 park，不消耗 CPU。
     * 当有新任务提交时，调用 unpark() 唤醒。
     */
    private void parkLoop() {
        // 双重检查：park 前再次确认没有任务
        if (coordinator.totalPendingTasks() > 0) {
            return;
        }

        // Park 等待
        parked.set(true);
        // 再次检查（防止 unpark 在 parked=true 之前调用）
        if (coordinator.totalPendingTasks() > 0) {
            parked.set(false);
            return;
        }
        LockSupport.park(this);
        parked.set(false);
    }

    // ---- 统计 ----

    /**
     * 获取已执行任务数。
     */
    public long tasksExecuted() {
        return tasksExecuted;
    }

    /**
     * 获取总执行时间（纳秒）。
     */
    public long totalExecutionTimeNanos() {
        return totalExecutionTimeNanos;
    }

    /**
     * 获取平均执行时间（纳秒）。
     */
    public long averageExecutionTimeNanos() {
        return tasksExecuted > 0 ? totalExecutionTimeNanos / tasksExecuted : 0;
    }

    @Override
    public String toString() {
        return "SchedulerWorker-" + workerId +
                "{executed=" + tasksExecuted +
                ", avgTimeNs=" + averageExecutionTimeNanos() + "}";
    }
}
