package fun.bm.mili.lmili.runtime.io;

/**
 * IO 状态快照（不可变；IO worker 线程写计数，控制线程采样读）（ARCHITECTURE_AdaptiveRuntime.md §3.11 / D-13）。
 */
public record IOState(
        /** 当前排队中的 sync 任务数（含正在执行） */
        long queueDepth,
        /** 正在执行 sync 的 worker 数 */
        int activeWorkers,
        /** 池总大小（动态调整后） */
        int workerPoolSize,
        /** 周期内平均 sync 延迟 */
        long avgLatencyNanos,
        /** 周期内 p99 sync 延迟（环形直方图） */
        long p99LatencyNanos,
        /** 周期内落盘字节速率 */
        long bytesPerSecond,
        /** 待 sync 的受管 region 文件数 */
        int pendingRegions,
        /** level >= SATURATED */
        boolean saturated,
        /** 分级 */
        IoSaturationLevel level,
        /** 周期内完成 sync 数 */
        long syncedRegions,
        long timestampNanos
) {

    /** 中性值 —— 指标缺失/未接线时的 fail-safe 输入（§6.2：ioQueue→0、ioP99→0） */
    public static IOState neutral() {
        return new IOState(0, 0, 0, 0, 0, 0, 0, false, IoSaturationLevel.NORMAL, 0, System.nanoTime());
    }
}
