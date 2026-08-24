package fun.bm.mili.lmili.runtime.budget;

/**
 * Tick 预算配置（基准值可配，非硬编码语义；实际值由 BudgetAllocator 推导覆盖，D-21）
 * （ARCHITECTURE_AdaptiveRuntime.md §3.12 / D-21：4ms 只是默认示例基准，禁止魔法数字）。
 */
public record TickBudgetConfig(
        long baseSoftNanos,
        double hardMultiplier,
        long minSoftNanos,
        long maxSoftNanos,
        double emaAlpha,
        long entityShareNanos,
        long blockShareNanos,
        long chunkShareNanos,
        long pluginShareNanos
) {

    /** 示例分解（soft=4ms 时）：Entity 1.7ms │ Block 0.8ms │ Chunk 1.0ms │ Plugin 0.3ms │ Reserve 0.2ms */
    public static final TickBudgetConfig DEFAULTS = new TickBudgetConfig(
            4_000_000L,    // base 4ms
            1.25,          // hard = soft × 1.25
            1_000_000L,    // 下限 1ms
            16_000_000L,   // 上限 16ms
            0.3,           // EMA α
            1_700_000L,    // Entity 42.5%
            800_000L,      // Block 20%
            1_000_000L,    // Chunk 25%
            300_000L       // Plugin 7.5%
    );

    /** 类型默认估算（BudgetAllocator 换算时用占比；Save 不占 CPU 预算） */
    public long defaultEstimateNanos(fun.bm.mili.lmili.runtime.task.TickTaskType type) {
        return switch (type) {
            case ENTITY -> entityShareNanos;
            case BLOCK -> blockShareNanos;
            case CHUNK, FLUID -> chunkShareNanos;
            case PLUGIN -> pluginShareNanos;
            case SAVE -> 0;
        };
    }

    public TickBudgetConfig withBase(long newBaseSoftNanos) {
        return new TickBudgetConfig(newBaseSoftNanos, hardMultiplier, minSoftNanos, maxSoftNanos, emaAlpha,
                entityShareNanos, blockShareNanos, chunkShareNanos, pluginShareNanos);
    }
}
