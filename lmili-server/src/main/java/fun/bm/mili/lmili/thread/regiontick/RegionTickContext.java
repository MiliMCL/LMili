package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region tick 上下文 —— 管理单个 region 的 tick 状态、统计和屏障同步。
 *
 * <p>线程安全：本类的状态由 region tick 线程拥有，部分统计字段使用原子类型供外部读取。
 */
public final class RegionTickContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Region tick 状态 —— 防止 tick 重叠。
     *
     * <pre>
     * IDLE
     *  ↓
     * TICKING
     *  ↓
     * DRAINING
     *  ↓
     * IDLE
     * </pre>
     */
    public enum RegionTickState {
        /** 空闲，可以开始新 tick */
        IDLE,
        /** 正在 tick */
        TICKING,
        /** 正在排空，等待完成 */
        DRAINING
    }

    public final long regionId;
    public final io.papermc.paper.threadedregions.ThreadedRegionizer
            .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;

    private final AtomicReference<LongList> ownedChunks = new AtomicReference<>(new LongArrayList());
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private volatile Phaser tickBarrier;
    private volatile long tickStartNanos;
    private volatile long lastTickDurationNanos;

    /**
     * Region tick 状态 —— 防止 tick 重叠。
     * Tick N 未完成时，同一 Region 不能进入 Tick N+1。
     */
    private final AtomicReference<RegionTickState> tickState = new AtomicReference<>(RegionTickState.IDLE);

    // 统计计数器（外部可读）
    private final LongAdder totalTicksCompleted = new LongAdder();
    private final LongAdder totalTickTimeNanos = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);
    private final AtomicLong currentTick = new AtomicLong(0);

    // Phaser 超时配置
    private static final long AWAIT_TIMEOUT_SECONDS = 30;
    private static final long SLOW_TICK_WARNING_MS = 50;

    public RegionTickContext(
            final long regionId,
            final io.papermc.paper.threadedregions.ThreadedRegionizer
                    .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        this.regionId = regionId;
        this.region = Objects.requireNonNull(region, "region");
    }

    public void refreshOwnedChunks(@NotNull final LongList chunks) { this.ownedChunks.set(chunks); }
    public LongList getOwnedChunks() { return this.ownedChunks.get(); }

    /**
     * 开始 tick —— 初始化指定参与方数量的 barrier。
     *
     * @param parties 需要到达的参与方数（= slice 数 + 1（region tick 线程本身））
     */
    public void beginTick(final int parties) {
        this.tickBarrier = new Phaser(parties);
        this.tickStartNanos = System.nanoTime();
        this.currentTick.incrementAndGet();
    }

    /**
     * 尝试开始 tick —— 使用 CAS 防止 tick 重叠。
     *
     * <p>Tick N 未完成时，同一 Region 不能进入 Tick N+1。
     *
     * @param parties 需要到达的参与方数
     * @return true 如果成功进入 TICKING 状态，false 如果已有 tick 在执行
     */
    public boolean tryBeginTick(final int parties) {
        if (!tickState.compareAndSet(RegionTickState.IDLE, RegionTickState.TICKING)) {
            return false; // 当前 Region 已经有 Tick 在执行
        }
        this.tickBarrier = new Phaser(parties);
        this.tickStartNanos = System.nanoTime();
        this.currentTick.incrementAndGet();
        return true;
    }

    /**
     * 标记 tick 进入排空阶段。
     */
    public void beginDraining() {
        tickState.set(RegionTickState.DRAINING);
    }

    /**
     * 完成 tick —— 回到 IDLE 状态。
     */
    public void finishTick() {
        tickState.set(RegionTickState.IDLE);
    }

    /**
     * 检查当前是否正在 tick。
     */
    public boolean isTicking() {
        return tickState.get() != RegionTickState.IDLE;
    }

    /**
     * 获取当前 region tick 状态。
     */
    public RegionTickState getTickState() {
        return tickState.get();
    }

    /**
     * 等待所有参与方完成 tick —— 使用 Phaser 中断式等待。
     *
     * <p>如果超时（worker 线程可能卡死/崩溃），强制终止并记录警告。
     */
    public void awaitTickCompletion() {
        Phaser barrier = this.tickBarrier;
        if (barrier == null) return;
        int phase = barrier.arrive();
        try {
            barrier.awaitAdvanceInterruptibly(phase, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("[RegionTickContext] Region #{} tick timed out after {}s — forcing advance. " +
                            "Registered={}, Arrived={}, Unarrived={}",
                    regionId, AWAIT_TIMEOUT_SECONDS,
                    barrier.getRegisteredParties(), barrier.getArrivedParties(), barrier.getUnarrivedParties());
            barrier.forceTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Slice 完成通知。
     */
    public void arriveSlice() {
        Phaser barrier = this.tickBarrier;
        if (barrier != null) barrier.arrive();
    }

    /**
     * 结束 tick —— 记录耗时统计。
     */
    public void endTick() {
        long elapsed = System.nanoTime() - this.tickStartNanos;
        this.lastTickDurationNanos = elapsed;
        this.totalTicksCompleted.increment();
        this.totalTickTimeNanos.add(elapsed);
        this.maxTickDurationNanos.accumulateAndGet(elapsed, Math::max);

        // 回到 IDLE 状态，允许下一 tick
        finishTick();

        if (elapsed / 1_000_000 > SLOW_TICK_WARNING_MS) {
            LOGGER.warn("[RegionTickContext] Slow tick in region #{}: {}ms (chunks={})",
                    regionId, elapsed / 1_000_000, ownedChunks.get().size());
        }
    }

    // ---- 统计查询 ----

    public long getLastTickDurationNanos() { return this.lastTickDurationNanos; }
    public long getLastTickDurationMs() { return this.lastTickDurationNanos / 1_000_000; }
    public long getTotalTicksCompleted() { return this.totalTicksCompleted.sum(); }
    public long getTotalTickTimeNanos() { return this.totalTickTimeNanos.sum(); }
    public long getMaxTickDurationNanos() { return this.maxTickDurationNanos.get(); }
    public long getMaxTickDurationMs() { return this.maxTickDurationNanos.get() / 1_000_000; }

    public long getAverageTickDurationNanos() {
        long completed = totalTicksCompleted.sum();
        return completed > 0 ? totalTickTimeNanos.sum() / completed : 0;
    }

    public long getAverageTickDurationMs() { return getAverageTickDurationNanos() / 1_000_000; }

    public int getWorkerCount() { return this.workerCount.get(); }
    public void setWorkerCount(int count) { this.workerCount.set(count); }
    public long getCurrentTick() { return this.currentTick.get(); }
    public void setCurrentTick(long tick) { this.currentTick.set(tick); }

    /**
     * 计算期望的 worker 数 —— 基于 chunk 数和并行度阈值。
     *
     * @param maxWorkersPerRegion 每个 region 最大 worker 数
     * @param parallelismThreshold 每个 worker 最少处理的 chunk 数
     */
    public int computeDesiredWorkers(final int maxWorkersPerRegion, final int parallelismThreshold) {
        int chunkCount = this.ownedChunks.get().size();
        if (chunkCount < parallelismThreshold) return 1;
        int desired = (chunkCount + parallelismThreshold - 1) / parallelismThreshold;
        return Math.min(desired, maxWorkersPerRegion);
    }

    /**
     * 计算保证的最小 worker 数 —— 与期望值取较大者。
     */
    public int computeGuaranteedWorkers(final int maxWorkersPerRegion,
                                         final int minWorkersPerRegion,
                                         final int parallelismThreshold) {
        return Math.max(computeDesiredWorkers(maxWorkersPerRegion, parallelismThreshold), minWorkersPerRegion);
    }

    @Override
    public String toString() {
        return "RegionTickContext{regionId=" + regionId +
                ", chunks=" + ownedChunks.get().size() +
                ", workers=" + workerCount.get() +
                ", avg_tick_ms=" + getAverageTickDurationMs() +
                ", max_tick_ms=" + getMaxTickDurationMs() + "}";
    }
}
