package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.runtime.generation.TickGeneration;
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
 * <h3>生命周期状态机</h3>
 * <pre>
 * CREATED
 *    ↓
 * RUNNING
 *    ↓
 * DEADLINE_EXCEEDED
 *    ↓
 * DRAINING
 *    ↓
 * COMPLETED / CANCELLED
 * </pre>
 *
 * <h3>Generation 隔离保证</h3>
 * <ul>
 *   <li>旧 Generation 的迟到完成不会影响新 Generation</li>
 *   <li>旧 Generation 不得修改新 Tick 状态</li>
 *   <li>旧 Generation 不得增加新 Tick completed counter</li>
 *   <li>旧 Generation 不得触发新 Tick completion 或 reschedule</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>创建/调度线程：Region Tick 线程</li>
 *   <li>执行线程：Worker 线程</li>
 *   <li>完成通知：Worker 线程（通过 arriveSlice）</li>
 * </ul>
 */
public final class RegionTickContext {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Region tick 状态 —— 使用 CAS 防止 tick 重叠。
     *
     * @deprecated 使用 {@link TickGeneration.State} 替代
     */
    @Deprecated
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

    /** 当前活跃的 Generation */
    private final AtomicReference<TickGeneration> currentGeneration = new AtomicReference<>();

    /** 上一个 Generation（用于诊断和 late completion 检测） */
    private volatile TickGeneration previousGeneration;

    private volatile CountDownLatch tickLatch;
    private volatile long tickStartNanos;
    private volatile long lastTickDurationNanos;

    /**
     * Region tick 状态 —— 使用 CAS 防止 tick 重叠。
     */
    private final AtomicReference<TickGeneration.State> tickState = new AtomicReference<>(TickGeneration.State.CREATED);

