package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region tick 上下文 —— 管理单个 region 的 tick 状态、统计和同步。
 *
 * <p>线程安全：使用 CAS 状态机防止 tick 重叠。
 *
 * <h3>状态机</h3>
 * <pre>
 * IDLE ──(tryBeginTick)──▶ TICKING ──(allSlicesDone/endTick)──▶ IDLE
 *                              │
 *                              └──(timeout/error)──▶ IDLE (强制恢复)
 * </pre>
 */
public final class RegionTickContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Region tick 状态。
     */
    public enum RegionTickState {
        /** 空闲，可以开始新 tick */
        IDLE,
        /** 正在 tick，slice 正在执行 */
        TICKING,
        /** 超时，tick 未完成 */
        TIMED_OUT
    }

    public final long regionId;
    public final io.papermc.paper.threadedregions.ThreadedRegionizer
            .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;

    private final AtomicReference<LongList> ownedChunks = new AtomicReference<>(new LongArrayList());
    private final AtomicInteger expectedSlices = new AtomicInteger(0);
    private final AtomicInteger completedSlices = new AtomicInteger(0);
    private volatile CountDownLatch tickLatch;
    private volatile long tickStartNanos;
    private volatile long lastTickDurationNanos;

    /**
     * Region tick 状态 —— 使用 CAS 防止 tick 重叠。
     */
    private final AtomicReference<RegionTickState> tickState = new AtomicReference<>(RegionTickState.IDLE);

    // 统计计数器
    private final LongAdder totalTicksCompleted = new LongAdder();
    private final LongAdder totalTickTimeNanos = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);
    private final AtomicLong currentTick = new AtomicLong(0);
    private final LongAdder totalSkippedTicks = new LongAdder();

    // 超时配置
    private static final long AWAIT_TIMEOUT_MS = 4000; // 4秒，留1秒给 watchdog
    private static final long SLOW_TICK_WARNING_MS = 50;

    public RegionTickContext(
            final long regionId,
            final io.papermc.paper.threadedregions.ThreadedRegionizer
                    .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        this.regionId = regionId;
        this.region = Objects.requireNonNull(region, "region");
    }

    public void refreshOwnedChunks(@NotNull final LongList chunks) {
        // 保存不可修改的 snapshot，避免外部修改影响内部状态
        this.ownedChunks.set(LongLists.unmodifiable(new LongArrayList(chunks)));
    }
    public LongList getOwnedChunks() { return this.ownedChunks.get(); }

    /**
     * 尝试开始 tick —— 使用 CAS 防止 tick 重叠。
     *
     * <p>如果前一个 tick 仍在执行，记录跳过并返回 false。
     * 如果之前处于 TIMED_OUT 状态，也允许开始新 tick。</p>
     *
     * @param sliceCount 本次 tick 的 slice 数量
     * @return true 如果成功进入 TICKING 状态
     */
    public boolean tryBeginTick(final int sliceCount) {
        RegionTickState currentState = tickState.get();
        if (currentState == RegionTickState.TICKING) {
            totalSkippedTicks.increment();
            LOGGER.debug("[RegionTickContext] Region #{} tick skipped — already ticking", regionId);
            return false;
        }
        // 从 IDLE 或 TIMED_OUT 进入 TICKING
        if (!tickState.compareAndSet(currentState, RegionTickState.TICKING)) {
            totalSkippedTicks.increment();
            LOGGER.debug("[RegionTickContext] Region #{} tick skipped — state changed", regionId);
            return false;
        }

        // CAS 成功，初始化 tick 状态
        this.expectedSlices.set(sliceCount);
        this.completedSlices.set(0);
        this.tickLatch = new CountDownLatch(sliceCount);
        this.tickStartNanos = System.nanoTime();
        this.currentTick.incrementAndGet();

        return true;
    }

    /**
     * 标记一个 slice 完成。
     *
     * @param tickGeneration slice 所属的 tick generation
     */
    public void arriveSlice(final long tickGeneration) {
        // 只有 generation 匹配时才更新计数和 latch
        if (tickGeneration != currentTick.get()) {
            // late completion，忽略
            return;
        }
        completedSlices.incrementAndGet();
        CountDownLatch latch = this.tickLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 标记一个 slice 完成（使用当前 generation）。
     *
     * <p>注意：此方法使用当前 generation，可能在 tick 已经切换时失效。
     */
    public void arriveSlice() {
        arriveSlice(currentTick.get());
    }

    /**
     * 标记一个 slice 失败（使用当前 generation）。
     *
     * <p>失败也会推进完成计数，但会记录失败状态。
     *
     * @param throwable 失败原因
     */
    public void failSlice(final long tickGeneration, final Throwable throwable) {
        if (tickGeneration != currentTick.get()) {
            return;
        }
        completedSlices.incrementAndGet();
        CountDownLatch latch = this.tickLatch;
        if (latch != null) {
            latch.countDown();
        }
        LOGGER.error("[RegionTickContext] Slice failed in region #{}", regionId, throwable);
    }

    /**
     * 标记一个 slice 失败（使用当前 generation）。
     */
    public void failSlice(final Throwable throwable) {
        failSlice(currentTick.get(), throwable);
    }

    /**
     * 等待所有 slice 完成。
     *
     * @return true 如果所有 slice 在超时前完成
     */
    public boolean awaitTickCompletion() {
        CountDownLatch latch = this.tickLatch;
        if (latch == null) return true;

        try {
            // 等待 latch，但同时检查 completedSlices 以避免旧任务错误 countDown
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                if (completedSlices.get() >= expectedSlices.get()) {
                    return true;
                }
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return completedSlices.get() >= expectedSlices.get();
                }
            }
            return completedSlices.get() >= expectedSlices.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 结束 tick —— 记录耗时统计并根据 slice 完成情况决定状态。
     *
     * <p>只有当所有 slice 都完成时才进入 IDLE，否则进入 TIMED_OUT。
     */
    public void endTick() {
        long elapsed = System.nanoTime() - this.tickStartNanos;
        this.lastTickDurationNanos = elapsed;

        // 检查是否所有 slice 都已完成
        if (completedSlices.get() < expectedSlices.get()) {
            // 有 slice 未完成，标记为超时
            tickState.set(RegionTickState.TIMED_OUT);
            LOGGER.warn("[RegionTickContext] Tick timeout in region #{}: {}ms (slices={}/{})",
                    regionId, elapsed / 1_000_000, completedSlices.get(), expectedSlices.get());
            return;
        }

        this.totalTicksCompleted.increment();
        this.totalTickTimeNanos.add(elapsed);
        this.maxTickDurationNanos.accumulateAndGet(elapsed, Math::max);

        // 所有 slice 完成，回到 IDLE 状态
        tickState.set(RegionTickState.IDLE);

        if (elapsed / 1_000_000 > SLOW_TICK_WARNING_MS) {
            LOGGER.warn("[RegionTickContext] Slow tick in region #{}: {}ms (chunks={}, slices={}/{})",
                    regionId, elapsed / 1_000_000, ownedChunks.get().size(),
                    completedSlices.get(), expectedSlices.get());
        }
    }

    /**
     * 检查当前是否正在 tick。
     */
    public boolean isTicking() {
        return tickState.get() == RegionTickState.TICKING;
    }

    /**
     * 获取当前 region tick 状态。
     */
    public RegionTickState getTickState() {
        return tickState.get();
    }

    /**
     * 获取已完成的 slice 数量。
     */
    public int getCompletedSlices() {
        return completedSlices.get();
    }

    /**
     * 获取期望的 slice 数量。
     */
    public int getExpectedSlices() {
        return expectedSlices.get();
    }

    /**
     * 强制重置 tick 状态 —— 仅用于错误恢复。
     *
     * <p>通过递增 generation 使旧 tick 的延迟完成失效，并回到 IDLE 状态。
     */
    public void forceReset() {
        // 递增 generation，使旧 tick 的 arriveSlice 调用被忽略
        currentTick.incrementAndGet();
        tickState.set(RegionTickState.IDLE);
        LOGGER.warn("[RegionTickContext] Force reset tick state for region #{}", regionId);
    }

    // ---- 统计查询 ----

    public long getLastTickDurationNanos() { return this.lastTickDurationNanos; }
    public long getLastTickDurationMs() { return this.lastTickDurationNanos / 1_000_000; }
    public long getTotalTicksCompleted() { return this.totalTicksCompleted.sum(); }
    public long getTotalSkippedTicks() { return this.totalSkippedTicks.sum(); }
    public long getTotalTickTimeNanos() { return this.totalTickTimeNanos.sum(); }
    public long getMaxTickDurationNanos() { return this.maxTickDurationNanos.get(); }
    public long getMaxTickDurationMs() { return this.maxTickDurationNanos.get() / 1_000_000; }
    public long getCurrentTick() { return this.currentTick.get(); }
    public void setCurrentTick(long tick) { this.currentTick.set(tick); }

    public long getAverageTickDurationNanos() {
        long completed = totalTicksCompleted.sum();
        return completed > 0 ? totalTickTimeNanos.sum() / completed : 0;
    }

    public long getAverageTickDurationMs() { return getAverageTickDurationNanos() / 1_000_000; }

    @Override
    public String toString() {
        return "RegionTickContext{regionId=" + regionId +
                ", chunks=" + ownedChunks.get().size() +
                ", slices=" + completedSlices.get() + "/" + expectedSlices.get() +
                ", avg_tick_ms=" + getAverageTickDurationMs() +
                ", max_tick_ms=" + getMaxTickDurationMs() +
                ", skipped=" + getTotalSkippedTicks() + "}";
    }
}
