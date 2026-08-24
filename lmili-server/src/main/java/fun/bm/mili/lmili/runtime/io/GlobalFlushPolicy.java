package fun.bm.mili.lmili.runtime.io;

/**
 * 全局 flush 优先级策略（如 CPU/IO_PRESSURE 时全局降级非关键 flush）（ARCHITECTURE_AdaptiveRuntime.md §3.7，
 * 由本实施新建的最小类型）。
 *
 * <p>IO_PRESSURE 状态动作（§4.2 状态动作汇总表）：① LOW→DEFERRED；② NORMAL→LOW；
 * 非压力态恒等映射。
 *
 * @param deferLowOnPressure        IO 压力下 LOW → DEFERRED（默认 true）
 * @param downgradeNormalOnPressure IO 压力下 NORMAL → LOW（默认 true）
 */
public record GlobalFlushPolicy(
        boolean deferLowOnPressure,
        boolean downgradeNormalOnPressure
) {

    public static final GlobalFlushPolicy DEFAULTS = new GlobalFlushPolicy(false, false);

    public static final GlobalFlushPolicy IO_PRESSURE = new GlobalFlushPolicy(true, true);

    /** 应用映射（非压力态恒等） */
    public FlushPriority map(FlushPriority p, boolean ioPressure) {
        if (!ioPressure) {
            return p;
        }
        if (deferLowOnPressure && p == FlushPriority.LOW) {
            return FlushPriority.DEFERRED;
        }
        if (downgradeNormalOnPressure && p == FlushPriority.NORMAL) {
            return FlushPriority.LOW;
        }
        return p;
    }

    /** 该优先级在压力态下是否应延期（映射后 ≤ LOW） */
    public boolean shouldDefer(FlushPriority p, boolean ioPressure) {
        return map(p, ioPressure).rank() <= FlushPriority.LOW.rank();
    }
}
