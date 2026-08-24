package fun.bm.mili.lmili.runtime;

import fun.bm.mili.lmili.runtime.policy.PressureState;

/**
 * 健康快照（/lmili control status 面板数据，不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.2）。
 */
public record GlobalHealthSnapshot(
        PressureState pressure,
        double tps,
        double cpuLoad,
        int schedulerLoadPct,
        long ioQueueDepth,
        long ioP99Nanos,
        int regions,
        int activeRegions,
        int workers,
        boolean parallelTickOn,
        boolean oLinearOn,
        boolean entityThrottleOn,
        boolean backpressure,
        DegradeReason degrade,
        long timestampNanos
) {
}
