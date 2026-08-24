package fun.bm.mili.lmili.runtime.control;

import fun.bm.mili.lmili.runtime.DegradeReason;
import fun.bm.mili.lmili.runtime.policy.PressureState;

/**
 * 控制面板快照（/lmili control status 数据源，不可变）。
 * v1 展示核心字段；v2 追加熔断/发散/冻结等可视化字段（ARCHITECTURE_AdaptiveRuntime.md Phase 0/5/6）。
 */
public record ControlPanelSnapshot(
        long timestampNanos,
        PressureState pressure,
        double tps,
        double cpuLoad,
        int schedulerLoadPct,
        long ioQueueDepth,
        long ioP99Nanos,
        int regions,
        int activeRegions,
        int workers,
        int ioWorkers,
        boolean parallelTickOn,
        boolean oLinearOn,
        boolean entityThrottleOn,
        boolean backpressure,
        DegradeReason degrade,
        long policyVersion,
        // ---- v2（Phase 5/6）----
        String circuitBreakers,
        boolean divergenceDetected,
        boolean stateMachineLocked,
        boolean policyFrozen,
        int auditEntries,
        long budgetRejections,
        long agingPromotions
) {
}