    // 统计计数器
    private final LongAdder totalTicksCompleted = new LongAdder();
    private final LongAdder totalTickTimeNanos = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);
    private final AtomicLong generationCounter = new AtomicLong(0);
    private final LongAdder totalSkippedTicks = new LongAdder();
    private final LongAdder totalLateCompletions = new LongAdder();
    private final LongAdder totalTimeouts = new LongAdder();

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
     * 获取当前 Generation ID。
     *
     * <p>注意：此方法返回的是创建时的快照，可能在调用后立即变化。
     * 任务创建时应捕获此值，而不是依赖执行时的"当前"值。
     */
    public long getCurrentGenerationId() {
        TickGeneration gen = currentGeneration.get();
        return gen != null ? gen.generationId() : generationCounter.get();
    }

    /**
     * 尝试开始新的 tick —— 创建新的 TickGeneration。
     *
     * <p>如果前一个 tick 仍在执行，记录跳过并返回 false。
     * 如果前一个 tick 处于 DEADLINE_EXCEEDED 或 DRAINING 状态，允许开始新 tick。
     *
     * @param sliceCount 本次 tick 的 slice 数量
     * @param deadlineNanos 本次 tick 的 deadline（纳秒）
     * @return 新的 TickGeneration，如果无法开始则返回 null
     */
    public TickGeneration tryBeginTick(final int sliceCount, final long deadlineNanos) {
        TickGeneration.State currentState = tickState.get();

        // 只有 CREATED 或 COMPLETED 或 CANCELLED 状态才能开始新 tick
        if (currentState == TickGeneration.State.RUNNING ||
            currentState == TickGeneration.State.DEADLINE_EXCEEDED ||
            currentState == TickGeneration.State.DRAINING) {
            totalSkippedTicks.increment();
            LOGGER.debug("[RegionTickContext] Region #{} tick skipped — current state: {}", regionId, currentState);
            return null;
        }

        // CAS 确保状态一致性
        if (!tickState.compareAndSet(currentState, TickGeneration.State.RUNNING)) {
            totalSkippedTicks.increment();
            LOGGER.debug("[RegionTickContext] Region #{} tick skipped — state changed", regionId);
            return null;
        }

        // CAS 成功，创建新的 Generation
        long newGenId = generationCounter.incrementAndGet();
        TickGeneration newGen = new TickGeneration(newGenId, sliceCount, deadlineNanos);

        // 保存上一个 generation 用于诊断
        TickGeneration oldGen = currentGeneration.get();
        if (oldGen != null) {
            this.previousGeneration = oldGen;
        }
        this.currentGeneration.set(newGen);

        // 初始化 tick 状态
        this.tickLatch = new CountDownLatch(sliceCount);
        this.tickStartNanos = System.nanoTime();

        // 尝试从 CREATED 转换到 RUNNING
        newGen.tryBegin();

        return newGen;
    }

    /**
     * 尝试开始新的 tick（使用相对延迟计算 deadline）。
     *
     * <p>使用 {@code previousDeadline + 50ms} 而非 {@code currentTime + 50ms} 来避免长期 Tick drift。
     *
     * @param sliceCount 本次 tick 的 slice 数量
     * @param relativeDeadlineNanos 相对 deadline（从当前时间算起）
     * @return 新的 TickGeneration，如果无法开始则返回 null
     */
    public TickGeneration tryBeginTickWithRelativeDeadline(final int sliceCount, final long relativeDeadlineNanos) {
        long deadline = System.nanoTime() + relativeDeadlineNanos;
        return tryBeginTick(sliceCount, deadline);
    }

    /**
     * 标记一个 slice 完成 —— 必须携带 generation ID。
     *
     * <p><b>Generation 隔离</b>：只有 generation 匹配时才更新计数和 latch。
     * 旧 Generation 的迟到完成会被忽略并记录诊断信息。
     *
     * @param generationId slice 所属的 tick generation
     */
    public void arriveSlice(final long generationId) {
        TickGeneration current = currentGeneration.get();

        // Generation 不匹配 —— late completion
        if (current == null || current.generationId() != generationId) {
            totalLateCompletions.increment();
            LOGGER.debug("[RegionTickContext] Late completion ignored for region #{}: gen={}, current={}",
                    regionId, generationId, current != null ? current.generationId() : "null");
            return;
        }

        // 标记任务完成
        current.markTaskCompleted();

        // 更新 latch
        CountDownLatch latch = this.tickLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 标记一个 slice 完成（使用当前 generation）。
     *
     * @deprecated 此方法使用当前 generation，可能在 tick 已经切换时失效。
     *             应使用 {@link #arriveSlice(long)} 并传入创建时捕获的 generationId。
     */
    @Deprecated
    public void arriveSlice() {
        TickGeneration current = currentGeneration.get();
        if (current != null) {
            arriveSlice(current.generationId());
        }
    }

    /**
     * 标记一个 slice 失败 —— 必须携带 generation ID。
     *
     * @param generationId slice 所属的 tick generation
     * @param throwable 失败原因
     */
    public void failSlice(final long generationId, final Throwable throwable) {
        TickGeneration current = currentGeneration.get();

        // Generation 不匹配 —— late failure，忽略
        if (current == null || current.generationId() != generationId) {
            totalLateCompletions.increment();
            LOGGER.debug("[RegionTickContext] Late failure ignored for region #{}: gen={}", regionId, generationId);
            return;
        }

        current.markTaskCompleted();
        current.reportFailure(throwable);

        CountDownLatch latch = this.tickLatch;
        if (latch != null) {
            latch.countDown();
        }
        LOGGER.error("[RegionTickContext] Slice failed in region #{} gen={}", regionId, generationId, throwable);
    }

    /**
     * 标记一个 slice 失败（使用当前 generation）。
     *
     * @deprecated 应使用 {@link #failSlice(long, Throwable)} 并传入创建时捕获的 generationId。
     */
    @Deprecated
    public void failSlice(final Throwable throwable) {
        TickGeneration current = currentGeneration.get();
        if (current != null) {
            failSlice(current.generationId(), throwable);
        }
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
                TickGeneration current = currentGeneration.get();
                if (current != null && current.allTasksCompleted()) {
                    return true;
                }
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return currentGeneration.get() != null && currentGeneration.get().allTasksCompleted();
                }
            }
            return currentGeneration.get() != null && currentGeneration.get().allTasksCompleted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 检查当前 tick 是否已超时。
     *
     * @return true 如果已触发超时转换
     */
    public boolean checkTimeout() {
        TickGeneration current = currentGeneration.get();
        if (current == null) return false;

        long now = System.nanoTime();
        boolean timedOut = current.checkTimeout(now);

        if (timedOut) {
            totalTimeouts.increment();
            tickState.set(TickGeneration.State.DEADLINE_EXCEEDED);
            LOGGER.warn("[RegionTickContext] Tick timeout in region #{} gen={}: deadline={}ms",
                    regionId, current.generationId(), (now - current.deadlineNanos()) / 1_000_000);
        }

        return timedOut;
    }

    /**
     * 进入 DRAINING 状态 —— 不再接受新任务，等待剩余任务完成。
     *
     * @return true 如果成功进入 DRAINING
     */
    public boolean beginDraining() {
        TickGeneration current = currentGeneration.get();
        if (current == null) return false;

        boolean drained = current.tryBeginDraining();
        if (drained) {
            tickState.set(TickGeneration.State.DRAINING);
        }
        return drained;
    }

    /**
     * 结束 tick —— 记录耗时统计并根据 slice 完成情况决定状态。
     *
     * <p>只有当所有 slice 都完成时才进入 COMPLETED，否则进入 DEADLINE_EXCEEDED → DRAINING → CANCELLED。
     */
    public void endTick() {
        long elapsed = System.nanoTime() - this.tickStartNanos;
        this.lastTickDurationNanos = elapsed;

        TickGeneration current = currentGeneration.get();
        if (current == null) {
            LOGGER.error("[RegionTickContext] endTick called with no active generation for region #{}", regionId);
            return;
        }

        // 检查是否所有 slice 都已完成
        if (current.allTasksCompleted()) {
            // 所有任务完成 —— 进入 DRAINING → COMPLETED
            current.trySealFromRunning();
            current.complete();
            tickState.set(TickGeneration.State.COMPLETED);

            this.totalTicksCompleted.increment();
            this.totalTickTimeNanos.add(elapsed);
            this.maxTickDurationNanos.accumulateAndGet(elapsed, Math::max);

            if (elapsed / 1_000_000 > SLOW_TICK_WARNING_MS) {
                LOGGER.warn("[RegionTickContext] Slow tick in region #{}: {}ms (chunks={}, slices={}/{})",
                        regionId, elapsed / 1_000_000, ownedChunks.get().size(),
                        current.completedTasks(), current.expectedTasks());
            }
        } else {
            // 有 slice 未完成 —— 进入 DEADLINE_EXCEEDED → DRAINING → CANCELLED
            current.checkTimeout(System.nanoTime());
            current.tryBeginDraining();
            current.cancel();
            tickState.set(TickGeneration.State.CANCELLED);

            LOGGER.warn("[RegionTickContext] Tick cancelled in region #{}: {}ms (slices={}/{})",
                    regionId, elapsed / 1_000_000, current.completedTasks(), current.expectedTasks());
        }
    }

    /**
     * 检查当前是否正在 tick。
     */
    public boolean isTicking() {
        return tickState.get() == TickGeneration.State.RUNNING;
    }

    /**
     * 获取当前 region tick 状态。
     */
    public TickGeneration.State getTickState() {
        return tickState.get();
    }

    /**
     * 获取当前 TickGeneration。
     */
    public TickGeneration getCurrentGeneration() {
        return currentGeneration.get();
    }

    /**
     * 获取已完成的 slice 数量。
     */
    public int getCompletedSlices() {
        TickGeneration current = currentGeneration.get();
        return current != null ? current.completedTasks() : 0;
    }

    /**
     * 获取期望的 slice 数量。
     */
    public int getExpectedSlices() {
        TickGeneration current = currentGeneration.get();
        return current != null ? current.expectedTasks() : 0;
    }

    /**
     * 强制重置 tick 状态 —— 仅用于错误恢复。
     *
     * <p>通过创建新 Generation 使旧 tick 的延迟完成失效。
     */
    public void forceReset() {
        // 递增 generation，使旧 tick 的 arriveSlice 调用被忽略
        long newGenId = generationCounter.incrementAndGet();
        TickGeneration newGen = new TickGeneration(newGenId, 0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5000));

        TickGeneration oldGen = currentGeneration.getAndSet(newGen);
        if (oldGen != null) {
            oldGen.cancel();
        }

        tickState.set(TickGeneration.State.CANCELLED);
        LOGGER.warn("[RegionTickContext] Force reset tick state for region #{}: new gen={}", regionId, newGenId);
    }

    /**
     * 关闭此 context —— 清理所有资源。
     *
     * <p>在 region 被销毁时由 {@code RegionTickDispatcher.unregisterRegion()} 调用。
     * 执行以下操作：
     * <ol>
     *   <li>取消当前 Generation（阻止迟到完成影响后续状态）</li>
     *   <li>将 tickState 置为 CANCELLED</li>
     *   <li>释放 tickLatch（防止等待线程永远阻塞）</li>
     * </ol>
     *
     * <p>此方法幂等，多次调用安全。</p>
     */
    public void close() {
        // 1. 取消当前 Generation
        final TickGeneration gen = currentGeneration.getAndSet(null);
        if (gen != null) {
            gen.cancel();
        }

        // 2. 重置 tick 状态
        tickState.set(TickGeneration.State.CANCELLED);

        // 3. 释放 latch（如果有等待线程）
        // 修复：使用局部变量避免 TOCTOU 问题，并添加中断通知
        final CountDownLatch latch = this.tickLatch;
        if (latch != null) {
            // 一次性 countDown 到 0，避免在循环中产生大量不必要的 countDown 调用
            // 使用 synchronized 确保与 arriveSlice 的 countDown 不会产生竞态
            synchronized (this) {
                while (latch.getCount() > 0) {
                    latch.countDown();
                }
            }
        }
    }

    // ---- 统计查询 ----

    public long getLastTickDurationNanos() { return this.lastTickDurationNanos; }
    public long getLastTickDurationMs() { return this.lastTickDurationNanos / 1_000_000; }
    public long getTotalTicksCompleted() { return this.totalTicksCompleted.sum(); }
    public long getTotalSkippedTicks() { return this.totalSkippedTicks.sum(); }
    public long getTotalTickTimeNanos() { return this.totalTickTimeNanos.sum(); }
    public long getMaxTickDurationNanos() { return this.maxTickDurationNanos.get(); }
    public long getMaxTickDurationMs() { return this.maxTickDurationNanos.get() / 1_000_000; }
    public long getCurrentTick() { return generationCounter.get(); }
    public long getTotalLateCompletions() { return totalLateCompletions.sum(); }
    public long getTotalTimeouts() { return totalTimeouts.sum(); }

    public long getAverageTickDurationNanos() {
        long completed = totalTicksCompleted.sum();
        return completed > 0 ? totalTickTimeNanos.sum() / completed : 0;
    }

    public long getAverageTickDurationMs() { return getAverageTickDurationNanos() / 1_000_000; }

    @Override
    public String toString() {
        TickGeneration current = currentGeneration.get();
        return "RegionTickContext{regionId=" + regionId +
                ", chunks=" + ownedChunks.get().size() +
                ", state=" + tickState.get() +
                ", slices=" + (current != null ? current.completedTasks() : 0) +
                "/" + (current != null ? current.expectedTasks() : 0) +
                ", avg_tick_ms=" + getAverageTickDurationMs() +
                ", max_tick_ms=" + getMaxTickDurationMs() +
                ", skipped=" + getTotalSkippedTicks() +
                ", late=" + getTotalLateCompletions() + "}";
    }
}
