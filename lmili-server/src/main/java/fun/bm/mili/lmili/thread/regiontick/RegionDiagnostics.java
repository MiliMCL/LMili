package fun.bm.mili.lmili.thread.regiontick;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region Tick 诊断统计 —— 收集性能指标和诊断信息。
 */
public final class RegionDiagnostics {

    private final LongAdder totalTicksDispatched = new LongAdder();
    private final LongAdder totalTickErrors = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);

    /**
     * 记录一次 tick 的执行时间。
     */
    public void recordTick(long durationNanos) {
        totalTicksDispatched.increment();
        maxTickDurationNanos.accumulateAndGet(durationNanos, Math::max);
    }

    /**
     * 记录一次 tick 错误。
     */
    public void recordError() {
        totalTickErrors.increment();
    }

    /**
     * 返回总 tick 次数。
     */
    public long getTotalTicksDispatched() {
        return totalTicksDispatched.sum();
    }

    /**
     * 返回总错误次数。
     */
    public long getTotalErrors() {
        return totalTickErrors.sum();
    }

    /**
     * 返回最大 tick 耗时（毫秒）。
     */
    public long getMaxTickDurationMs() {
        return maxTickDurationNanos.get() / 1_000_000;
    }

    /**
     * 获取统计快照。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_ticks_dispatched", totalTicksDispatched.sum());
        stats.put("total_tick_errors", totalTickErrors.sum());
        stats.put("max_tick_duration_ms", maxTickDurationNanos.get() / 1_000_000);
        return stats;
    }
}
