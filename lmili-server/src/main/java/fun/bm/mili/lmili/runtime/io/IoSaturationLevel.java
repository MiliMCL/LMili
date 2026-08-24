package fun.bm.mili.lmili.runtime.io;

/**
 * IO 饱和度分级（确定性映射，见 ARCHITECTURE_AdaptiveRuntime.md §4.2 公式）。
 */
public enum IoSaturationLevel {
    /** queueDepth &lt; 500 且 p99 &lt; 20ms */
    NORMAL(0),
    /** queueDepth &lt; 2000 且 p99 &lt; 80ms */
    BUSY(1),
    /** queueDepth ≥ 2000 或 p99 ≥ 80ms */
    SATURATED(2),
    /** queueDepth ≥ 5000 或 p99 ≥ 200ms */
    CRITICAL(3);

    private final int rank;

    IoSaturationLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 是否达到（含）指定级别 */
    public boolean atLeast(IoSaturationLevel other) {
        return rank >= other.rank;
    }
}
