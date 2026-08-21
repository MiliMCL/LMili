package fun.bm.mili.lmili.thread.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.runtime.generation.TickGeneration;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一运行时 —— 整合 DeadlineScheduler、WorkStealingDeque 和 AdaptiveSlicer。
 *
 * <p>P1 核心调度器，包含：
 * <ul>
 *   <li>{@link DeadlineScheduler} —— 无漂移 Tick 调度</li>
 *   <li>{@link SchedulerWorker}[] —— 工作窃取 Worker 池</li>
 *   <li>{@link WorkStealingDeque} —— Worker 本地队列</li>
 *   <li>{@link WorkerUtilization}[] —— Worker 利用率追踪</li>
 *   <li>{@link AdaptiveSlicer} —— 动态 slice 大小调整</li>
 *   <li>{@link SliceCostCalculator} —— 区域成本预测</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>任意线程可以提交任务</li>
 *   <li>Worker 线程执行任务</li>
 *   <li>Tick 调度器线程触发 Tick</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>创建 {@link #UnifiedRuntime(int)}</li>
 *   <li>提交 {@link #submit(RuntimeTask)}</li>
 *   <li>关闭 {@link #shutdown()}</li>
 * </ol>
 */
public final class UnifiedRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Worker 数量 */
    private final int workerCount;

    /** 核心组件 */
    private final DeadlineScheduler deadlineScheduler;
    private final AdaptiveSlicer adaptiveSlicer;
    private final SliceCostCalculator costCalculator;

    /** Worker 池 */
    private final SchedulerWorker[] workers;
    private final Thread[] workerThreads;

    /** Region 成本预测器 */
    private final ConcurrentHashMap<Long, SliceCostCalculator.RegionCostPredictor> regionPredictors = new ConcurrentHashMap<>();

    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean[] parkedWorkers;

    /** 全局统计 */
    private final AtomicLong totalTasksSubmitted = new AtomicLong(0);
    private final AtomicLong totalTasksCompleted = new AtomicLong(0);
    private final AtomicLong totalSteals = new AtomicLong(0);
    private final AtomicLong totalLateCompletions = new AtomicLong(0);

    /**
     * 创建统一运行时。
     *
     * @param workerCount Worker 数量
     */
    public UnifiedRuntime(int workerCount) {
        this.workerCount = Math.max(1, workerCount);
        this.deadlineScheduler = new DeadlineScheduler();
        this.adaptiveSlicer = new AdaptiveSlicer();
        this.costCalculator = new SliceCostCalculator();

        // 创建 Worker 池
        this.workers = new SchedulerWorker[this.workerCount];
        this.workerThreads = new Thread[this.workerCount];
        this.parkedWorkers = new AtomicBoolean[this.workerCount];

        for (int i = 0; i < this.workerCount; i++) {
            this.workers[i] = new SchedulerWorker(i, this);
            this.parkedWorkers[i] = new AtomicBoolean(false);
        }

        // 启动 Worker 线程
        for (int i = 0; i < this.workerCount; i++) {
            this.workerThreads[i] = this.workers[i].workerThread;
            this.workers[i].start();
        }

        LOGGER.info("[UnifiedRuntime] Started {} workers", this.workerCount);
    }

    /**
     * 提交任务到运行时。
     *
     * <p>任务会根据其 regionId 被分发到最空闲的 Worker。
     *
     * @param task 要执行的任务
     */
    public void submit(RuntimeTask task) {
        if (!running.get()) {
            task.cancel();
            return;
        }

        // 选择最空闲的 Worker（简单负载均衡）
        SchedulerWorker target = selectWorker(task);
        target.submitLocal(task);
        totalTasksSubmitted.incrementAndGet();
    }

    /**
     * 选择目标 Worker。
     *
     * <p>策略：选择队列深度最小的 Worker。
     */
    private SchedulerWorker selectWorker(RuntimeTask task) {
        // 如果有 regionId，尝试保持同一 region 的任务在同一 Worker（缓存局部性）
        if (task.regionId() >= 0) {
            int preferredWorker = (int) (task.regionId() % workerCount);
            if (workers[preferredWorker].isRunning()) {
                return workers[preferredWorker];
            }
        }

        // 选择队列深度最小的 Worker
        SchedulerWorker best = workers[0];
        int bestDepth = best.localQueue().size();

        for (int i = 1; i < workers.length; i++) {
            int depth = workers[i].localQueue().size();
            if (depth < bestDepth) {
                best = workers[i];
                bestDepth = depth;
            }
        }

        return best;
    }

    /**
     * 获取下一个 Tick deadline（防止漂移）。
     *
     * @return 下一个 deadline（纳秒）
     */
    public long nextTickDeadline() {
        return deadlineScheduler.nextDeadline();
    }

    /**
     * 记录一次 Tick 完成。
     *
     * @param executionTimeNanos 本次执行耗时
     */
    public void recordTickCompletion(long executionTimeNanos) {
        deadlineScheduler.recordTickCompletion(executionTimeNanos);
        totalTasksCompleted.incrementAndGet();
    }

    /**
     * 获取或创建 Region 的成本预测器。
     */
    public SliceCostCalculator.RegionCostPredictor getOrCreatePredictor(long regionId) {
        return regionPredictors.computeIfAbsent(regionId,
                id -> new SliceCostCalculator.RegionCostPredictor());
    }

    /**
     * 计算 slice 划分（使用 Adaptive Slicer）。
     *
     * @param regionId     Region ID
     * @param totalChunks  总 chunk 数
     * @return slice 计划
     */
    public AdaptiveSlicer.SlicePlan calculateSlicePlan(long regionId, int totalChunks) {
        SliceCostCalculator.RegionCostPredictor predictor = getOrCreatePredictor(regionId);
        double predictedTimePerChunk = predictor.getPredictedCost() / Math.max(1, totalChunks);
        return adaptiveSlicer.calculateSlices(totalChunks, predictedTimePerChunk);
    }

    /**
     * 记录一次 slice 的实际执行时间。
     *
     * @param regionId       Region ID
     * @param chunksInSlice 本次 slice 包含的 chunk 数
     * @param executionNanos 实际执行时间（纳秒）
     */
    public void recordSliceExecution(long regionId, int chunksInSlice, long executionNanos) {
        double executionMillis = executionNanos / 1_000_000.0;
        SliceCostCalculator.RegionCostPredictor predictor = getOrCreatePredictor(regionId);
        predictor.recordActualCost(executionMillis);
    }

    // ---- Worker 状态管理 ----

    public SchedulerWorker[] getWorkers() {
        return workers;
    }

    public void markWorkerParked(int workerId) {
        if (workerId >= 0 && workerId < workerCount) {
            parkedWorkers[workerId].set(true);
        }
    }

    public void markWorkerUnparked(int workerId) {
        if (workerId >= 0 && workerId < workerCount) {
            parkedWorkers[workerId].set(false);
        }
    }

    public boolean hasPendingTasks() {
        for (SchedulerWorker worker : workers) {
            if (!worker.localQueue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 唤醒一个 parked 的 Worker。
     */
    public void unparkOneWorker() {
        for (int i = 0; i < workerCount; i++) {
            if (parkedWorkers[i].get()) {
                workers[i].unpark();
                return;
            }
        }
    }

    // ---- 指标查询 ----

    public DeadlineScheduler getDeadlineScheduler() {
        return deadlineScheduler;
    }

    public AdaptiveSlicer getAdaptiveSlicer() {
        return adaptiveSlicer;
    }

    public SliceCostCalculator getCostCalculator() {
        return costCalculator;
    }

    /**
     * 获取所有 Worker 的利用率列表。
     */
    public List<WorkerUtilization.Snapshot> getWorkerUtilizations() {
        List<WorkerUtilization.Snapshot> result = new ArrayList<>(workerCount);
        for (SchedulerWorker worker : workers) {
            result.add(worker.utilization().snapshot());
        }
        return result;
    }

    /**
     * 获取全局平均利用率。
     */
    public double getAverageUtilization() {
        double sum = 0;
        for (SchedulerWorker worker : workers) {
            sum += worker.utilization().getUtilization();
        }
        return workerCount > 0 ? sum / workerCount : 0;
    }

    /**
     * 获取总窃取次数。
     */
    public long getTotalSteals() {
        long sum = 0;
        for (SchedulerWorker worker : workers) {
            sum += worker.utilization().getStealSuccessCount();
        }
        return sum;
    }

    /**
     * 获取总窃取失败次数。
     */
    public long getTotalStealFailures() {
        long sum = 0;
        for (SchedulerWorker worker : workers) {
            sum += worker.utilization().getStealFailureCount();
        }
        return sum;
    }

    /**
     * 获取快照。
     */
    public RuntimeSnapshot getSnapshot() {
        return new RuntimeSnapshot(
                deadlineScheduler.getTickCount(),
                totalTasksSubmitted.get(),
                totalTasksCompleted.get(),
                getTotalSteals(),
                getTotalStealFailures(),
                getAverageUtilization(),
                deadlineScheduler.getActualTPS(),
                hasPendingTasks()
        );
    }

    /**
     * 关闭运行时。
     */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOGGER.info("[UnifiedRuntime] Shutting down...");

        // 停止所有 Worker
        for (SchedulerWorker worker : workers) {
            worker.stop();
        }

        // 等待 Worker 线程退出
        for (SchedulerWorker worker : workers) {
            try {
                workerThreads[worker.workerId()].join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.info("[UnifiedRuntime] Shutdown complete (ticks={}, tasks={}, steals={}, avgUtil={:.1f}%)",
                deadlineScheduler.getTickCount(),
                totalTasksCompleted.get(),
                getTotalSteals(),
                getAverageUtilization() * 100);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 运行时快照。
     */
    public record RuntimeSnapshot(
            long tickCount,
            long tasksSubmitted,
            long tasksCompleted,
            long totalSteals,
            long totalStealFailures,
            double averageUtilization,
            double actualTPS,
            boolean hasPendingTasks
    ) {
        public double stealSuccessRate() {
            long total = totalSteals + totalStealFailures;
            return total > 0 ? (double) totalSteals / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("Runtime{ticks=%d, tasks=%d/%d, steals=%d/%d (%.1f%%), util=%.1f%%, tps=%.2f}",
                    tickCount,
                    tasksCompleted,
                    tasksSubmitted,
                    totalSteals,
                    totalSteals + totalStealFailures,
                    stealSuccessRate() * 100,
                    averageUtilization * 100,
                    actualTPS);
        }
    }
}
