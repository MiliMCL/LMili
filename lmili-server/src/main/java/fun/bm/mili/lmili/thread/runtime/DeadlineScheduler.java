package fun.bm.mili.lmili.thread.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deadline Scheduler —— 基于累积时间的 Tick 调度器。
 *
 * <p>使用 {@code deadline = previousDeadline + TICK_INTERVAL} 避免长期 Tick drift，
 * 而不是 {@code deadline = currentTime + TICK_INTERVAL}。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li>防止 Tick 时间漂移</li>
 *   <li>稳定的 Tick 频率（默认 20 TPS = 50ms 间隔）</li>
 *   <li>可配置的 Tick 间隔</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。多个线程可以同时调用 {@link #nextDeadline()} 和 {@link #tick()}。
 */
public final class DeadlineScheduler {

    /** 默认 Tick 间隔：50ms (20 TPS) */
    public static final long DEFAULT_TICK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** 最小 Tick 间隔：10ms (100 TPS) */
    public static final long MIN_TICK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /** 最大 Tick 间隔：100ms (10 TPS) */
    public static final long MAX_TICK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /** 每次 Tick 增加的间隔（防止漂移） */
    private final AtomicLong nextDeadline = new AtomicLong(0);

    /** Tick 间隔（纳秒） */
    private final long tickIntervalNanos;

    /** 当前 Tick 计数 */
    private final AtomicLong tickCount = new AtomicLong(0);

    /** 统计：累计 drift 时间 */
    private final AtomicLong totalDriftNanos = new AtomicLong(0);

    /** 统计：超时 Tick 次数 */
    private final AtomicLong timeoutTicks = new AtomicLong(0);

    /**
     * 创建 DeadlineScheduler，使用默认 Tick 间隔。
     */
    public DeadlineScheduler() {
        this(DEFAULT_TICK_INTERVAL_NANOS);
    }

    /**
     * 创建 DeadlineScheduler。
     *
     * @param tickIntervalNanos Tick 间隔（纳秒）
     */
    public DeadlineScheduler(long tickIntervalNanos) {
        this.tickIntervalNanos = Math.max(MIN_TICK_INTERVAL_NANOS,
                Math.min(MAX_TICK_INTERVAL_NANOS, tickIntervalNanos));
        // 初始化下一个 deadline 为当前时间 + 间隔
        this.nextDeadline.set(System.nanoTime() + this.tickIntervalNanos);
    }

    /**
     * 获取下一个 deadline。
     *
     * <p>每次调用此方法会原子地获取当前 deadline 并计算下一个：
     * {@code nextDeadline = currentDeadline + tickIntervalNanos}
     *
     * @return 当前 deadline（纳秒）
     */
    public long nextDeadline() {
        return nextDeadline.getAndAdd(tickIntervalNanos);
    }

    /**
     * 获取当前 deadline（不推进）。
     */
    public long currentDeadline() {
        return nextDeadline.get();
    }

    /**
     * 推进到下一个 Tick。
     *
     * <p>增加 Tick 计数并返回新的 Tick 编号。
     *
     * @return 新的 Tick 编号
     */
    public long tick() {
        tickCount.incrementAndGet();
        return tickCount.get();
    }

    /**
     * 检查当前是否已超过 deadline。
     *
     * @return true 如果已超过 deadline
     */
    public boolean isOverdue() {
        return System.nanoTime() >= nextDeadline.get();
    }

    /**
     * 获取当前已超时的毫秒数。
     *
     * <p>如果未超时返回 0。
     */
    public long getOverdueMillis() {
        long overdue = System.nanoTime() - nextDeadline.get();
        return overdue > 0 ? TimeUnit.NANOSECONDS.toMillis(overdue) : 0;
    }

    /**
     * 记录一次 Tick 执行完成。
     *
     * <p>用于计算 drift 统计。
     *
     * @param executionTimeNanos 本次执行耗时（纳秒）
     */
    public void recordTickCompletion(long executionTimeNanos) {
        long drift = executionTimeNanos - tickIntervalNanos;
        if (drift > 0) {
            totalDriftNanos.addAndGet(drift);
            timeoutTicks.incrementAndGet();
        }
    }

    /**
     * 计算到下一个 deadline 需要等待的时间。
     *
     * <p>如果已经超时返回 0。
     *
     * @return 等待时间（纳秒）
     */
    public long waitForNextDeadline() {
        long deadline = nextDeadline.get();
        long now = System.nanoTime();
        long waitTime = deadline - now;
        return Math.max(0, waitTime);
    }

    // ---- 统计 ----

    public long getTickCount() { return tickCount.get(); }
    public long getTotalDriftNanos() { return totalDriftNanos.get(); }
    public long getTimeoutTicks() { return timeoutTicks.get(); }
    public long getTickIntervalNanos() { return tickIntervalNanos; }

    /**
     * 计算实际 TPS。
     */
    public double getActualTPS() {
        long drift = totalDriftNanos.get();
        if (drift == 0) return 1_000_000_000.0 / tickIntervalNanos;
        long totalTime = (tickCount.get() * tickIntervalNanos) + drift;
        return totalTime > 0 ? (double) tickCount.get() * 1_000_000_000.0 / totalTime : 0;
    }

    @Override
    public String toString() {
        return "DeadlineScheduler{tick=" + tickCount.get() +
                ", interval=" + TimeUnit.NANOSECONDS.toMillis(tickIntervalNanos) + "ms" +
                ", drift=" + TimeUnit.NANOSECONDS.toMillis(totalDriftNanos.get()) + "ms" +
                ", actualTPS=" + String.format("%.2f", getActualTPS()) + "}";
    }
}
