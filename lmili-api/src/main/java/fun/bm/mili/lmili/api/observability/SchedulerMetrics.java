package fun.bm.mili.lmili.api.observability;

import org.jetbrains.annotations.NotNull;

/**
 * LMili 调度器公开指标（面向插件 / Spark / VisualVM 等外部观察者）。
 *
 * <p>§15 LMili_Next_Step_Optimization.md 要求所有关键调度指标对外可观测。
 * 本接口由 LMili 内部实现，注册到 JMX（{@code fun.bm.mili:type=SchedulerMetrics}），
 * 任何 JMX 客户端（含 Spark profile UI、VisualVM、jconsole）都能查到本接口的所有字段。
 *
 * <h3>为什么需要独立接口（而不是直接暴露 RuntimeMetrics）</h3>
 * <ul>
 *   <li>RuntimeMetrics 是 server-side 模块内 POJO，与 lmili-server 强耦合</li>
 *   <li>lmili-api 不能反向依赖 lmili-server（避免循环依赖）</li>
 *   <li>本接口是稳定契约，向后兼容；RuntimeMetrics 可以随便改内部实现</li>
 * </ul>
 *
 * <h3>典型调用</h3>
 * <pre>{@code
 * SchedulerMetrics m = LMili.schedulerMetrics();
 * long p99Ms = m.msptP99Ms();
 * long chunkWaits = m.chunkWaitCount();
 * }</pre>
 *
 * @see fun.bm.mili.lmili.api.LMili#schedulerMetrics()
 */
public interface SchedulerMetrics {

    // ---- §5 上报 / §15 baseline 必含 ----

    long totalTicks();
    double msptMeanMs();
    double msptMedianMs();
    double msptP95Ms();
    double msptP99Ms();
    double msptMaxMs();
    double currentTps();

    // ---- Worker / 调度 ----

    int workerCount();
    int activeWorkerCount();
    int idleWorkerCount();
    long totalSteals();
    long totalStealFailures();
    double averageWorkerUtilization();

    // ---- 任务 ----

    long tasksSubmitted();
    long tasksCompleted();
    long tasksFailed();
    long tasksCancelled();
    long tasksLateCompleted();

    // ---- 长尾 / 阻塞 ----

    long blockedRegionCount();
    long deferredQueueDepth();
    long chunkWaitCount();
    long chunkWaitNanosTotal();
    long poiWaitCount();
    long softBudgetTrips();
    long hardBudgetTrips();

    // ---- 跨 Region ----

    long crossRegionAccessCount();
    long pluginTaskCount();

    /**
     * 完整快照（不可变 record）。
     */
    @NotNull
    Snapshot snapshot();

    /**
     * 不可变快照 record —— 跨 LMili 内部版本兼容。
     */
    record Snapshot(
            long totalTicks,
            double msptMeanMs,
            double msptMedianMs,
            double msptP95Ms,
            double msptP99Ms,
            double msptMaxMs,
            double currentTps,
            int workerCount,
            int activeWorkerCount,
            int idleWorkerCount,
            long totalSteals,
            long totalStealFailures,
            double averageWorkerUtilization,
            long tasksSubmitted,
            long tasksCompleted,
            long tasksFailed,
            long tasksCancelled,
            long tasksLateCompleted,
            long blockedRegionCount,
            long deferredQueueDepth,
            long chunkWaitCount,
            long chunkWaitNanosTotal,
            long poiWaitCount,
            long softBudgetTrips,
            long hardBudgetTrips,
            long crossRegionAccessCount,
            long pluginTaskCount
    ) {
        public double stealSuccessRate() {
            long total = totalSteals + totalStealFailures;
            return total > 0 ? (double) totalSteals / total : 0.0;
        }
    }
}
