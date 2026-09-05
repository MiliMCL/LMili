package fun.bm.mili.lmili.runtime.control;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.policy.BudgetPolicy;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.PerformanceSnapshot;
import fun.bm.mili.lmili.thread.scheduler.MiliSchedulerHolder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Scheduler 控制入口 —— 只读指标 + 预算策略翻译（ARCHITECTURE_AdaptiveRuntime.md §3.3）。
 *
 * <p>接入现有：{@link MiliScheduler}（经 {@link MiliSchedulerHolder}，晚绑定）。
 * <strong>首选零改动 MiliSchedulerImpl</strong>（§5.2）：本控制器不做任何内部队列改动，
 * 预算策略翻译为 per-region 提交闸门参数下发给 TickController（§5.3 闸门方案）。
 */
public final class SchedulerController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AtomicReference<MiliScheduler> schedulerRef = new AtomicReference<>(null);
    private final AtomicReference<BudgetPolicy> lastBudgetPolicy = new AtomicReference<>(BudgetPolicy.DEFAULTS);
    /** 预算策略转发（runtime 装配时指向 TickController） */
    private volatile Consumer<BudgetPolicy> budgetForwarder = null;
    /** 手动 hold（regionId → heldUntilEpochMillis；仅诊断/测试） */
    private final ConcurrentHashMap<Long, Long> heldRegions = new ConcurrentHashMap<>();
    private final AtomicLong snapshotFailures = new AtomicLong();

    /**
     * P1-1 / C3：region 销毁时清理 heldRegions entry。
     */
    public void onRegionDestroyed(long regionId) {
        this.heldRegions.remove(regionId);
    }

    /** 装配期绑定共享 scheduler（MiliRuntimeHolder 装配路径调用；晚绑定，null 安全） */
    public void bind(MiliScheduler scheduler) {
        if (scheduler != null) {
            schedulerRef.set(scheduler);
        }
    }

    /** 只读性能快照（null = scheduler 未就绪/不可用） */
    public PerformanceSnapshot schedulerSnapshot() {
        final MiliScheduler s = schedulerRef.get();
        if (s == null) {
            return null;
        }
        try {
            return s.performanceSnapshot();
        } catch (Throwable t) {
            snapshotFailures.incrementAndGet();
            LOGGER.warn("[SchedulerController] performanceSnapshot failed", t);
            return null;
        }
    }

    public long totalSubmittedTasks() {
        final PerformanceSnapshot snap = schedulerSnapshot();
        return snap != null ? snap.totalSubmittedTasks() : 0;
    }

    public int carrierThreads() {
        final PerformanceSnapshot snap = schedulerSnapshot();
        return snap != null ? snap.carrierThreadCount() : 0;
    }

    /** 调度器负载百分比（active/carrier × 100；null → 0） */
    public int schedulerLoadPct() {
        final PerformanceSnapshot snap = schedulerSnapshot();
        if (snap == null || snap.carrierThreadCount() <= 0) {
            return 0;
        }
        return (int) Math.min(100, (snap.activeTaskCount() * 100L) / snap.carrierThreadCount());
    }

    public long pendingTasks() {
        final PerformanceSnapshot snap = schedulerSnapshot();
        return snap != null ? snap.pendingTaskCount() : 0;
    }

    public int activeRegionCount() {
        final PerformanceSnapshot snap = schedulerSnapshot();
        return snap != null ? snap.activeRegionCount() : 0;
    }

    /**
     * 应用 CPU 预算策略（仅 PolicyController 编排调用）。
     * 实现：翻译为 per-region 提交闸门参数（TickController），不直接改 MiliSchedulerImpl（§5.2）。
     */
    public void applyBudgetPolicy(BudgetPolicy policy) {
        if (policy == null) {
            return;
        }
        lastBudgetPolicy.set(policy);
        final Consumer<BudgetPolicy> forwarder = budgetForwarder;
        if (forwarder != null) {
            try {
                forwarder.accept(policy);
            } catch (Throwable t) {
                LOGGER.warn("[SchedulerController] budget forward failed", t);
            }
        }
        LOGGER.info("[SchedulerController] BudgetPolicy applied: {}", policy);
    }

    /**
     * 动态 worker 数（§8.3 已知限制 1：与 work-stealing 的交互需压测确认；MiliSchedulerImpl
     * 未暴露 resize 扩展点 → 默认 no-op，仅记录）。
     */
    public void setWorkers(int n) {
        LOGGER.info("[SchedulerController] setWorkers({}) requested but dynamic worker resize is not wired (default no-op, §8.3)", n);
    }

    /** hold 一个 region 的调度（bypass 用；注册 holdRegion 回调由集成方接线） */
    public void holdRegion(long regionId, Duration duration) {
        heldRegions.put(regionId, System.currentTimeMillis() + duration.toMillis());
    }

    public void releaseRegion(long regionId) {
        heldRegions.remove(regionId);
    }

    public boolean isHeld(long regionId) {
        final Long until = heldRegions.get(regionId);
        return until != null && until > System.currentTimeMillis();
    }

    public void trackRegion(long regionId) {
        // 现有 MiliSchedulerImpl 内部已跟踪活跃 region；此处保留扩展位
    }

    public void untrackRegion(long regionId) {
        heldRegions.remove(regionId);
    }

    /** 关闭 scheduler（MiliRuntime.shutdown step 6；幂等） */
    public void shutdown(Duration timeout) {
        final MiliScheduler s = schedulerRef.get();
        if (s == null || s.isShutdown()) {
            return;
        }
        try {
            LOGGER.info("[SchedulerController] Shutting down MiliScheduler (timeout={})", timeout);
            s.shutdown(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[SchedulerController] Scheduler shutdown interrupted", e);
        }
    }

    /** 预算策略转发接线（MiliRuntime 装配：指向 TickController::applyBudgetPolicy） */
    public void setBudgetForwarder(Consumer<BudgetPolicy> forwarder) {
        this.budgetForwarder = forwarder;
    }

    public BudgetPolicy lastBudgetPolicy() {
        return lastBudgetPolicy.get();
    }
}
