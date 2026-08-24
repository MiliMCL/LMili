package fun.bm.mili.lmili.observability;

import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.observability.SchedulerMetrics;

/**
 * LMili JMX MXBean —— Spark / VisualVM / jconsole 通过 JMX 读取 §15 全部指标。
 *
 * <p><b>为什么必须注册 JMX（解决"Spark 看不到 LMili 调度"问题）</b>：
 * <ul>
 *   <li>paper 内置 spark 与 Folia 多线程模型不兼容（已被 LMili 强制禁用）。</li>
 *   <li>外部 spark-paper plugin 的 {@code /spark sampler} 默认只能采集线程堆栈，
 *       不会主动识别 "MiliScheduler-Worker-N" 这类 LMili 自定义线程组。</li>
 *   <li>LMili 的内部调度次数（region 调度 / chunk wait / plugin task）通过 JMX MXBean
 *       暴露，Spark 通过 {@code jmx connect}、VisualVM / jconsole 通过
 *       {@code fun.bm.mili:type=SchedulerMetrics} ObjectName 直接读，无需 spark 集成。</li>
 *   <li>同时为 {@link fun.bm.mili.lmili.api.LMili#schedulerMetrics()} 提供服务 —— 插件作者
 *       可在代码里 {@code LMili.schedulerMetrics().msptP99Ms()} 直接拿。</li>
 * </ul>
 *
 * <p>注册时机：MiliRuntime 启动后（fail-safe；注册失败仅日志，不阻断）。
 */
public final class JmxSchedulerMetricsMXBean implements SchedulerMetrics, SchedulerMetricsMXBean {

    /** Source of truth —— LMili 内部 RuntimeMetrics。 */
    private volatile SchedulerMetrics source;

    public JmxSchedulerMetricsMXBean() {}

    public JmxSchedulerMetricsMXBean(SchedulerMetrics initial) {
        this.source = initial;
    }

    /** 服务端注入（runtime 启动时） */
    public void install(SchedulerMetrics src) {
        this.source = src;
        // 同时挂到 LMili 公开 API —— 插件可通过 LMili.schedulerMetrics() 直接拿
        LMili.installSchedulerMetrics(src);
    }

    @Override
    public long getTotalTicks() { return n(source, SchedulerMetrics::totalTicks); }
    @Override
    public double getMsptMeanMs() { return n(source, SchedulerMetrics::msptMeanMs); }
    @Override
    public double getMsptMedianMs() { return n(source, SchedulerMetrics::msptMedianMs); }
    @Override
    public double getMsptP95Ms() { return n(source, SchedulerMetrics::msptP95Ms); }
    @Override
    public double getMsptP99Ms() { return n(source, SchedulerMetrics::msptP99Ms); }
    @Override
    public double getMsptMaxMs() { return n(source, SchedulerMetrics::msptMaxMs); }
    @Override
    public double getCurrentTps() { return n(source, SchedulerMetrics::currentTps); }

    @Override
    public int getWorkerCount() { return n(source, SchedulerMetrics::workerCount); }
    @Override
    public int getActiveWorkerCount() { return n(source, SchedulerMetrics::activeWorkerCount); }
    @Override
    public int getIdleWorkerCount() { return n(source, SchedulerMetrics::idleWorkerCount); }
    @Override
    public long getTotalSteals() { return n(source, SchedulerMetrics::totalSteals); }
    @Override
    public long getTotalStealFailures() { return n(source, SchedulerMetrics::totalStealFailures); }
    @Override
    public double getAverageWorkerUtilization() { return n(source, SchedulerMetrics::averageWorkerUtilization); }

    @Override
    public long getTasksSubmitted() { return n(source, SchedulerMetrics::tasksSubmitted); }
    @Override
    public long getTasksCompleted() { return n(source, SchedulerMetrics::tasksCompleted); }
    @Override
    public long getTasksFailed() { return n(source, SchedulerMetrics::tasksFailed); }
    @Override
    public long getTasksCancelled() { return n(source, SchedulerMetrics::tasksCancelled); }
    @Override
    public long getTasksLateCompleted() { return n(source, SchedulerMetrics::tasksLateCompleted); }

