package fun.bm.mili.lmili.observability;

import fun.bm.mili.lmili.api.observability.SchedulerMetrics;
import fun.bm.mili.lmili.thread.runtime.metrics.RuntimeMetrics;

/**
 * 把内部 {@link RuntimeMetrics} 适配成 plugin API 的 {@link SchedulerMetrics}。
 *
 * <p>注意：RuntimeMetrics 不直接持有 worker/active/idle 概念 —— 这些字段由
 * UnifiedRuntime 提供。本 adapter 在 runtime 未装配时返回 0。
 */
public final class RuntimeMetricsAdapter implements SchedulerMetrics {

    private final RuntimeMetrics metrics;
    private final WorkerCountersProvider workers;

    public RuntimeMetricsAdapter(RuntimeMetrics metrics) {
        this(metrics, () -> new WorkerSnapshot(0, 0, 0, 0L, 0L, 0.0));
    }

    public RuntimeMetricsAdapter(RuntimeMetrics metrics, WorkerCountersProvider workers) {
        this.metrics = metrics;
        this.workers = workers != null ? workers : () -> new WorkerSnapshot(0, 0, 0, 0L, 0L, 0.0);
    }

    // ---- §15 baseline 必含 ----
    @Override public long totalTicks() { return safe(metrics, RuntimeMetrics::getTotalTicks); }
    @Override public double msptMeanMs() { return safe(metrics, RuntimeMetrics::getAverageTickTimeMs); }
    @Override public double msptMedianMs() { return safe(metrics, RuntimeMetrics::getP50); }
    @Override public double msptP95Ms() { return safe(metrics, RuntimeMetrics::getP95); }
    @Override public double msptP99Ms() { return safe(metrics, RuntimeMetrics::getP99); }
    @Override public double msptMaxMs() { return safe(metrics, RuntimeMetrics::getMaxTickTimeMs); }
    @Override public double currentTps() { return safe(metrics, RuntimeMetrics::getCurrentTPS); }

    @Override public int workerCount() { return workers.get().total; }
    @Override public int activeWorkerCount() { return workers.get().active; }
    @Override public int idleWorkerCount() { return workers.get().idle; }
    @Override public long totalSteals() { return workers.get().stealSuccess; }
    @Override public long totalStealFailures() { return workers.get().stealFailure; }
    @Override public double averageWorkerUtilization() { return workers.get().avgUtil; }

    @Override public long tasksSubmitted() { return safe(metrics, RuntimeMetrics::getTasksSubmitted); }
    @Override public long tasksCompleted() { return safe(metrics, RuntimeMetrics::getTasksCompleted); }
    @Override public long tasksFailed() { return safe(metrics, RuntimeMetrics::getTasksFailed); }
    @Override public long tasksCancelled() { return safe(metrics, RuntimeMetrics::getCancellationCount); }
    @Override public long tasksLateCompleted() { return safe(metrics, RuntimeMetrics::getLateCompletionCount); }

    @Override public long blockedRegionCount() { return safe(metrics, RuntimeMetrics::getBlockedRegionCount); }
    @Override public long deferredQueueDepth() { return safe(metrics, RuntimeMetrics::getDeferredQueueDepth); }
    @Override public long chunkWaitCount() { return safe(metrics, RuntimeMetrics::getChunkWaitCount); }
    @Override public long chunkWaitNanosTotal() { return safe(metrics, RuntimeMetrics::getChunkWaitNanosTotal); }
    @Override public long poiWaitCount() { return safe(metrics, RuntimeMetrics::getPoiWaitCount); }
    @Override public long softBudgetTrips() { return safe(metrics, RuntimeMetrics::getSoftBudgetTrips); }
    @Override public long hardBudgetTrips() { return safe(metrics, RuntimeMetrics::getHardBudgetTrips); }

    @Override public long crossRegionAccessCount() { return safe(metrics, RuntimeMetrics::getCrossRegionAccessCount); }
    @Override public long pluginTaskCount() {
        // RuntimeMetrics 当前未单独维护 plugin task count；plugins 计数由 PluginUsageCollector 提供
        // 此处返回 0 —— §18.1/§18.9 不要求新增字段时优先复用现有指标
        return 0L;
    }

    @Override
    public Snapshot snapshot() {
        WorkerSnapshot ws = workers.get();
        return new Snapshot(
                totalTicks(),
                msptMeanMs(), msptMedianMs(), msptP95Ms(), msptP99Ms(), msptMaxMs(),
                currentTps(),
                ws.total, ws.active, ws.idle,
                ws.stealSuccess, ws.stealFailure, ws.avgUtil,
                tasksSubmitted(), tasksCompleted(), tasksFailed(), tasksCancelled(), tasksLateCompleted(),
                blockedRegionCount(), deferredQueueDepth(),
                chunkWaitCount(), chunkWaitNanosTotal(), poiWaitCount(),
                softBudgetTrips(), hardBudgetTrips(),
                crossRegionAccessCount(), pluginTaskCount()
        );
    }

    // ---- helpers ----
    private static long safe(RuntimeMetrics m, java.util.function.ToLongFunction<RuntimeMetrics> f) {
        if (m == null) return 0;
        try { return f.applyAsLong(m); } catch (Throwable t) { return 0; }
    }
    private static double safe(RuntimeMetrics m, java.util.function.ToDoubleFunction<RuntimeMetrics> f) {
        if (m == null) return 0;
        try { return f.applyAsDouble(m); } catch (Throwable t) { return 0; }
    }

    /** Worker count provider —— 运行时由 UnifiedRuntime 注入 */
    @FunctionalInterface
    public interface WorkerCountersProvider {
        WorkerSnapshot get();
    }

    public record WorkerSnapshot(int total, int active, int idle,
                                 long stealSuccess, long stealFailure,
                                 double avgUtil) {}
}
