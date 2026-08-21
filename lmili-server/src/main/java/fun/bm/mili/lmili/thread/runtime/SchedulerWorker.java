package fun.bm.mili.lmili.thread.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 调度器 Worker 线程 —— 从本地 WorkStealingDeque 获取任务并执行。
 *
 * <p>P1 增强：
 * <ul>
 *   <li>使用 {@link WorkStealingDeque} 作为本地队列</li>
 *   <li>使用 {@link WorkerUtilization} 追踪利用率指标</li>
 *   <li>LIFO 本地执行 + FIFO 窃取</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>Worker 线程从本地队列 pop 任务执行</li>
 *   <li>其他 Worker 从本地队列 steal 任务</li>
 * </ul>
 */
public final class SchedulerWorker implements Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int workerId;
    private final WorkStealingDeque<RuntimeTask> localQueue;
    private final WorkerUtilization utilization;
    private final UnifiedRuntime runtime;

    private volatile Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean parked = new AtomicBoolean(false);

    /** 最后一次窃取的时间（纳秒） */
    private long lastStealTimeNanos = 0;

    /** 最后一次窃取来源 */
    private int lastStealFromWorkerId = -1;

    public SchedulerWorker(int workerId, UnifiedRuntime runtime) {
        this.workerId = workerId;
        this.runtime = runtime;
        this.localQueue = new WorkStealingDeque<>(workerId);
        this.utilization = new WorkerUtilization(workerId);
    }

    public void start() {
        Thread thread = new Thread(this, "MiliRuntime-Worker-" + workerId);
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
    public WorkStealingDeque<RuntimeTask> localQueue() { return localQueue; }
    public WorkerUtilization utilization() { return utilization; }

    @Override
    public void run() {
        LOGGER.debug("[Worker-{}] Started", workerId);

        while (running.get()) {
            try {
                // 1. 尝试从本地队列获取任务
                RuntimeTask task = localQueue.pop();

                if (task == null) {
                    // 2. 本地队列为空，尝试窃取
                    task = tryStealFromOtherWorkers();
                }

                if (task == null) {
                    // 3. 没有任务，进入 park
                    parkLoop();
                    continue;
                }

                // 4. 执行任务
                executeTask(task);

            } catch (Exception e) {
                LOGGER.error("[Worker-{}] Unexpected error in worker loop", workerId, e);
                LockSupport.parkNanos(1_000_000);
            }
        }

        LOGGER.debug("[Worker-{}] Stopped (executed {} tasks)", workerId, utilization.getTasksExecuted());
    }

    /**
     * 执行任务并记录指标。
     */
    private void executeTask(RuntimeTask task) {
        long startNanos = System.nanoTime();
        try {
            task.execute();
            long elapsed = System.nanoTime() - startNanos;
            utilization.recordTaskExecution(elapsed);
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startNanos;
            utilization.recordTaskExecution(elapsed);
            LOGGER.error("[Worker-{}] Error executing task {}", workerId, task.taskId(), e);
        }
    }

    /**
     * 尝试从其他 Worker 窃取任务。
     */
    private RuntimeTask tryStealFromOtherWorkers() {
        SchedulerWorker[] workers = runtime.getWorkers();
        if (workers == null || workers.length <= 1) return null;

        // 随机选择窃取目标（避免所有 Worker 同时窃取同一目标）
        int startIndex = (int) (System.nanoTime() % workers.length);

        for (int i = 0; i < workers.length; i++) {
            int targetIndex = (startIndex + i) % workers.length;
            SchedulerWorker target = workers[targetIndex];

            // 跳过自己和未运行的 Worker
            if (target == this || !target.isRunning()) continue;

            RuntimeTask stolen = target.localQueue.steal();
            if (stolen != null) {
                utilization.recordStealSuccess(target.workerId);
                lastStealTimeNanos = System.nanoTime();
                lastStealFromWorkerId = target.workerId;
                return stolen;
            } else {
                utilization.recordStealFailure();
            }
        }

        return null;
    }

    private void parkLoop() {
        long parkStart = System.nanoTime();

        // 先检查是否有新任务提交
        if (runtime.hasPendingTasks()) {
            return;
        }

        parked.set(true);
        runtime.markWorkerParked(workerId);

        // 双重检查
        if (runtime.hasPendingTasks()) {
            parked.set(false);
            runtime.markWorkerUnparked(workerId);
            return;
        }

        LockSupport.park(this);

        parked.set(false);
        runtime.markWorkerUnparked(workerId);

        // 记录空闲时间
        long idleNanos = System.nanoTime() - parkStart;
        if (idleNanos > 0) {
            utilization.recordIdle(idleNanos);
        }
    }

    /**
     * 提交任务到本地队列。
     */
    public void submitLocal(RuntimeTask task) {
        localQueue.push(task);
        utilization.updateQueueDepth(localQueue.size());
        unpark();
    }

    /**
     * 提交任务到指定 Worker 的队列。
     */
    public static void submitToWorker(RuntimeTask task, SchedulerWorker worker) {
        worker.submitLocal(task);
    }

    @Override
    public String toString() {
        return "SchedulerWorker-" + workerId +
                "{util=" + String.format("%.1f%%", utilization.getUtilization() * 100) +
                ", tasks=" + utilization.getTasksExecuted() +
                ", queueDepth=" + localQueue.size() + "}";
    }
}
