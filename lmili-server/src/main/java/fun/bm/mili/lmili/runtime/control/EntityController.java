package fun.bm.mili.lmili.runtime.control;

import fun.bm.mili.lmili.thread.regiontick.EntityTickMetrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entity Tick 的控制入口（ARCHITECTURE_AdaptiveRuntime.md §3.5）。
 *
 * <p>接入现有：EntityThrottlePolicy、EntityPriorityScheduler、EntityTickMetrics、EntitySimulator
 * （thread/runtime/entity）—— 本控制器只做策略翻译，不拥有执行逻辑。
 */
public final class EntityController {

    /** 实体节流总开关（/lmili control throttle entity on） */
    private final AtomicBoolean throttleEnabled = new AtomicBoolean(false);

    /** 每 region 实体上限（0 = 不限制） */
    private final AtomicInteger maxEntitiesPerRegion = new AtomicInteger(0);

    /** 低优先级实体削减因子（0.0~1.0；CPU_PRESSURE 时策略设为 0.5） */
    private final AtomicLong lowPriorityFactorBits = new AtomicLong(Double.doubleToLongBits(1.0));

    /** 实体 tick 预算占比（soft 的 42.5%；-1 = 自动由 BudgetAllocator 换算） */
    private final AtomicLong entityBudgetNanos = new AtomicLong(-1L);

    /** 指标（EntityTickMetrics 为现有多线程指标类，直接复用实例） */
    private final EntityTickMetrics entityMetrics = new EntityTickMetrics();

    public void setThrottleEnabled(boolean on) {
        throttleEnabled.set(on);
    }

    public boolean isThrottleEnabled() {
        return throttleEnabled.get();
    }

    /** 每 region 实体上限（钳制 [0, 100_000]；0 = 不限制） */
    public void setMaxEntitiesPerRegion(int n) {
        maxEntitiesPerRegion.set(Math.max(0, Math.min(100_000, n)));
    }

    public int maxEntitiesPerRegion() {
        return maxEntitiesPerRegion.get();
    }

    /** 低优先级实体削减因子（钳制 [0, 1]） */
    public void setLowPriorityFactor(double factor) {
        lowPriorityFactorBits.set(Double.doubleToLongBits(Math.max(0.0, Math.min(1.0, factor))));
    }

    public double lowPriorityFactor() {
        return Double.longBitsToDouble(lowPriorityFactorBits.get());
    }

    /** 实体 tick 预算（纳秒；-1 = 自动） */
    public void setEntityBudgetNanos(long nanos) {
        entityBudgetNanos.set(nanos < 0 ? -1 : Math.min(nanos, 100_000_000L));
    }

    public long entityBudgetNanos() {
        return entityBudgetNanos.get();
    }

    /** 汇报（供 MetricsController poll） */
    public EntityTickMetrics entityMetrics() {
        return entityMetrics;
    }
}
