package fun.bm.mili.lmili.thread.runtime.diagnostics;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 长尾 Tick 结构化事件追踪（§12 P2-3 LMili_Next_Step_Optimization.md）。
 *
 * <p>当一次 Tick 耗时超过 <code>softBudget</code> 时记录 <i>soft</i> 事件；
 * 超过 <code>hardBudget</code> 时记录 <i>hard</i> 事件（含更完整的诊断 dump）。
 *
 * <h3>每条事件至少记录（§12）</h3>
 * <ul>
 *   <li>tickId</li>
 *   <li>regionId</li>
 *   <li>thread</li>
 *   <li>task</li>
 *   <li>duration（ms）</li>
 *   <li>blockingReason</li>
 *   <li>chunkWait（ms / 次数）</li>
 *   <li>dependencyWait（ms / 次数）</li>
 *   <li>pluginTask</li>
 *   <li>entityCount</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>任何线程都可调用 {@link #recordSoft} / {@link #recordHard}；事件写入使用
 * {@link ArrayDeque#addFirst} + synchronized（事件频率低，无锁复杂度不值得）。
 *
 * <h3>使用建议</h3>
 * <p>{@link #snapshot()} 返回最近 N 条事件；{@link #drain()} 排空并返回。
 * 可挂到 {@code /lmili debug scheduler}（§11）以及 async log appender 上。
 */
public final class LongTailTickDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 默认保留最近多少条 hard 事件 */
    public static final int DEFAULT_HARD_HISTORY = 64;
    /** 默认保留最近多少条 soft 事件 */
    public static final int DEFAULT_SOFT_HISTORY = 128;

    /** softBudget 触发次数 */
    private final AtomicLong softTripCount = new AtomicLong(0);
    /** hardBudget 触发次数 */
    private final AtomicLong hardTripCount = new AtomicLong(0);
    /** hardBudget 时 dump 的累计 tick 数（counter for tail ratio） */
    private final AtomicLong ticksObserved = new AtomicLong(0);

    private final long softBudgetNanos;
    private final long hardBudgetNanos;

    private final Deque<SoftTickEvent> softHistory;
    private final Deque<HardTickEvent> hardHistory;

    /** 最近一条 hard 事件（用于 /lmili debug 即时查看） */
    private final AtomicReference<HardTickEvent> lastHard = new AtomicReference<>();

    public LongTailTickDiagnostics(long softBudgetNanos, long hardBudgetNanos) {
        this(softBudgetNanos, hardBudgetNanos, DEFAULT_SOFT_HISTORY, DEFAULT_HARD_HISTORY);
    }

    public LongTailTickDiagnostics(long softBudgetNanos, long hardBudgetNanos,
                                   int softHistoryCapacity, int hardHistoryCapacity) {
        this.softBudgetNanos = softBudgetNanos;
        this.hardBudgetNanos = Math.max(softBudgetNanos, hardBudgetNanos);
        this.softHistory = new ArrayDeque<>(softHistoryCapacity);
        this.hardHistory = new ArrayDeque<>(hardHistoryCapacity);
    }

    /**
     * 记录一次 Tick 完成；按耗时自动分类为 normal / soft / hard。
     *
     * @return 该 Tick 的归类等级
     */
    public Level record(long tickId, long regionId, Thread thread, String taskName,
                        long elapsedNanos, BlockingReason reason,
                        long chunkWaitNanos, int chunkWaitCount,
                        long dependencyWaitNanos, int dependencyWaitCount,
                        boolean pluginTask, int entityCount) {
        ticksObserved.incrementAndGet();
        if (elapsedNanos < softBudgetNanos) {
            return Level.NORMAL;
        }
        if (elapsedNanos < hardBudgetNanos) {
            recordSoft(new SoftTickEvent(tickId, regionId, thread.getName(),
                    taskName, nanosToMs(elapsedNanos), reason,
                    nanosToMs(chunkWaitNanos), chunkWaitCount,
                    nanosToMs(dependencyWaitNanos), dependencyWaitCount,
                    pluginTask, entityCount, System.currentTimeMillis()));
            return Level.SOFT;
        }
        recordHard(new HardTickEvent(tickId, regionId, thread.getName(),
                taskName, nanosToMs(elapsedNanos), reason,
                nanosToMs(chunkWaitNanos), chunkWaitCount,
                nanosToMs(dependencyWaitNanos), dependencyWaitCount,
                pluginTask, entityCount, System.currentTimeMillis()));
        return Level.HARD;
    }

    private void recordSoft(SoftTickEvent ev) {
        softTripCount.incrementAndGet();
        synchronized (softHistory) {
            if (softHistory.size() >= DEFAULT_SOFT_HISTORY) {
                softHistory.pollLast();
            }
            softHistory.addFirst(ev);
        }
    }

    private void recordHard(HardTickEvent ev) {
        hardTripCount.incrementAndGet();
        lastHard.set(ev);
        synchronized (hardHistory) {
            if (hardHistory.size() >= DEFAULT_HARD_HISTORY) {
                hardHistory.pollLast();
            }
            hardHistory.addFirst(ev);
        }
        // hard 事件额外输出结构化诊断日志（§12）
        LOGGER.warn("[LongTail-HARD] {}", ev);
    }

    /**
     * 排空所有事件。
     */
    public Drained drain() {
        List<SoftTickEvent> soft;
        List<HardTickEvent> hard;
        synchronized (softHistory) {
            soft = new ArrayList<>(softHistory);
            softHistory.clear();
        }
        synchronized (hardHistory) {
            hard = new ArrayList<>(hardHistory);
            hardHistory.clear();
        }
        return new Drained(soft, hard);
    }

    /**
     * 不排空，仅返回当前内容。
     */
    public Snapshot snapshot() {
        List<SoftTickEvent> soft;
        List<HardTickEvent> hard;
        synchronized (softHistory) {
            soft = new ArrayList<>(softHistory);
        }
        synchronized (hardHistory) {
            hard = new ArrayList<>(hardHistory);
        }
        return new Snapshot(softTripCount.get(), hardTripCount.get(),
                ticksObserved.get(), soft, hard, lastHard.get());
    }

    public long softTripCount() { return softTripCount.get(); }
    public long hardTripCount() { return hardTripCount.get(); }
    public long ticksObserved() { return ticksObserved.get(); }
    public long softBudgetNanos() { return softBudgetNanos; }
    public long hardBudgetNanos() { return hardBudgetNanos; }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    // ===== Types =====

    public enum Level { NORMAL, SOFT, HARD }

    public enum BlockingReason {
        NONE,
        CHUNK_LOAD,
        POI_QUERY,
        DEPENDENCY,
        PLUGIN_GLOBAL_LOCK,
        GC,
        IO,
        UNKNOWN
    }

    public record SoftTickEvent(
            long tickId,
            long regionId,
            String thread,
            String task,
            double durationMs,
            BlockingReason reason,
            double chunkWaitMs,
            int chunkWaitCount,
            double dependencyWaitMs,
            int dependencyWaitCount,
            boolean pluginTask,
            int entityCount,
            long timestampMs
    ) {
        @Override
        public String toString() {
            return String.format(
                    "SOFT{tick=%d, region=%d, thread=%s, task=%s, dur=%.2fms, reason=%s, " +
                    "chunkWait=%.2fms/%d, depWait=%.2fms/%d, plugin=%s, entities=%d}",
                    tickId, regionId, thread, task, durationMs, reason,
                    chunkWaitMs, chunkWaitCount, dependencyWaitMs, dependencyWaitCount,
                    pluginTask, entityCount);
        }
    }

    public record HardTickEvent(
            long tickId,
            long regionId,
            String thread,
            String task,
            double durationMs,
            BlockingReason reason,
            double chunkWaitMs,
            int chunkWaitCount,
            double dependencyWaitMs,
            int dependencyWaitCount,
            boolean pluginTask,
            int entityCount,
            long timestampMs
    ) {
        @Override
        public String toString() {
            return String.format(
                    "HARD{tick=%d, region=%d, thread=%s, task=%s, dur=%.2fms, reason=%s, " +
                    "chunkWait=%.2fms/%d, depWait=%.2fms/%d, plugin=%s, entities=%d}",
                    tickId, regionId, thread, task, durationMs, reason,
                    chunkWaitMs, chunkWaitCount, dependencyWaitMs, dependencyWaitCount,
                    pluginTask, entityCount);
        }
    }

    public record Snapshot(
            long softTrips,
            long hardTrips,
            long ticksObserved,
            List<SoftTickEvent> softEvents,
            List<HardTickEvent> hardEvents,
            HardTickEvent lastHard
    ) {
        /** tail ratio = hardTrips / ticksObserved（长尾率，§15） */
        public double hardTailRatio() {
            return ticksObserved > 0 ? (double) hardTrips / ticksObserved : 0.0;
        }

        public double softTailRatio() {
            return ticksObserved > 0 ? (double) softTrips / ticksObserved : 0.0;
        }
    }

    public record Drained(List<SoftTickEvent> soft, List<HardTickEvent> hard) {}
}
