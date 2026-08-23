package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.MiliTickThread;
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
 *
 * <h3>RISK-01 修复</h3>
 * <p>Worker 在执行 task 前，调用 {@link MiliTickThread#setRegionOwnership(long, long)} 把
 * token 携带的 regionId + generation 写入当前 worker 线程。MiliTickThread.ownsRegion()
 * 在执行 region 数据访问时被检查。任何被伪造的 TickThread 身份（generation=0 或
 * ownedRegionId 不匹配）都会被 {@link MiliTickThread#verifyRegionOwnership(long)} 抛出
 * IllegalStateException，从而阻止 region 并发执行。
 *
 * <p>阻塞任务的 token 由 BlockingWorker 接管；BlockingWorker 释放时也会调用
 * clearRegionOwnership。
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

                // RISK-08/09/10/20 修复：执行前强制 ownership 三因素验证。
                //
                // 校验 token 与 task 的三因素一致：
                //   (1) token.regionId == task.regionId                  （RISK-20：handle 与 node 不匹配）
                //   (2) token.generation == regionState.generation        （RISK-12：stale token/旧任务复活）
                //   (3) token.owner == workerId                          （RISK-09：worker steal 跨 ownership）
                // 任意一项不匹配：取消 task + 释放 token，绝不执行 task。
                if (!verifyOwnership(result, task, workerId)) {
                    LOGGER.error("[Worker-{}] Ownership violation: token.regionId={} task.regionId={} "
                                    + "token.gen={} region.gen={} token.owner={} workerId={}",
                            workerId,
                            result.regionId(), task.regionId(),
                            result.generation(), result.regionGeneration(),
                            result.ownerId(), workerId);
                    try {
                        task.onCancel();
                    } catch (Throwable ignored) {
                    }
                    try {
                        result.release();
                    } catch (Throwable ignored) {
                    }
                    tasksExecuted++;
                    totalExecutionTimeNanos += System.nanoTime() - startNanos;
                    continue;
                }

                // RISK-01：取出 token 后立即把 ownership 写到当前线程。
                // blocking task 不写在这里 —— 它会通过 transferTo 由 BlockingWorker 接管 token，
                // ownership 的写入也由 BlockingWorker 负责（见 BlockingTaskIsolation）。
                boolean wroteOwnership = false;

                try {
                    if (task.isBlocking()) {
                        // R2-03 修复：将 ExecutionToken 转移给 BlockingWorker
                        // BlockingWorker 完成任务后会释放 token
                        blockingIsolation.executeBlocking(task, result, workerId);
                    } else {
                        // RISK-01：执行前先把 token ownership 写入当前 worker 线程
                        writeOwnership(result);
                        wroteOwnership = true;
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
                        // RISK-01：释放 token 后清除 ownership
                        if (wroteOwnership) {
                            clearOwnership();
                        }
                    }
                    tasksExecuted++;
                    totalExecutionTimeNanos += System.nanoTime() - startNanos;
                }

            } catch (Exception e) {
                LOGGER.error("[Worker-{}] Unexpected error in worker loop", workerId, e);
                LockSupport.parkNanos(1_000_000);
            }
        }

        // Worker 退出时确保不残留 ownership
        clearOwnership();
        LOGGER.debug("[Worker-{}] Stopped (executed {} tasks)", workerId, tasksExecuted);
    }

    /**
     * RISK-08/09/10/20 修复：执行前 ownership 三因素校验。
     *
     * <p>必须同时满足：
     * <ul>
     *   <li>token.regionId != -1（GLOBAL 不该进入 ownership 路径）</li>
     *   <li>token.regionId == task.regionId</li>
     *   <li>token.generation == regionState.generation（防 stale）</li>
     *   <li>token.owner == workerId（防 steal）</li>
     * </ul>
     *
     * <p>任意一项失败返回 false，调用者必须取消 task 并释放 token，绝不执行。</p>
     */
    private static boolean verifyOwnership(WorkStealingCoordinator.PollResult result,
                                           RegionTask task, int workerId) {
        long taskRegionId = task.regionId();
        long tokenRegionId = result.regionId();
        long tokenGen = result.generation();
        long regionGen = result.regionGeneration();
        int tokenOwner = result.ownerId();

        if (tokenRegionId == -1L) {
            return false; // GLOBAL token 不在 ownership 路径
        }
        if (tokenRegionId != taskRegionId) {
            return false; // RISK-20：handle 与 node regionId 不匹配
        }
        if (tokenGen != regionGen) {
            return false; // RISK-12：region 已重新生成，token stale
        }
        return tokenOwner == workerId; // RISK-09：worker ownership 校验
    }

    /**
     * RISK-01 修复：把 PollResult 持有的 token ownership 写入当前 worker 线程。
     *
     * <p>只有当前线程是 MiliTickThread 时才写入（否则 Folia 线程安全检查
     * 也无法将该线程识别为合法 tickThread）。非 TickThread 模式下，线程类型
     * 校验在 Folia 端不会通过，因此也不需要 ownership 写入。</p>
     */
    private void writeOwnership(WorkStealingCoordinator.PollResult result) {
        Thread self = Thread.currentThread();
        if (self instanceof MiliTickThread mtt) {
            // 通过反射 / 受控 API 从 PollResult 拿到 token 的 regionId + generation
            // 为了避免暴露 token 内部状态，PollResult 提供 getRegionId()/getGeneration()
            mtt.setRegionOwnership(result.regionId(), result.generation());
        }
    }

    private void clearOwnership() {
        Thread self = Thread.currentThread();
        if (self instanceof MiliTickThread mtt) {
            mtt.clearRegionOwnership();
        }
    }

    private void parkLoop() {
        if (coordinator.totalPendingTasks() > 0) return;
        parked.set(true);
        coordinator.markWorkerParked(workerId);
        if (coordinator.totalPendingTasks() > 0) {
            parked.set(false);
            coordinator.markWorkerUnparked(workerId);
            return;
        }
        LockSupport.park(this);
        parked.set(false);
        coordinator.markWorkerUnparked(workerId);
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
