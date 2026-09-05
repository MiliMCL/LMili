package fun.bm.mili.lmili.runtime.tps;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.optimizations.TPSStabilityConfig;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tick 监控钩子 —— 在 tick 开始和结束时记录时间，
 * 并将耗时报告给 {@link TPSStabilizer}。
 *
 * <p>此钩子需要在服务器 tick 循环的关键点调用：
 * <ul>
 *   <li>{@link #onTickStart()} - tick 开始时调用</li>
 *   <li>{@link #onTickEnd()} - tick 结束时调用</li>
 *   <li>{@link #onRegionTickStart(long)} - region tick 开始时调用</li>
 *   <li>{@link #onRegionTickEnd(long)} - region tick 结束时调用</li>
 * </ul>
 *
 * <h3>TPS 追赶机制集成</h3>
 * <p>在 tick 结束时，根据 {@link TPSStabilizer#getRecommendedTickRate()} 的建议速率
 * 动态调整 tick 循环的执行间隔，实现 TPS 追赶。
 *
 * @since 2.0.0
 */
public final class TickMonitorHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile TickMonitorHook instance;

    /** 当前 tick 开始时间 */
    private long currentTickStartTime;

    /** 当前 region tick 开始时间（按 region ID） */
    private final ConcurrentHashMap<Long, Long> regionTickStartTimes = new ConcurrentHashMap<>();

    /**
     * P1-1 / C3：region 销毁时清理 regionTickStartTimes entry。
     */
    public void onRegionDestroyed(long regionId) {
        this.regionTickStartTimes.remove(regionId);
    }

    /** 上一次 tick 耗时 */
    private volatile long lastTickDurationNanos;

    /** 监控状态 */
    private final AtomicBoolean monitoring = new AtomicBoolean(false);

    /** TPS 追赶：累计需要补偿的时间（纳秒） */
    private volatile long catchupDebtNanos = 0;

    /** 上次追赶调整时间 */
    private volatile long lastCatchupAdjustment = System.nanoTime();

    /** 追赶tick 目标速率（纳秒/tick），动态调整 */
    private volatile long catchupTargetNanos = TPSStabilizer.TARGET_TICK_NANOS;

    private TickMonitorHook() {}

    /**
     * 获取监控钩子实例。
     *
     * @return 监控钩子
     */
    public static TickMonitorHook getInstance() {
        if (instance == null) {
            synchronized (TickMonitorHook.class) {
                if (instance == null) {
                    instance = new TickMonitorHook();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化监控钩子。
     */
    public void initialize() {
        if (!TPSStabilityConfig.enabled) {
            LOGGER.info("[TickMonitorHook] TPS 稳定器已禁用，跳过初始化");
            return;
        }
        monitoring.set(true);
        TPSStabilizer.getInstance().initialize();
        LOGGER.info("[TickMonitorHook] 已初始化，PI 控制器已就绪");
    }

    /**
     * Tick 开始时调用。
     */
    public void onTickStart() {
        if (!monitoring.get()) return;
        currentTickStartTime = System.nanoTime();
    }

    /**
     * Tick 结束时调用。
     */
    public void onTickEnd() {
        if (!monitoring.get()) return;
        long duration = System.nanoTime() - currentTickStartTime;
        lastTickDurationNanos = duration;

        // 报告给 TPS 稳定器（PI 控制器更新）
        TPSStabilizer.getInstance().recordTickDuration(duration);

        // 更新追赶目标
        updateCatchupTarget(duration);

        // 检查是否超过目标时间
        long targetNanos = TPSStabilizer.TARGET_TICK_NANOS;
        if (duration > targetNanos) {
            double actualTps = 1_000_000_000.0 / duration;
            LOGGER.debug("[TickMonitorHook] Tick 超时: {}ms (目标 {}ms, 实际 TPS {:.2f})",
                    duration / 1_000_000, targetNanos / 1_000_000, actualTps);
        }
    }

    /**
     * 更新追赶目标速率。
     *
     * <p>当 TPS 低于目标时，累积追赶债务，并在后续 tick 中逐步偿还。
     *
     * @param actualDurationNanos 实际 tick 耗时
     */
    private void updateCatchupTarget(long actualDurationNanos) {
        TPSStabilizer stabilizer = TPSStabilizer.getInstance();
        TPSStabilizer.CatchupMode mode = stabilizer.getCatchupMode();

        // 计算追赶目标：如果 TPS 低于目标，缩短 tick 间隔
        double recommendedRate = stabilizer.getRecommendedTickRate();

        // 累积债务：如果实际耗时超过目标，债务增加
        long overshoot = actualDurationNanos - TPSStabilizer.TARGET_TICK_NANOS;
        if (overshoot > 0) {
            catchupDebtNanos += overshoot;
        } else {
            // 偿还债务
            catchupDebtNanos = Math.max(0, catchupDebtNanos + overshoot);
        }

        // 限制债务上限（防止过度追赶）
        long maxDebt = TPSStabilizer.TARGET_TICK_NANOS * 5; // 最多累积 5 个 tick 的债务
        catchupDebtNanos = Math.min(catchupDebtNanos, maxDebt);

        // 根据追赶模式调整目标 tick 间隔
        long newTarget = switch (mode) {
            case IDLE -> TPSStabilizer.TARGET_TICK_NANOS;
            case LIGHT_CATCHUP -> (long) (TPSStabilizer.TARGET_TICK_NANOS * 0.98);
            case MODERATE_CATCHUP -> (long) (TPSStabilizer.TARGET_TICK_NANOS * 0.95);
            case EMERGENCY_CATCHUP -> (long) (TPSStabilizer.TARGET_TICK_NANOS * 0.90);
        };

        // 根据 PI 输出微调
        double piOutput = stabilizer.getPIOutput();
        if (piOutput > 0) {
            // PI 输出为正 = TPS 过低 = 需要加速
            newTarget = (long) (newTarget * (1.0 - piOutput * 0.02));
        }

        // 确保不低于最小间隔（防止 CPU 过载）
        newTarget = Math.max(TPSStabilizer.TARGET_TICK_NANOS / 2, newTarget);

        // 平滑过渡：逐步调整
        long oldTarget = catchupTargetNanos;
        catchupTargetNanos = (oldTarget * 3 + newTarget) / 4; // 25% 新值 + 75% 旧值

        lastCatchupAdjustment = System.nanoTime();
    }

    /**
     * 获取建议的 tick 等待时间。
     *
     * <p>用于 tick 循环中计算需要等待的时间：
     * <pre>
     *   long waitNanos = TickMonitorHook.getInstance().getRecommendedWaitNanos();
     *   if (waitNanos > 0) {
     *       LockSupport.parkNanos(waitNanos);
     *   }
     * </pre>
     *
     * @return 建议等待时间（纳秒），0 表示不需要等待
     */
    public long getRecommendedWaitNanos() {
        if (!monitoring.get()) return 0;

        long elapsed = System.nanoTime() - currentTickStartTime;
        long target = catchupTargetNanos;
        long wait = target - elapsed;

        return Math.max(0, wait);
    }

    /**
     * 获取追赶债务（纳秒）。
     *
     * @return 当前追赶债务
     */
    public long getCatchupDebtNanos() {
        return catchupDebtNanos;
    }

    /**
     * 获取追赶债务（毫秒）。
     *
     * @return 当前追赶债务（毫秒）
     */
    public double getCatchupDebtMs() {
        return catchupDebtNanos / 1_000_000.0;
    }

    /**
     * Region tick 开始时调用。
     *
     * @param regionId region ID
     */
    public void onRegionTickStart(long regionId) {
        if (!monitoring.get()) return;
        regionTickStartTimes.put(regionId, System.nanoTime());
    }

    /**
     * Region tick 结束时调用。
     *
     * @param regionId region ID
     * @return region tick 耗时（纳秒），如果未找到开始时间则返回 -1
     */
    public long onRegionTickEnd(long regionId) {
        if (!monitoring.get()) return -1;
        Long startTime = regionTickStartTimes.remove(regionId);
        if (startTime == null) return -1;

        long duration = System.nanoTime() - startTime;

        // 检查 region tick 是否超过预算
        long budgetNanos = TPSStabilizer.getInstance().getCurrentTickBudgetNanos();
        if (duration > budgetNanos) {
            LOGGER.warn("[TickMonitorHook] Region #{} tick 超预算: {}ms (预算 {}ms)",
                    regionId, duration / 1_000_000, budgetNanos / 1_000_000);
        }

        return duration;
    }

    /**
     * 获取上一次 tick 耗时。
     *
     * @return 上一次 tick 耗时（纳秒）
     */
    public long getLastTickDurationNanos() {
        return lastTickDurationNanos;
    }

    /**
     * 获取上一次 tick 耗时（毫秒）。
     *
     * @return 上一次 tick 耗时（毫秒）
     */
    public double getLastTickDurationMs() {
        return lastTickDurationNanos / 1_000_000.0;
    }

    /**
     * 检查当前是否正在监控。
     *
     * @return true 如果正在监控
     */
    public boolean isMonitoring() {
        return monitoring.get();
    }

    /**
     * 设置监控状态。
     *
     * @param monitoring true 启用监控，false 禁用
     */
    public void setMonitoring(boolean monitoring) {
        this.monitoring.set(monitoring);
    }

    /**
     * 获取当前降级等级。
     *
     * @return 降级等级
     */
    public TPSStabilizer.DegradeLevel getDegradeLevel() {
        return TPSStabilizer.getInstance().getCurrentLevel();
    }

    /**
     * 获取当前追赶模式。
     *
     * @return 追赶模式
     */
    public TPSStabilizer.CatchupMode getCatchupMode() {
        return TPSStabilizer.getInstance().getCatchupMode();
    }

    /**
     * 重置监控钩子。
     */
    public void reset() {
        currentTickStartTime = 0;
        regionTickStartTimes.clear();
        lastTickDurationNanos = 0;
        monitoring.set(false);
        catchupDebtNanos = 0;
        lastCatchupAdjustment = System.nanoTime();
        catchupTargetNanos = TPSStabilizer.TARGET_TICK_NANOS;
    }
}
