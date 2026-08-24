package fun.bm.mili.lmili.runtime.task;

/**
 * tick 任务类型（需求 #7：TickTask { Entity, Block, Chunk, Fluid, Plugin } + Save）
 * （ARCHITECTURE_AdaptiveRuntime.md §3.13）。
 *
 * <p>默认预算仅为 soft 基准的示例占比（42.5%/20%/25%/7.5%/5%）；
 * 实际纳秒值由 BudgetAllocator 按 soft 预算动态换算（D-21），此处不写死绝对值。
 */
public enum TickTaskType {
    /** rank 3（最高） */
    ENTITY(3),
    /** rank 2 */
    BLOCK(2),
    /** rank 1（含 chunk save 类目） */
    CHUNK(1),
    FLUID(1),
    /** rank 0（最低，background） */
    PLUGIN(0),
    /** 不占 tick CPU 预算（走 IO 通道） */
    SAVE(0);

    private final int rank;

    TickTaskType(int rank) {
        this.rank = rank;
    }

    /** 优先级（Entity 最高、Block 次高、Plugin/background 低） */
    public int rank() {
        return rank;
    }
}
