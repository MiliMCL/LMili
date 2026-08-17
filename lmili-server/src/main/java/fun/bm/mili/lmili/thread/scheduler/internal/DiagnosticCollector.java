package fun.bm.mili.lmili.thread.scheduler.internal;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 诊断信息收集器 —— 运行时诊断事件和异常的记录。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>低开销</b>：正常路径只进行原子操作，不分配对象</li>
 *   <li><b>有界存储</b>：环形缓冲区限制内存使用，自动丢弃旧数据</li>
 *   <li><b>线程安全</b>：使用并发集合，无全局锁</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>记录任务执行异常（保留最近 N 个）</li>
 *   <li>记录调度决策（延迟、work-stealing 等）</li>
 *   <li>记录 region 状态变化</li>
 * </ul>
 */
public final class DiagnosticCollector {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private static final int MAX_RECENT_EXCEPTIONS = 100;
    private static final int MAX_RECENT_EVENTS = 500;
    private static final int MAX_SLOW_TASKS = 50;

    // ---- 环形缓冲区 ----
    private final Queue<ExceptionEntry> recentExceptions = new ConcurrentLinkedQueue<>();
    private final Queue<DiagnosticEvent> recentEvents = new ConcurrentLinkedQueue<>();
    private final Queue<SlowTaskEntry> slowTasks = new ConcurrentLinkedQueue<>();

    // ---- 计数器 ----
    private final LongAdder totalEvents = new LongAdder();
    private final LongAdder totalExceptions = new LongAdder();
    private final LongAdder totalSlowTasks = new LongAdder();
    private final AtomicLong lastSlowTaskWarnMillis = new AtomicLong(0);

    // ---- 阈值 ----
    private volatile long slowTaskThresholdNanos = 1_000_000L; // 1ms
    private volatile boolean recordingEnabled = true;

    /**
     * 创建诊断收集器。
     */
    public DiagnosticCollector() {
    }

    /**
     * 记录一个异常事件。
     *
     * @param type 异常类型/来源
     * @param exception 异常对象
     * @param context 上下文信息
     */
    public void recordException(@NotNull String type, @NotNull Throwable exception, @Nullable Map<String, Object> context) {
        totalExceptions.increment();

        if (!recordingEnabled) return;

        // 限制缓冲区大小
        while (recentExceptions.size() >= MAX_RECENT_EXCEPTIONS) {
            recentExceptions.poll();
        }

        recentExceptions.offer(new ExceptionEntry(
                Instant.now(),
                type,
                exception,
                context != null ? new HashMap<>(context) : Collections.emptyMap(),
                Thread.currentThread().getName()
        ));
    }

    /**
     * 记录一个调度事件。
     *
     * @param eventType 事件类型
     * @param details 事件详情
     */
    public void recordEvent(@NotNull String eventType, @NotNull Map<String, Object> details) {
        totalEvents.increment();

        if (!recordingEnabled) return;

        while (recentEvents.size() >= MAX_RECENT_EVENTS) {
            recentEvents.poll();
        }

        recentEvents.offer(new DiagnosticEvent(
                Instant.now(),
                eventType,
                new HashMap<>(details),
                Thread.currentThread().getName()
        ));
    }

    /**
     * 记录一个执行缓慢的任务。
     *
     * @param taskName 任务名称
     * @param durationNanos 执行耗时
     * @param regionId 关联的 region ID
     */
    public void recordSlowTask(@NotNull String taskName, long durationNanos, long regionId) {
        totalSlowTasks.increment();

        if (!recordingEnabled) return;

        // 只在超过阈值时记录
        if (durationNanos < slowTaskThresholdNanos) return;

        while (slowTasks.size() >= MAX_SLOW_TASKS) {
            slowTasks.poll();
        }

        slowTasks.offer(new SlowTaskEntry(
                Instant.now(),
                taskName,
                durationNanos,
                regionId
        ));

        // 限制告警频率
        long now = System.currentTimeMillis();
        long last = lastSlowTaskWarnMillis.get();
        if (now - last > 5000 && lastSlowTaskWarnMillis.compareAndSet(last, now)) {
            LOGGER.warn("[SchedulerDiag] Slow task detected: '{}' in region #{} took {}ms",
                    taskName, regionId, durationNanos / 1_000_000);
        }
    }

    /**
     * 获取最近的异常列表。
     */
    @NotNull
    public List<ExceptionEntry> getRecentExceptions() {
        return new ArrayList<>(recentExceptions);
    }

    /**
     * 获取最近的事件列表。
     */
    @NotNull
    public List<DiagnosticEvent> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    /**
     * 获取最近的慢任务列表。
     */
    @NotNull
    public List<SlowTaskEntry> getSlowTasks() {
        return new ArrayList<>(slowTasks);
    }

    /**
     * 设置慢任务阈值（纳秒）。
     */
    public void setSlowTaskThreshold(long thresholdNanos) {
        this.slowTaskThresholdNanos = Math.max(100_000L, thresholdNanos); // 最低 100μs
    }

    /**
     * 启用/禁用诊断记录。
     */
    public void setRecordingEnabled(boolean enabled) {
        this.recordingEnabled = enabled;
    }

    /**
     * 获取诊断摘要。
     */
    @NotNull
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_events", totalEvents.sum());
        summary.put("total_exceptions", totalExceptions.sum());
        summary.put("total_slow_tasks", totalSlowTasks.sum());
        summary.put("recent_exceptions", recentExceptions.size());
        summary.put("recent_events", recentEvents.size());
        summary.put("slow_tasks_buffered", slowTasks.size());
        summary.put("slow_task_threshold_nanos", slowTaskThresholdNanos);
        summary.put("recording_enabled", recordingEnabled);
        return summary;
    }

    /**
     * 清理所有缓冲区。
     */
    public void clear() {
        recentExceptions.clear();
        recentEvents.clear();
        slowTasks.clear();
    }

    // ---- 数据记录 ----

    /**
     * 异常记录。
     */
    public record ExceptionEntry(
            @NotNull Instant timestamp,
            @NotNull String type,
            @NotNull Throwable exception,
            @NotNull Map<String, Object> context,
            @NotNull String threadName
    ) {}

    /**
     * 诊断事件记录。
     */
    public record DiagnosticEvent(
            @NotNull Instant timestamp,
            @NotNull String eventType,
            @NotNull Map<String, Object> details,
            @NotNull String threadName
    ) {}

    /**
     * 慢任务记录。
     */
    public record SlowTaskEntry(
            @NotNull Instant timestamp,
            @NotNull String taskName,
            @NotNull long durationNanos,
            long regionId
    ) {}
}
