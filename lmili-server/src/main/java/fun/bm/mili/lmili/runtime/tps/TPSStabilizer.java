package fun.bm.mili.lmili.runtime.tps;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.budget.RegionTickBudget;
import fun.bm.mili.lmili.runtime.budget.TickBudgetConfig;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.utils.performance.TPSTracker;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TPS 稳定器 —— 确保服务器 TPS 稳定在 20。
 *
 * <h3>核心机制</h3>
 * <ol>
 *   <li><b>PI 控制器</b>：比例-积分控制实现平滑的 tick 预算调整</li>
 *   <li><b>TPS 追赶</b>：当 TPS 低于目标时，主动加速 tick 执行以追赶</li>
 *   <li><b>紧急恢复</b>：严重掉 TPS 时启动紧急恢复模式</li>
 *   <li><b>防抖恢复</b>：防止 TPS 在阈值附近振荡</li>
 *   <li><b>自适应降级</b>：根据负载动态调整非关键任务执行比例</li>
 * </ol>
 *
 * <h3>PI 控制器原理</h3>
 * <pre>
 *   error = target_tps - actual_tps
 *   integral += error * dt
 *   integral = clamp(integral, -max_integral, max_integral)  // 防积分饱和
 *   output = Kp * error + Ki * integral
 *   budget = base_budget * (1.0 - output)
 * </pre>
 *
 * <h3>TPS 追赶策略</h3>
 * <p>当 TPS 低于目标时：
 * <ul>
 *   <li><b>轻微掉速 (TPS 18-20)</b>：轻微加速 tick，减少跳过</li>
 *   <li><b>中度掉速 (TPS 15-18)</b>：适度加速，跳过非必要任务</li>
 *   <li><b>严重掉速 (TPS &lt; 15)</b>：紧急模式，最大化 tick 速率，仅执行关键操作</li>
 * </ul>
 *
 * <h3>Tick 预算分配</h3>
 * <p>目标：每个 tick 不超过 50ms（20 TPS）。</p>
 *
 * @since 2.0.0
 */
