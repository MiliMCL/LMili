package fun.bm.mili.lmili.runtime.policy;

/**
 * 阈值集（不可变；经 PolicyController 整体替换，volatile 引用原子发布）（ARCHITECTURE_AdaptiveRuntime.md §3.10 / §4.2）。
 */
public record PressureThresholds(
        double enterCpuTps,
        double enterCpuLoad,
        double exitCpuTps,
        double exitCpuLoad,
        long enterIoQueue,
        long enterIoP99Nanos,
        long exitIoQueue,
        long exitIoP99Nanos,
        int enterCycles,
        int exitCycles,
        long minDwellMillis,
        int maxTransitionsPerMinute,
        long failSafeStaleNanos
) {

    public static final PressureThresholds DEFAULTS = new PressureThresholds(
            18.0,
            0.85,
            19.5,
            0.70,
            2000,
            80_000_000L,   // 80ms
            800,
            30_000_000L,   // 30ms
            2,             // 进入需连续满足 2 周期
            3,             // 退出需连续满足 3 周期
            5000,          // 最小驻留 5s
            6,             // 每分钟迁移上限 6 次
            2_000_000_000L // 2s：指标过期判定
    );
}