    @Override
    public long getBlockedRegionCount() { return n(source, SchedulerMetrics::blockedRegionCount); }
    @Override
    public long getDeferredQueueDepth() { return n(source, SchedulerMetrics::deferredQueueDepth); }
    @Override
    public long getChunkWaitCount() { return n(source, SchedulerMetrics::chunkWaitCount); }
    @Override
    public long getChunkWaitNanosTotal() { return n(source, SchedulerMetrics::chunkWaitNanosTotal); }
    @Override
    public long getPoiWaitCount() { return n(source, SchedulerMetrics::poiWaitCount); }
    @Override
    public long getSoftBudgetTrips() { return n(source, SchedulerMetrics::softBudgetTrips); }
    @Override
    public long getHardBudgetTrips() { return n(source, SchedulerMetrics::hardBudgetTrips); }

    @Override
    public long getCrossRegionAccessCount() { return n(source, SchedulerMetrics::crossRegionAccessCount); }
    @Override
    public long getPluginTaskCount() { return n(source, SchedulerMetrics::pluginTaskCount); }
    @Override
    public long pluginTaskCount() { return getPluginTaskCount(); }

    @Override
    public SchedulerMetrics.Snapshot snapshot() {
        final SchedulerMetrics s = source;
        return s != null ? s.snapshot() : emptySnapshot();
    }

    // ---- SchedulerMetrics (no-get prefix) forwards ----
    @Override public long totalTicks() { return getTotalTicks(); }
    @Override public double msptMeanMs() { return getMsptMeanMs(); }
    @Override public double msptMedianMs() { return getMsptMedianMs(); }
    @Override public double msptP95Ms() { return getMsptP95Ms(); }
    @Override public double msptP99Ms() { return getMsptP99Ms(); }
    @Override public double msptMaxMs() { return getMsptMaxMs(); }
    @Override public double currentTps() { return getCurrentTps(); }
    @Override public int workerCount() { return getWorkerCount(); }
    @Override public int activeWorkerCount() { return getActiveWorkerCount(); }
    @Override public int idleWorkerCount() { return getIdleWorkerCount(); }
    @Override public long totalSteals() { return getTotalSteals(); }
    @Override public long totalStealFailures() { return getTotalStealFailures(); }
    @Override public double averageWorkerUtilization() { return getAverageWorkerUtilization(); }
    @Override public long tasksSubmitted() { return getTasksSubmitted(); }
    @Override public long tasksCompleted() { return getTasksCompleted(); }
    @Override public long tasksFailed() { return getTasksFailed(); }
    @Override public long tasksCancelled() { return getTasksCancelled(); }
    @Override public long tasksLateCompleted() { return getTasksLateCompleted(); }
    @Override public long blockedRegionCount() { return getBlockedRegionCount(); }
    @Override public long deferredQueueDepth() { return getDeferredQueueDepth(); }
    @Override public long chunkWaitCount() { return getChunkWaitCount(); }
    @Override public long chunkWaitNanosTotal() { return getChunkWaitNanosTotal(); }
    @Override public long poiWaitCount() { return getPoiWaitCount(); }
    @Override public long softBudgetTrips() { return getSoftBudgetTrips(); }
    @Override public long hardBudgetTrips() { return getHardBudgetTrips(); }
    @Override public long crossRegionAccessCount() { return getCrossRegionAccessCount(); }

    private static SchedulerMetrics.Snapshot emptySnapshot() {
        return new SchedulerMetrics.Snapshot(
                0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0, 0L, 0L, 0.0,
                0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L
        );
    }

    private static <T> T n(SchedulerMetrics src, java.util.function.ToLongFunction<SchedulerMetrics> f) {
        if (src == null) return (T) Long.valueOf(0);
        try {
            return (T) Long.valueOf(f.applyAsLong(src));
        } catch (Throwable ignored) {
            return (T) Long.valueOf(0);
        }
    }

    private static <T> T n(SchedulerMetrics src, java.util.function.ToDoubleFunction<SchedulerMetrics> f) {
        if (src == null) return (T) Double.valueOf(0);
        try {
            return (T) Double.valueOf(f.applyAsDouble(src));
        } catch (Throwable ignored) {
            return (T) Double.valueOf(0);
        }
    }

    private static <T> T n(SchedulerMetrics src, java.util.function.ToIntFunction<SchedulerMetrics> f) {
        if (src == null) return (T) Integer.valueOf(0);
        try {
            return (T) Integer.valueOf(f.applyAsInt(src));
        } catch (Throwable ignored) {
            return (T) Integer.valueOf(0);
        }
    }
}
