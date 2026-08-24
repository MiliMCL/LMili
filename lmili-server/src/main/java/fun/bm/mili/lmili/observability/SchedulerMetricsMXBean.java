package fun.bm.mili.lmili.observability;

import fun.bm.mili.lmili.api.observability.SchedulerMetrics;

/**
 * LMili SchedulerMetrics JMX MXBean 接口（§11 / §15 / Spark integration）。
 *
 * <p>ObjectName：{@code fun.bm.mili:type=SchedulerMetrics}（由 {@link fun.bm.mili.lmili.api.LMili#jmxObjectName()} 返回）。
 * 外部 JMX 客户端（Spark 通过 {@code jmx connect}、VisualVM、jconsole）可用此接口直接读取 §15 全部指标。
 */
public interface SchedulerMetricsMXBean extends SchedulerMetrics {

    // ---- explicit getter signatures (JMX 必须有 getXxx 方法) ----
    long getTotalTicks();
    double getMsptMeanMs();
    double getMsptMedianMs();
    double getMsptP95Ms();
    double getMsptP99Ms();
    double getMsptMaxMs();
    double getCurrentTps();

    int getWorkerCount();
    int getActiveWorkerCount();
    int getIdleWorkerCount();
    long getTotalSteals();
    long getTotalStealFailures();
    double getAverageWorkerUtilization();

    long getTasksSubmitted();
    long getTasksCompleted();
    long getTasksFailed();
    long getTasksCancelled();
    long getTasksLateCompleted();

    long getBlockedRegionCount();
    long getDeferredQueueDepth();
    long getChunkWaitCount();
    long getChunkWaitNanosTotal();
    long getPoiWaitCount();
    long getSoftBudgetTrips();
    long getHardBudgetTrips();

    long getCrossRegionAccessCount();
    long getPluginTaskCount();
}
