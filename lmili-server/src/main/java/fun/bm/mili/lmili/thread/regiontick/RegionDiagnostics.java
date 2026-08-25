package fun.bm.mili.lmili.thread.regiontick;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region Tick 诊断统计 —— 收集性能指标和诊断信息。
 *
 * <p>修复：添加清空方法，允许运行时重置统计，避免长期运行后统计值过大。
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
     * 修复：重置所有统计计数器。
     * 用于周期性清空统计，避免 LongAdder 长期累加导致数值过大。
     */
    public void reset() {
        totalTicksDispatched.reset();
        totalTickErrors.reset();
        maxTickDurationNanos.set(0);
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
