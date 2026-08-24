package fun.bm.mili.lmili.runtime.policy;

/**
 * 压力信号 —— 状态机输入（不可变快照，由 MetricsController.collect() 组装）（ARCHITECTURE_AdaptiveRuntime.md §3.10）。
 *
 * <p>字段语义：tps/cpuLoad/ioQueueDepth/ioP99Nanos 均为 EMA 平滑值（α=0.3，§4.2）。
 */
public record PressureSignals(
        /** EMA 平滑 TPS */
        double tps,
        /** EMA 平滑进程 CPU%（0~1） */
        double cpuLoad,
        /** IOState.queueDepth（EMA 取整） */
        long ioQueueDepth,
        /** IOState.p99LatencyNanos */
        long ioP99Nanos,
        /** activeWorkers / carrierThreads（0~1） */
        double schedulerLoad,
        int activeRegions,
        int totalRegions,
        boolean shuttingDown,
        long timestampNanos
) {

    /** 中性信号 —— 全部源失败时的 fail-safe 输入（§6.2：cpu→0.5、ioQueue→0、ioP99→0、schedulerLoad→0.5） */
    public static PressureSignals neutral() {
        return new PressureSignals(20.0, 0.5, 0, 0, 0.5, 0, 0, false, System.nanoTime());
    }
}
