package fun.bm.mili.lmili.thread.regiontick;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单 region 的实体 tick 指标 —— 现代化高并发实现（{@link LongAdder}，高竞争下性能远超 AtomicLong）。
 *
 * <p>统计：
 * <ul>
 *   <li>{@code ticked} —— 实际 tick 的实体数（含 Kaiiju limit / 时间预算通过的）</li>
 *   <li>{@code skipped} —— 因 Kaiiju limit skip 的实体数（降频分片）</li>
 *   <li>{@code removed} —— 因 Kaiiju removal 或世界级清理销毁的实体数</li>
 *   <li>{@code totalTickNanos} —— 所有 ticked 实体的 tick 耗时累计（用于算平均）</li>
 *   <li>{@code ticksCompleted} —— region tick 完成次数（含 budget exceeded）</li>
 *   <li>{@code budgetExceededTicks} —— region tick 触发了时间/shard 预算跳出的次数</li>
 *   <li>{@code lastTickElapsedNanos} / {@code lastTickTimestampMs} —— 最近一次 tick 的耗时与时间戳</li>
 * </ul>
 */
public final class EntityTickMetrics {

    private final LongAdder ticked = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder removed = new LongAdder();
    private final LongAdder totalTickNanos = new LongAdder();
    private final LongAdder ticksCompleted = new LongAdder();
    private final LongAdder budgetExceededTicks = new LongAdder();

    private volatile long lastTickElapsedNanos = 0L;
    private volatile long lastTickTimestampMs = 0L;

    public void recordTicked(final long entityNanos) {
        ticked.increment();
        totalTickNanos.add(entityNanos);
    }

    public void recordSkipped() {
        skipped.increment();
    }

    /**
     * 批量记录 skip 数（如 EntityPriorityScheduler 一次跳过 N 个超远实体）—— 比 recordSkipped()
     * 单次调用更高效（避免 LongAdder 多次 CAS）。
     */
    public void recordSkippedBatch(final int n) {
        if (n > 0) {
            skipped.add(n);
        }
    }

    public void recordRemoved() {
        removed.increment();
    }

    public void recordTickCompletion(final boolean budgetExceeded) {
        ticksCompleted.increment();
        if (budgetExceeded) {
            budgetExceededTicks.increment();
        }
    }

    public void recordLastTick(final long elapsedNanos) {
        lastTickElapsedNanos = elapsedNanos;
        lastTickTimestampMs = System.currentTimeMillis();
    }

    public Map<String, Object> snapshot() {
        final long tickedCount = ticked.sum();
        final long totalNanos = totalTickNanos.sum();
        final long avgNanos = tickedCount == 0L ? 0L : totalNanos / tickedCount;
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("ticked", tickedCount);
        map.put("skipped", skipped.sum());
        map.put("removed", removed.sum());
        map.put("ticks_completed", ticksCompleted.sum());
        map.put("budget_exceeded_ticks", budgetExceededTicks.sum());
        map.put("avg_entity_tick_nanos", avgNanos);
        map.put("last_tick_elapsed_ms", lastTickElapsedNanos / 1_000_000L);
        map.put("last_tick_timestamp_ms", lastTickTimestampMs);
        return map;
    }
}