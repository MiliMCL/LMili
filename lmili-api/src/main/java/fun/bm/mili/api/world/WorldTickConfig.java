package fun.bm.mili.api.world;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

/**
 * 世界 Tick 配置 —— 每个世界独立的性能参数。
 *
 * <p>允许为不同世界设置不同的 TPS 目标和 CPU 预算，
 * 适用于多世界服务器中对不同世界进行差异化性能配置。
 *
 * <h3>默认值</h3>
 * <ul>
 *   <li>TPS 目标: 20.0</li>
 *   <li>CPU 预算: 4.0ms (每 tick 每 region)</li>
 *   <li>并行 tick: true</li>
 *   <li>实体 tick 优先级: NORMAL</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 获取当前配置
 * WorldTickConfig config = MiliWorlds.getWorldConfig(world);
 *
 * // 修改配置
 * WorldTickConfig newConfig = config.withTpsTarget(18.0);
 * MiliWorlds.setWorldConfig(world, newConfig);
 * }</pre>
 *
 * @since 2.0.0
 */
public record WorldTickConfig(
    /** TPS 目标值 [10.0, 20.0] */
    double tpsTarget,
    /** CPU 预算 (纳秒/region/tick) */
    long cpuBudgetNanos,
    /** 是否启用并行 tick */
    boolean parallelTickEnabled,
    /** 实体 tick 优先级 */
    EntityTickPriority entityTickPriority,
    /** 最大并发任务数 */
    int maxConcurrentTasks,
    /** 任务队列深度限制 */
    int maxQueueDepth
) {
    /** 默认配置: TPS 20, 4ms 预算, 并行开启 */
    public static final WorldTickConfig DEFAULT = new WorldTickConfig(
        20.0,
        4_000_000L,
        true,
        EntityTickPriority.NORMAL,
        100,
        1000
    );

    /** 低性能配置: TPS 15, 2ms 预算, 并行关闭 */
    public static final WorldTickConfig LOW_PERFORMANCE = new WorldTickConfig(
        15.0,
        2_000_000L,
        false,
        EntityTickPriority.LOW,
        50,
        500
    );

    /** 高性能配置: TPS 20, 8ms 预算, 并行开启 */
    public static final WorldTickConfig HIGH_PERFORMANCE = new WorldTickConfig(
        20.0,
        8_000_000L,
        true,
        EntityTickPriority.HIGH,
        200,
        2000
    );

    /**
     * 创建修改 TPS 目标的新配置。
     *
     * @param newTpsTarget 新的 TPS 目标 [10.0, 20.0]
     * @return 新配置实例
     */
    @NotNull
    public WorldTickConfig withTpsTarget(double newTpsTarget) {
        double clamped = Math.max(10.0, Math.min(20.0, newTpsTarget));
        return new WorldTickConfig(clamped, cpuBudgetNanos, parallelTickEnabled,
            entityTickPriority, maxConcurrentTasks, maxQueueDepth);
    }

    /**
     * 创建修改 CPU 预算的新配置。
     *
     * @param nanos 新的 CPU 预算 (纳秒)
     * @return 新配置实例
     */
    @NotNull
    public WorldTickConfig withCpuBudgetNanos(long nanos) {
        return new WorldTickConfig(tpsTarget, Math.max(1_000_000L, nanos),
            parallelTickEnabled, entityTickPriority, maxConcurrentTasks, maxQueueDepth);
    }

    /**
     * 创建修改并行 tick 开关的新配置。
     *
     * @param enabled 是否启用并行 tick
     * @return 新配置实例
     */
    @NotNull
    public WorldTickConfig withParallelTick(boolean enabled) {
        return new WorldTickConfig(tpsTarget, cpuBudgetNanos, enabled,
            entityTickPriority, maxConcurrentTasks, maxQueueDepth);
    }

    /**
     * 创建修改实体 tick 优先级的新配置。
     *
     * @param priority 新的优先级
     * @return 新配置实例
     */
    @NotNull
    public WorldTickConfig withEntityTickPriority(@NotNull EntityTickPriority priority) {
        return new WorldTickConfig(tpsTarget, cpuBudgetNanos, parallelTickEnabled,
            priority, maxConcurrentTasks, maxQueueDepth);
    }

    /**
     * 获取 tick 间隔毫秒数。
     *
     * @return tick 间隔 (ms)
     */
    public double tickIntervalMs() {
        return 1000.0 / tpsTarget;
    }

    /**
     * 实体 tick 优先级枚举。
     */
    public enum EntityTickPriority {
        /** 低优先级 - 资源紧张时优先跳过 */
        LOW(0),
        /** 普通优先级 - 默认 */
        NORMAL(1),
        /** 高优先级 - 保证执行 */
        HIGH(2),
        /** 关键优先级 - 不可跳过 */
        CRITICAL(3);

        private final int level;

        EntityTickPriority(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }
    }
}
