package fun.bm.mili.lmili.runtime.control;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.budget.BudgetAllocator;
import fun.bm.mili.lmili.runtime.budget.BudgetLease;
import fun.bm.mili.lmili.runtime.budget.RegionTickBudget;
import fun.bm.mili.lmili.runtime.budget.TickBudgetConfig;
import fun.bm.mili.lmili.runtime.policy.BudgetPolicy;
import fun.bm.mili.lmili.runtime.policy.PressureState;
import fun.bm.mili.lmili.runtime.task.TickTaskType;
import fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Tick 治理入口 —— 预算闸门 + TPS 治理（ARCHITECTURE_AdaptiveRuntime.md §3.4 / §5.3）。
 *
 * <p><strong>闸门语义（D-10 / §6.3 R1）</strong>：tryAcquire 失败<strong>不阻塞</strong>；
 * MiliTickRegionScheduler 侧退避 5ms→50ms 重试，连续被拒 3 次走 soft 窗口（aging 防饥饿）。
 *
 * <p><strong>TPS 治理（D-14）</strong>：只写 {@link MiliTickRegionScheduler#tpsTarget} 字段；
 * <strong>禁止写 TIME_BETWEEN_TICKS</strong>（沿用 AdaptiveTPSManager 废弃注释的教训）。
 */
public final class TickController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final BudgetAllocator allocator = new BudgetAllocator();
    private final AtomicBoolean parallelTickEnabled = new AtomicBoolean(true);
    private final AtomicBoolean reserveClosed = new AtomicBoolean(false);

    /** 压力状态提供者（MiliRuntime 装配期接线：policy.snapshot().pressure()） */
    private volatile Supplier<PressureState> pressureProvider = () -> PressureState.NORMAL;

    // ---- 统计 ----
    private final LongAdder budgetRejections = new LongAdder();
    private final LongAdder agingPromotions = new LongAdder();
    private final LongAdder tickCount = new LongAdder();
    private final LongAdder tickNanosTotal = new LongAdder();
    private final AtomicLong maxTickNanos = new AtomicLong(0);

    /**
     * 预算获取（2 参：按类型默认估算）。null = 预算不足（非阻塞）。
     */
    public BudgetLease tryAcquire(long regionId, TickTaskType type) {
        return tryAcquire(regionId, type, allocator.config().defaultEstimateNanos(type));
    }

    /**
     * 预算获取（3 参：显式估算）。null = 预算不足（非阻塞，调用方必须退避/aging，§6.3 R1）。
     */
    public BudgetLease tryAcquire(long regionId, TickTaskType type, long estimatedNanos) {
        final PressureState state = pressureProvider.get();
        if (state == PressureState.SHUTDOWN) {
            return null; // 关闭编排中：不接受新预算（R2'）
        }
        final RegionTickBudget budget = allocator.allocate(regionId, state);
        if (budget.tryAcquire(type, estimatedNanos)) {
            tickCount.increment();
            tickNanosTotal.add(estimatedNanos);
            updateMax(estimatedNanos);
            return new BudgetLease(budget, estimatedNanos);
        }
        budgetRejections.increment();
        return null;
    }

    /**
     * soft 窗口获取（仅 ENTITY 且 NORMAL 态；aging：连续被拒 3 次后由调度器调用）。
     */
    public BudgetLease tryAcquireSoftWindow(long regionId, TickTaskType type) {
        if (type != TickTaskType.ENTITY) {
            return null;
        }
        final PressureState state = pressureProvider.get();
        if (state != PressureState.NORMAL) {
            return null;
        }
        final RegionTickBudget budget = allocator.allocate(regionId, state);
        final long nanos = allocator.config().defaultEstimateNanos(type);
        if (budget.tryAcquireSoftWindow(type, nanos)) {
            agingPromotions.increment();
            return new BudgetLease(budget, nanos);
        }
        budgetRejections.increment();
        return null;
    }

    /** 预算剩余（诊断） */
    public long remainingNanos(long regionId) {
        return allocator.allocate(regionId, pressureProvider.get()).remaining();
    }

    /** 预算释放（region 注销） */
    public void releaseRegionBudget(long regionId) {
        allocator.release(regionId);
    }

    // ================= 并行开关 =================

    public void setParallelTickEnabled(boolean on) {
        parallelTickEnabled.set(on);
        LOGGER.info("[TickController] parallel tick {}", on ? "enabled" : "disabled");
    }

    public boolean isParallelTickEnabled() {
        return parallelTickEnabled.get();
    }

    // ================= TPS 治理（D-14：只写 tpsTarget，禁止写 TIME_BETWEEN_TICKS） =================

    /** 写 TPS 治理目标（钳制 [10, 20]，§6.1 值域） */
    public void applyTpsTarget(double tps) {
        final double clamped = Math.max(10.0, Math.min(20.0, tps));
        MiliTickRegionScheduler.tpsTarget = clamped;
        LOGGER.info("[TickController] TPS target -> {}", clamped);
    }

    public double tpsTarget() {
        return MiliTickRegionScheduler.tpsTarget;
    }

    // ================= 压力态辅助 =================

    /** 压力态策略：关闭 reserve 池（CPU_PRESSURE 动作经 PolicyController 调用） */
    public void setReserveClosed(boolean closed) {
        reserveClosed.set(closed);
        allocator.setReserveClosed(closed);
    }

    public boolean isReserveClosed() {
        return reserveClosed.get();
    }

    /** background 任务因子（0.0~1.0；CPU_PRESSURE 下 0.5） */
    private final AtomicLong backgroundTaskFactorBits = new AtomicLong(Double.doubleToLongBits(1.0));

    public void setBackgroundTaskFactor(double factor) {
        backgroundTaskFactorBits.set(Double.doubleToLongBits(Math.max(0.0, Math.min(1.0, factor))));
    }

    public double backgroundTaskFactor() {
        return Double.longBitsToDouble(backgroundTaskFactorBits.get());
    }

    /** 预算策略下发（SchedulerController 转发；受控通道） */
    public void applyBudgetPolicy(BudgetPolicy policy) {
        if (policy != null) {
            allocator.applyPolicy(policy);
        }
    }

    /** 配置覆盖（测试/调优） */
    public void setBudgetConfig(TickBudgetConfig config) {
        allocator.setConfig(config);
    }

    public TickBudgetConfig budgetConfig() {
        return allocator.config();
    }

    /** region 类型提供者（集成：从 RegionLoadMonitor 折算；测试可注入） */
    public void setRegionTypeProvider(java.util.function.LongFunction<fun.bm.mili.lmili.runtime.io.RegionLoadSnapshot.RegionType> provider) {
        allocator.setRegionTypeProvider(provider);
    }

    /** 压力状态提供者接线（仅 MiliRuntime 装配期） */
    public void wirePressureProvider(Supplier<PressureState> provider) {
        if (provider != null) {
            this.pressureProvider = provider;
        }
    }

    /** 统计快照（供面板） */
    public TickStatsSnapshot snapshot() {
        final long ticks = tickCount.sum();
        final long avg = ticks > 0 ? tickNanosTotal.sum() / ticks : 0;
        return new TickStatsSnapshot(ticks, avg, maxTickNanos.get(), budgetRejections.sum(), agingPromotions.sum(),
                MiliTickRegionScheduler.tpsTarget, parallelTickEnabled.get());
    }

    private void updateMax(long nanos) {
        long cur;
        do {
            cur = maxTickNanos.get();
            if (cur >= nanos) {
                break;
            }
        } while (!maxTickNanos.compareAndSet(cur, nanos));
    }
}