public final class TPSStabilizer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ==================== 核心常量 ====================

    /** 目标 TPS */
    public static final double TARGET_TPS = 20.0;

    /** 目标 tick 时间（50ms = 20 TPS） */
    public static final long TARGET_TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    // ==================== PI 控制器参数 ====================

    /** 比例增益 —— 控制对当前误差的响应强度 */
    private static final double KP = 0.08;

    /** 积分增益 —— 控制对累积误差的响应强度 */
    private static final double KI = 0.02;

    /** 最大积分值 —— 防止积分饱和 */
    private static final double MAX_INTEGRAL = 5.0;

    /** 最小积分值 */
    private static final double MIN_INTEGRAL = -5.0;

    // ==================== 追赶机制参数 ====================

    /** 追赶模式激活阈值 —— 低于此 TPS 启动追赶 */
    private static final double CATCHUP_ACTIVATE_TPS = 19.5;

    /** 追赶模式最大倍率 —— 追赶时 tick 速率最高提升到 */
    private static final double CATCHUP_MAX_MULTIPLIER = 1.15;

    /** 紧急恢复阈值 —— 低于此 TPS 启动紧急恢复 */
    private static final double EMERGENCY_TPS = 15.0;

    /** 紧急恢复倍率 */
    private static final double EMERGENCY_MULTIPLIER = 1.25;

    /** 恢复防抖时间 —— 降级后等待此时间才能恢复（毫秒） */
    private static final long RECOVERY_DEBOUNCE_MS = 3000;

    /** 最大连续降级次数 —— 超过此值触发紧急模式 */
    private static final int MAX_CONSECUTIVE_DEGRADES = 5;

    // ==================== 降级阈值 ====================

    /** 正常运行阈值 */
    private static final double TPS_NORMAL_THRESHOLD = 19.5;

    /** 轻度降级阈值 */
    private static final double TPS_LIGHT_DEGRADE_THRESHOLD = 18.0;

    /** 中度降级阈值 */
    private static final double TPS_MODERATE_DEGRADE_THRESHOLD = 15.0;

    // ==================== 状态变量 ====================

    /** 单例实例 */
    private static volatile TPSStabilizer instance;

    /** 运行状态 */
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /** 当前降级等级 */
    private final AtomicReference<DegradeLevel> currentLevel = new AtomicReference<>(DegradeLevel.NORMAL);

    /** 当前 tick 预算（动态调整，纳秒） */
    private final AtomicLong currentTickBudgetNanos = new AtomicLong(TARGET_TICK_NANOS);

    /** 追赶模式倍率（1.0 = 正常，>1.0 = 加速） */
    private final AtomicReference<Double> catchupMultiplier = new AtomicReference<>(1.0);

    /** PI 控制器积分项 */
    private double integralAccumulator = 0.0;

    /** 上一次 tick 时间（用于计算 dt） */
    private long lastTickTime = System.nanoTime();

    /** 上次降级时间（用于恢复防抖） */
    private volatile long lastDegradeTime = 0;

    /** 连续降级计数 */
    private final AtomicLong consecutiveDegradeCount = new AtomicLong();

    /** 历史 tick 耗时（环形缓冲区） */
    private static final int HISTORY_SIZE = 128;
    private static final int HISTORY_MASK = HISTORY_SIZE - 1;
    private final long[] tickDurations = new long[HISTORY_SIZE];
    private final AtomicLong tickIndex = new AtomicLong();

    /** 连续超时计数 */
    private final AtomicLong consecutiveOverruns = new AtomicLong();

    /** 总 tick 计数 */
    private final AtomicLong totalTicks = new AtomicLong();

    /** 降级计数 */
    private final AtomicLong degradeCount = new AtomicLong();

    /** 恢复计数 */
    private final AtomicLong recoveryCount = new AtomicLong();

    /** 追赶模式激活计数 */
    private final AtomicLong catchupActivationCount = new AtomicLong();

    /** 紧急模式激活计数 */
    private final AtomicLong emergencyActivationCount = new AtomicLong();

    /** 累计追赶 tick 数 */
    private final AtomicLong catchupTicks = new AtomicLong();

    /**
     * 降级等级枚举。
     */
    public enum DegradeLevel {
        /** 正常运行 */
        NORMAL(0, 1.0, 0),
        /** 轻度降级：减少实体 AI 和粒子 */
        LIGHT(1, 0.9, 10),
        /** 中度降级：跳过非必要 chunk tick 和音效 */
        MODERATE(2, 0.75, 25),
        /** 重度降级：仅执行关键 tick 操作 */
        SEVERE(3, 0.55, 50),
        /** 紧急模式：最大化 tick 速率 */
        EMERGENCY(4, 0.4, 80);

        /** 等级数值 */
        final int level;
        /** 预算倍率（相对于目标） */
        final double budgetMultiplier;
        /** 非关键任务跳过百分比 */
        final int skipPercentage;

        DegradeLevel(int level, double budgetMultiplier, int skipPercentage) {
            this.level = level;
            this.budgetMultiplier = budgetMultiplier;
            this.skipPercentage = skipPercentage;
        }
    }

    /**
     * 追赶模式枚举。
     */
    public enum CatchupMode {
        /** 无追赶 —— TPS 正常 */
        IDLE,
        /** 轻微追赶 —— TPS 略低于目标 */
        LIGHT_CATCHUP,
        /** 中度追赶 —— TPS 明显低于目标 */
        MODERATE_CATCHUP,
        /** 紧急追赶 —— TPS 严重低于目标 */
        EMERGENCY_CATCHUP
    }

    /** 当前追赶模式 */
    private final AtomicReference<CatchupMode> catchupMode = new AtomicReference<>(CatchupMode.IDLE);

    private TPSStabilizer() {}

    /**
     * 获取 TPS 稳定器实例。
     *
     * @return TPS 稳定器
     */
    public static TPSStabilizer getInstance() {
        if (instance == null) {
            synchronized (TPSStabilizer.class) {
                if (instance == null) {
                    instance = new TPSStabilizer();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 TPS 稳定器。
     */
    public void initialize() {
        if (!enabled.get()) {
            LOGGER.warn("[TPSStabilizer] 已禁用，跳过初始化");
            return;
        }
        LOGGER.info("[TPSStabilizer] 已初始化，目标 TPS={}, KP={}, KI={}", TARGET_TPS, KP, KI);
    }

    /**
     * 记录 tick 耗时并更新 PI 控制器。
     *
     * @param tickDurationNanos tick 耗时（纳秒）
     */
    public void recordTickDuration(long tickDurationNanos) {
        if (!enabled.get()) return;

        // 记录到历史
        long idx = tickIndex.getAndIncrement();
        tickDurations[(int) (idx & HISTORY_MASK)] = tickDurationNanos;
        totalTicks.incrementAndGet();

        // 更新 PI 控制器
        updatePIController(tickDurationNanos);

        // 更新降级等级（带防抖）
        updateDegradeLevel();

        // 更新追赶模式
        updateCatchupMode();

        // 计算最终 tick 预算
        recalculateTickBudget();

        // 记录连续超时
        if (tickDurationNanos > TARGET_TICK_NANOS) {
            consecutiveOverruns.incrementAndGet();
        } else {
            consecutiveOverruns.set(0);
        }
    }

    /**
     * 更新 PI 控制器。
     *
     * <p>PID 控制器的离散形式：
     * <pre>
     *   error = target - actual
     *   integral += error * dt
     *   integral = clamp(integral, min, max)  // 防饱和
     *   output = Kp * error + Ki * integral
     * </pre>
     */
    private void updatePIController(long tickDurationNanos) {
        long now = System.nanoTime();
        double dt = (now - lastTickTime) / 1_000_000_000.0; // 转换为秒
        lastTickTime = now;

        // 防止 dt 过大（服务器可能暂停过）
        if (dt <= 0 || dt > 1.0) {
            dt = 0.05; // 默认 50ms
        }

        // 计算当前 TPS
        double actualTps = 1_000_000_000.0 / Math.max(tickDurationNanos, 1);
        actualTps = Math.min(actualTps, 200.0); // 限制最大值

        // 计算误差（正误差 = TPS 过低，需要加速）
        double error = TARGET_TPS - actualTps;

        // 更新积分项（带防饱和）
        integralAccumulator += error * dt;
        integralAccumulator = Math.max(MIN_INTEGRAL, Math.min(MAX_INTEGRAL, integralAccumulator));

        // 计算输出（正输出 = 需要加速）
        double output = KP * error + KI * integralAccumulator;

        // 存储当前输出供后续使用
        this.currentPIOutput = output;
    }

    /** 当前 PI 控制器输出 */
    private volatile double currentPIOutput = 0.0;

    /**
     * 获取当前 PI 控制器输出。
     *
     * @return PI 控制器输出（正值表示需要加速）
     */
    public double getPIOutput() {
        return currentPIOutput;
    }

    /**
     * 更新降级等级（带防抖）。
     */
    private void updateDegradeLevel() {
        double tps = TPSTracker.getTPS();
        long now = System.currentTimeMillis();
        DegradeLevel oldLevel = currentLevel.get();
        DegradeLevel newLevel;

        // 确定目标等级
        if (tps >= TPS_NORMAL_THRESHOLD) {
            newLevel = DegradeLevel.NORMAL;
        } else if (tps >= TPS_LIGHT_DEGRADE_THRESHOLD) {
            newLevel = DegradeLevel.LIGHT;
        } else if (tps >= TPS_MODERATE_DEGRADE_THRESHOLD) {
            newLevel = DegradeLevel.MODERATE;
        } else if (tps >= EMERGENCY_TPS) {
            newLevel = DegradeLevel.SEVERE;
        } else {
            newLevel = DegradeLevel.EMERGENCY;
        }

        // 降级：立即执行
        if (newLevel.level > oldLevel.level) {
            // 检查连续降级次数
            long consecutive = consecutiveDegradeCount.incrementAndGet();
            if (consecutive >= MAX_CONSECUTIVE_DEGRADES) {
                newLevel = DegradeLevel.EMERGENCY;
            }

            currentLevel.set(newLevel);
            lastDegradeTime = now;
            degradeCount.incrementAndGet();
            LOGGER.warn("[TPSStabilizer] 降级: {} -> {}, TPS={}, 连续降级={}",
                    oldLevel, newLevel, String.format("%.2f", tps), consecutive);

            // 紧急模式计数
            if (newLevel == DegradeLevel.EMERGENCY) {
                emergencyActivationCount.incrementAndGet();
            }
        }
        // 恢复：带防抖延迟
        else if (newLevel.level < oldLevel.level) {
            // 必须等待足够时间才能恢复
            if (now - lastDegradeTime >= RECOVERY_DEBOUNCE_MS) {
                // 渐进式恢复：每次只恢复一级
                DegradeLevel gradualRecovery = DegradeLevel.values()[oldLevel.level - 1];
                if (newLevel.level <= gradualRecovery.level) {
                    currentLevel.set(gradualRecovery);
                    recoveryCount.incrementAndGet();
                    consecutiveDegradeCount.set(0); // 重置连续降级计数
                    LOGGER.info("[TPSStabilizer] 恢复: {} -> {}, TPS={}",
                            oldLevel, gradualRecovery, String.format("%.2f", tps));
                }
            }
        }
    }

    /**
     * 更新追赶模式。
     */
    private void updateCatchupMode() {
        double tps = TPSTracker.getTPS();
        CatchupMode newMode;

        if (tps >= TARGET_TPS) {
            newMode = CatchupMode.IDLE;
        } else if (tps >= CATCHUP_ACTIVATE_TPS) {
            newMode = CatchupMode.LIGHT_CATCHUP;
        } else if (tps >= EMERGENCY_TPS) {
            newMode = CatchupMode.MODERATE_CATCHUP;
        } else {
            newMode = CatchupMode.EMERGENCY_CATCHUP;
        }

        CatchupMode oldMode = catchupMode.getAndSet(newMode);
        if (oldMode != newMode && newMode != CatchupMode.IDLE) {
            catchupActivationCount.incrementAndGet();
        }
    }

    /**
     * 重新计算 tick 预算。
     *
     * <p>最终预算由以下因素共同决定：
     * <ul>
     *   <li>降级等级基础倍率</li>
     *   <li>PI 控制器输出（误差修正）</li>
     *   <li>追赶模式倍率</li>
     * </ul>
     */
    private void recalculateTickBudget() {
        DegradeLevel level = currentLevel.get();
        CatchupMode mode = catchupMode.get();

        // 基础预算（基于降级等级）
        double baseMultiplier = level.budgetMultiplier;

        // PI 控制器修正（正输出 = 需要加速 = 减少预算）
        double piCorrection = 1.0 - (currentPIOutput * 0.05);
        piCorrection = Math.max(0.5, Math.min(1.2, piCorrection));

        // 追赶倍率
        double catchModeMultiplier = switch (mode) {
            case IDLE -> 1.0;
            case LIGHT_CATCHUP -> 1.05;   // 轻微加速
            case MODERATE_CATCHUP -> 1.1;  // 中度加速
            case EMERGENCY_CATCHUP -> CATCHUP_MAX_MULTIPLIER; // 最大加速
        };

        // 最终预算倍率 = 基础 * PI修正 * 追赶倍率
        double finalMultiplier = baseMultiplier * piCorrection * catchModeMultiplier;
        finalMultiplier = Math.max(0.3, Math.min(1.3, finalMultiplier));

        // 计算最终预算（纳秒）
        long newBudget = (long) (TARGET_TICK_NANOS * finalMultiplier);
        long oldBudget = currentTickBudgetNanos.getAndSet(newBudget);

        // 存储追赶倍率供查询
        catchupMultiplier.set(catchModeMultiplier);

        // 记录追赶 tick
        if (mode != CatchupMode.IDLE) {
            catchupTicks.incrementAndGet();
        }

        // 日志输出预算变化
        if (Math.abs(newBudget - oldBudget) > 1_000_000) { // 变化超过 1ms
            LOGGER.debug("[TPSStabilizer] 预算调整: {}ms -> {}ms (level={}, mode={}, pi={})",
                    oldBudget / 1_000_000, newBudget / 1_000_000,
                    level, mode, String.format("%.3f", currentPIOutput));
        }
    }

    /**
     * 获取当前 tick 预算。
     *
     * @return tick 预算（纳秒）
     */
    public long getCurrentTickBudgetNanos() {
        return currentTickBudgetNanos.get();
    }

    /**
     * 获取当前降级等级。
     *
     * @return 降级等级
     */
    public DegradeLevel getCurrentLevel() {
        return currentLevel.get();
    }

    /**
     * 获取当前追赶模式。
     *
     * @return 追赶模式
     */
    public CatchupMode getCatchupMode() {
        return catchupMode.get();
    }

    /**
     * 获取追赶倍率。
     *
     * @return 追赶倍率（1.0 = 正常，>1.0 = 加速）
     */
    public double getCatchupMultiplier() {
        return catchupMultiplier.get();
    }

    /**
     * 判断是否应该跳过非必要操作。
     *
     * <p>使用随机化跳过策略，基于 skipPercentage 概率跳过。
     *
     * @param taskType 任务类型
     * @return true 如果应该跳过
     */
    public boolean shouldSkip(TaskType taskType) {
        if (!enabled.get()) return false;

        DegradeLevel level = currentLevel.get();
        CatchupMode mode = catchupMode.get();

        // 紧急模式下跳过大部分非关键操作
        if (level == DegradeLevel.EMERGENCY) {
            return switch (taskType) {
                case ENTITY_AI, CHUNK_RANDOM_TICK, BLOCK_RANDOM_TICK, PARTICLE_EFFECT -> true;
                case SOUND_EFFECT, PLUGIN_TASK, CHUNK_ENTITY_SPAWN, LIGHTING_UPDATE -> true;
            };
        }

        // 其他模式下根据等级决定
        int skipPercentage = switch (taskType) {
            case ENTITY_AI -> level.skipPercentage;
            case CHUNK_RANDOM_TICK -> Math.max(0, level.skipPercentage - 10);
            case BLOCK_RANDOM_TICK -> Math.max(0, level.skipPercentage - 15);
            case PLUGIN_TASK -> Math.max(0, level.skipPercentage - 20);
            case PARTICLE_EFFECT -> Math.max(0, level.skipPercentage - 5);
            case SOUND_EFFECT -> Math.max(0, level.skipPercentage - 10);
            case CHUNK_ENTITY_SPAWN -> Math.max(0, level.skipPercentage - 25);
            case LIGHTING_UPDATE -> Math.max(0, level.skipPercentage - 30);
        };

        // 追赶模式下减少跳过
        if (mode == CatchupMode.LIGHT_CATCHUP) {
            skipPercentage = (int) (skipPercentage * 0.7);
        } else if (mode == CatchupMode.MODERATE_CATCHUP) {
            skipPercentage = (int) (skipPercentage * 0.5);
        } else if (mode == CatchupMode.EMERGENCY_CATCHUP) {
            skipPercentage = (int) (skipPercentage * 0.3);
        }

        // 概率跳过
        if (skipPercentage <= 0) return false;
        if (skipPercentage >= 100) return true;
        return (ThreadLocalRandom.current().nextInt(100) < skipPercentage);
    }

    /**
     * 判断是否应该限制实体数量。
     *
     * @return true 如果应该限制
     */
    public boolean shouldLimitEntities() {
        return enabled.get() && currentLevel.get().ordinal() >= DegradeLevel.MODERATE.ordinal();
    }

    /**
     * 获取实体数量限制比例。
     *
     * @return 0.0-1.0 之间的值，表示允许的实体比例
     */
    public double getEntityLimitRatio() {
        if (!enabled.get()) return 1.0;
        return switch (currentLevel.get()) {
            case NORMAL -> 1.0;
            case LIGHT -> 0.95;
            case MODERATE -> 0.8;
            case SEVERE -> 0.6;
            case EMERGENCY -> 0.4;
        };
    }

    /**
     * 获取tick 执行建议速率。
     *
     * <p>用于指导 tick 循环的执行速率：
     * <ul>
     *   <li>1.0 = 正常速率（50ms/tick）</li>
     *   <li>>1.0 = 加速（更快执行 tick）</li>
     *   <li><1.0 = 减速（更慢执行 tick）</li>
     * </ul>
     *
     * @return 建议 tick 速率倍率
     */
    public double getRecommendedTickRate() {
        if (!enabled.get()) return 1.0;

        double baseRate = catchupMultiplier.get();

        // 根据 PI 输出调整
        double piAdjustment = currentPIOutput * 0.1;
        baseRate += piAdjustment;

        // 限制范围
        return Math.max(0.5, Math.min(EMERGENCY_MULTIPLIER, baseRate));
    }

    /**
     * 获取平均 tick 耗时（纳秒）。
     *
     * @return 平均 tick 耗时
     */
    public long getAverageTickDuration() {
        long count = Math.min(tickIndex.get(), HISTORY_SIZE);
        if (count == 0) return 0;

        long sum = 0;
        for (long i = 0; i < count; i++) {
            sum += tickDurations[(int) (i & HISTORY_MASK)];
        }
        return sum / count;
    }

    /**
     * 获取移动平均 tick 耗时（最近 N 个 tick）。
     *
     * @param window 窗口大小
     * @return 移动平均 tick 耗时（纳秒）
     */
    public long getMovingAverageTickDuration(int window) {
        long count = Math.min(tickIndex.get(), Math.min(window, HISTORY_SIZE));
        if (count == 0) return 0;

        long totalIdx = tickIndex.get();
        long sum = 0;
        for (long i = totalIdx - count; i < totalIdx; i++) {
            sum += tickDurations[(int) (i & HISTORY_MASK)];
        }
        return sum / count;
    }

    /**
     * 获取 P99 tick 耗时（纳秒）。
     *
     * @return P99 tick 耗时
     */
    public long getP99TickDuration() {
        long count = Math.min(tickIndex.get(), HISTORY_SIZE);
        if (count == 0) return 0;

        long[] sorted = new long[(int) count];
        for (long i = 0; i < count; i++) {
            sorted[(int) i] = tickDurations[(int) (i & HISTORY_MASK)];
        }
        Arrays.sort(sorted);
        int p99Index = (int) (count * 0.99);
        return sorted[Math.min(p99Index, sorted.length - 1)];
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息
     */
    public Stats getStats() {
        return new Stats(
                totalTicks.get(),
                degradeCount.get(),
                recoveryCount.get(),
                consecutiveOverruns.get(),
                currentLevel.get(),
                getCurrentTickBudgetNanos(),
                getAverageTickDuration(),
                getP99TickDuration(),
                catchupMode.get(),
                catchupActivationCount.get(),
                emergencyActivationCount.get(),
                catchupTicks.get(),
                currentPIOutput,
                integralAccumulator
        );
    }

    /**
     * 启用/禁用 TPS 稳定器。
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled) {
            currentLevel.set(DegradeLevel.NORMAL);
            catchupMode.set(CatchupMode.IDLE);
            currentTickBudgetNanos.set(TARGET_TICK_NANOS);
            catchupMultiplier.set(1.0);
            integralAccumulator = 0.0;
        }
    }

    /**
     * 判断是否启用。
     *
     * @return true 如果启用
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 为 Region 创建 Tick 预算。
     *
     * @param context Region 上下文
     * @return Region tick 预算
     */
    public RegionTickBudget createRegionBudget(RegionTickContext context) {
        long budgetNanos = getCurrentTickBudgetNanos();
        TickBudgetConfig config = TickBudgetConfig.DEFAULTS.withBase(
                (long) (4_000_000L * currentLevel.get().budgetMultiplier)
        );
        return new RegionTickBudget(budgetNanos, budgetNanos, config, true);
    }

    /**
     * 重置统计信息。
     */
    public void reset() {
        tickIndex.set(0);
        totalTicks.set(0);
        degradeCount.set(0);
        recoveryCount.set(0);
        consecutiveOverruns.set(0);
        consecutiveDegradeCount.set(0);
        catchupActivationCount.set(0);
        emergencyActivationCount.set(0);
        catchupTicks.set(0);
        currentLevel.set(DegradeLevel.NORMAL);
        catchupMode.set(CatchupMode.IDLE);
        currentTickBudgetNanos.set(TARGET_TICK_NANOS);
        catchupMultiplier.set(1.0);
        integralAccumulator = 0.0;
        currentPIOutput = 0.0;
        lastDegradeTime = 0;
        lastTickTime = System.nanoTime();
    }

    /**
     * 任务类型枚举。
     */
    public enum TaskType {
        /** 实体 AI 计算 */
        ENTITY_AI,
        /** Chunk 随机 tick */
        CHUNK_RANDOM_TICK,
        /** 方块随机 tick */
        BLOCK_RANDOM_TICK,
        /** 插件任务 */
        PLUGIN_TASK,
        /** 粒子效果 */
        PARTICLE_EFFECT,
        /** 音效 */
        SOUND_EFFECT,
        /** 实体生成 */
        CHUNK_ENTITY_SPAWN,
        /** 光照更新 */
        LIGHTING_UPDATE
    }

    /**
     * 统计信息记录。
     */
    public record Stats(
            long totalTicks,
            long degradeCount,
            long recoveryCount,
            long consecutiveOverruns,
            DegradeLevel currentLevel,
            long currentBudgetNanos,
            long averageTickDuration,
            long p99TickDuration,
            CatchupMode catchupMode,
            long catchupActivations,
            long emergencyActivations,
            long catchupTicks,
            double piOutput,
            double piIntegral
    ) {
        @Override
        public String toString() {
            return String.format(
                    "TPSStats{total=%d, degrades=%d, recoveries=%d, overruns=%d, level=%s, budget=%dms, avg=%.2fms, p99=%.2fms, catchup=%s, catchupTicks=%d, emergency=%d, pi=%.3f, integral=%.3f}",
                    totalTicks, degradeCount, recoveryCount, consecutiveOverruns,
                    currentLevel, currentBudgetNanos / 1_000_000,
                    averageTickDuration / 1_000_000.0, p99TickDuration / 1_000_000.0,
                    catchupMode, catchupTicks, emergencyActivations,
                    piOutput, piIntegral
            );
        }
    }

    // 引入 ThreadLocalRandom 用于概率跳过
    private static final class ThreadLocalRandom {
        private static final java.util.concurrent.ThreadLocalRandom INSTANCE = java.util.concurrent.ThreadLocalRandom.current();

        static java.util.concurrent.ThreadLocalRandom current() {
            return INSTANCE;
        }
    }
}
