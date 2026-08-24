package fun.bm.mili.lmili.runtime.policy;

/**
 * 压力状态（全局；SHUTDOWN 为终态）（ARCHITECTURE_AdaptiveRuntime.md §3.10）。
 */
public enum PressureState {
    /** 健康：正常调度、正常 flush、正常并行 */
    NORMAL,
    /** TPS&lt;18 且 CPU&gt;85%：削减低优先级 Entity/background，限制 fan-out */
    CPU_PRESSURE,
    /** IO 拥塞：降低 background flush、提高关键 flush、暂缓非关键压缩 */
    IO_PRESSURE,
    /** 手动降级（/lmili control degrade）或 fail-safe 观察态 */
    DEGRADED,
    /** 关闭编排中（终态，不退出） */
    SHUTDOWN
}
