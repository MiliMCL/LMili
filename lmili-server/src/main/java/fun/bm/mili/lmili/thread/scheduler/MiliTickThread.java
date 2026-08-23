package fun.bm.mili.lmili.thread.scheduler;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mili 调度器的 TickThread 实现 —— 替代 Folia 的 TickThreadRunner。
 *
 * <p>此类扩展 {@link TickThread}，使 Mili 调度器的 worker 线程能够被
 * Folia 的线程安全检查（{@code TickThread.isTickThreadFor}）正确识别。
 *
 * <p>每个 worker 线程在执行 region tick 任务时，会设置当前 region 的上下文信息：
 * <ul>
 *   <li>{@link #currentTickingRegion} —— 当前正在 tick 的 region</li>
 *   <li>{@link #currentTickingWorldRegionizedData} —— 当前 region 的世界数据</li>
 *   <li>{@link #ownedRegionId} —— 当前 worker 通过 ExecutionToken 真实拥有的 regionId（RISK-01）</li>
 *   <li>{@link #ownedTokenGeneration} —— token 的 generation，用于防 stale token 被复用</li>
 * </ul>
 *
 * <p>这些字段替代了 Folia 的 {@code TickThreadRunner} 中的同名字段，
 * 使 {@code TickRegionScheduler.getCurrentRegion()} 等静态方法能够正确返回 region 信息。
 *
 * <h3>线程安全</h3>
 * <p>每个 MiliTickThread 实例只在其自己的线程上运行，字段访问无需同步。
 *
 * <h3>RISK-01 / RISK-07 修复</h3>
 * <p>TickThread 身份不能等同于 region ownership。Folia 的 isTickThreadFor() 只检查线程类型，
 * 不会验证 regionId 归属。本类提供 {@link #ownsRegion(long)} 与 {@link #verifyRegionOwnership(long)}
 * 两个方法，必须同时满足：
 * <pre>
 *   Thread instanceof MiliTickThread  ✓
 *   ownedRegionId == expectedRegionId ✓
 *   ownedTokenGeneration != 0          ✓
 * </pre>
 * 才能认为"当前线程拥有该 region"，避免 TickThread 身份伪造绕过 region 串行约束。
 */
public class MiliTickThread extends TickThread {

    /**
     * 当前正在 tick 的 region。
     * <p>在 region tick 开始时设置，tick 结束时清除。</p>
     */
    @Nullable
    public ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentTickingRegion;

    /**
     * 当前正在 tick 的 region 的世界数据。
     * <p>在 region tick 开始时设置，tick 结束时清除。</p>
     */
    @Nullable
    public RegionizedWorldData currentTickingWorldRegionizedData;

    /**
     * RISK-01 修复：当前 worker 通过 ExecutionToken 真实持有的 regionId。
     *
     * <p>只有当 worker 调用 {@code RegionState.tryAcquireExecution(workerId)} 成功并获得
     * token 后，才会设置该字段。{@link #clearRegionOwnership()} 时清除。
     * 任意线程设置此字段前，必须确保已持有真实的 ExecutionToken。</p>
     *
     * <p>值 0 表示未持有任何 region 的执行权（默认初始状态）。</p>
     */
    private final AtomicLong ownedRegionId = new AtomicLong(0L);

    /**
     * RISK-01 修复：token 的 generation。
     *
     * <p>与 {@link #ownedRegionId} 配套使用。每次成功 acquire 一个 ExecutionToken 时
     * 生成新值；防止 stale token 被同一 worker 错误地再次验证通过（即使 token 已被释放）。</p>
     */
    private final AtomicLong ownedTokenGeneration = new AtomicLong(0L);

    /**
     * 创建 MiliTickThread。
     *
     * @param run  要执行的任务
     * @param name 线程名称
     */
    public MiliTickThread(final Runnable run, final String name) {
        super(null, run, name);
        this.setDaemon(true);
    }

    /**
     * 设置当前线程的 region 上下文。
     *
     * @param region     当前 region（null 表示清除）
     * @param worldData  当前 region 的世界数据（null 表示清除）
     */
    public void setTickingRegion(
            @Nullable final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
            @Nullable final RegionizedWorldData worldData) {
        this.currentTickingRegion = region;
        this.currentTickingWorldRegionizedData = worldData;
    }

    /**
     * 清除当前线程的 region 上下文。
     */
    public void clearTickingRegion() {
        this.currentTickingRegion = null;
        this.currentTickingWorldRegionizedData = null;
    }

    /**
     * RISK-01 修复：记录 region ownership（必须配合真实的 ExecutionToken）。
     *
     * <p>仅当 caller 已经通过 {@code RegionState.tryAcquireExecution(workerId)} 获得
     * ExecutionToken 后才能调用。{@code generation} 来自 token，用于防止 stale token。</p>
     */
    public void setRegionOwnership(final long regionId, final long generation) {
        this.ownedRegionId.set(regionId);
        this.ownedTokenGeneration.set(generation);
    }

    /**
     * RISK-01 修复：清除 region ownership。
     *
     * <p>对应 token 释放。调用后 ownedRegionId==0 表示不持有任何 region。</p>
     */
    public void clearRegionOwnership() {
        this.ownedRegionId.set(0L);
        this.ownedTokenGeneration.set(0L);
    }

    /**
     * RISK-01 / RISK-07 修复：检查当前线程是否真正拥有指定 region 的执行权。
     *
     * <p>这是 "TickThread 身份 + Region ownership" 双因素校验的核心。
     * 任何对 region 数据的安全访问都必须先调用此方法。
     *
     * <p>判定条件（必须全部成立）：
     * <ol>
     *   <li>当前线程是 {@link MiliTickThread}</li>
     *   <li>{@link #ownedRegionId} == 待校验 regionId</li>
     *   <li>{@link #ownedTokenGeneration} != 0（说明 acquire 过有效 token）</li>
     * </ol>
     *
     * @param expectedRegionId 期望校验的 regionId
     * @return true 如果当前线程真实持有该 region 的 ExecutionToken
     */
    public boolean ownsRegion(final long expectedRegionId) {
        if (!(Thread.currentThread() instanceof MiliTickThread self)) {
            return false;
        }
        // generation 必须非 0，否则表示从未持有过有效 token
        if (self.ownedTokenGeneration.get() == 0L) {
            return false;
        }
        return self.ownedRegionId.get() == expectedRegionId;
    }

    /**
     * RISK-01 / RISK-07 修复：强制校验 ownership，失败时抛 IllegalStateException。
     *
     * <p>用于 region tick 入口处的硬性检查（不通过时立即抛错而不是吞掉）：
     * <pre>
     *   miliThread.verifyRegionOwnership(task.regionId());
     * </pre>
     *
     * @throws IllegalStateException 如果当前线程未持有该 region 的合法 ExecutionToken
     */
    public void verifyRegionOwnership(final long expectedRegionId) {
        if (!ownsRegion(expectedRegionId)) {
            throw new IllegalStateException(
                    "[MiliTickThread] Ownership violation: thread " + Thread.currentThread().getName()
                            + " does not own region #" + expectedRegionId
                            + " (owned=" + ownedRegionId.get()
                            + ", generation=" + ownedTokenGeneration.get() + ")");
        }
    }

    /**
     * 获取当前线程（如果是 MiliTickThread）。
     *
     * @return 当前线程的 MiliTickThread 实例，或 null
     */
    @Nullable
    public static MiliTickThread currentOrNull() {
        final Thread thread = Thread.currentThread();
        if (thread instanceof MiliTickThread miliThread) {
            return miliThread;
        }
        return null;
    }

    /**
     * RISK-01 修复：诊断用 —— 获取当前 owned regionId（0 表示无）。
     */
    public long getOwnedRegionId() {
        return ownedRegionId.get();
    }

    /**
     * RISK-01 修复：诊断用 —— 获取当前 owned generation（0 表示无）。
     */
    public long getOwnedTokenGeneration() {
        return ownedTokenGeneration.get();
    }
}
