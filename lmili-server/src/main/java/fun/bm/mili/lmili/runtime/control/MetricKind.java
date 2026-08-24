package fun.bm.mili.lmili.runtime.control;

/**
 * 指标信号种类 —— MetricsController 聚合时的输入维度（ARCHITECTURE_AdaptiveRuntime.md §3.8 适配器清单）。
 */
public enum MetricKind {
    /** TPSTracker.getTPS() → double */
    TPS,
    /** 进程 CPU%（0~1） → double */
    CPU_LOAD,
    /** IOState.queueDepth → Number */
    IO_QUEUE_DEPTH,
    /** IOState.p99LatencyNanos → Number */
    IO_P99_NANOS,
    /** Scheduler activeWorkers/carrierThreads → double(0~1) */
    SCHEDULER_LOAD,
    /** 活跃 region 数 → Number */
    ACTIVE_REGIONS,
    /** 受管 region 总数 → Number */
    TOTAL_REGIONS,
    /** Entity 节流统计（供面板，不参与压力判定） */
    ENTITY_STATS,
    /** RuntimeMetrics 快照（供面板，不参与压力判定） */
    RUNTIME_STATS
}
