package fun.bm.mili.lmili.thread.regiontick;

import fun.bm.mili.lmili.thread.regiontick.dag.ResourceType;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import org.jetbrains.annotations.NotNull;

/**
 * Mili 并行 tick 系统的 Profile 定义。
 *
 * <p>每个系统声明其读写资源类型，供 {@code ModernDagTickExecutor} 构建 DAG 冲突图。
 * 两个系统只要资源类型有交集且空间 Scope 有重叠，就存在冲突，必须串行执行；
 * 不同 chunk 上的同类型系统因 Scope 不重叠可并行执行。
 *
 * <h3>系统列表</h3>
 * <ul>
 *   <li>{@link #ENTITY_TICK} — 实体 tick（位置、运动、AI、生命值、骑乘）</li>
 *   <li>{@link #BLOCK_ENTITY_TICK} — 方块实体 tick（箱子、熔炉等）</li>
 *   <li>{@link #CHUNK_RANDOM_TICK} — Chunk 随机 tick（作物生长、流体）</li>
 * </ul>
 */
public final class MiliGameSystems {

    private MiliGameSystems() {}

    /**
     * 实体 tick 系统。
     *
     * <p>读写实体位置、运动向量、AI 状态、生命值、背包和骑乘关系。
     * 由于写入 ENTITY_POSITION，同 chunk 内的实体必须串行（避免位置竞争）；
     * 不同 chunk 上的实体因 Scope 不重叠可安全并行。
     */
    public static final SystemProfile ENTITY_TICK = SystemProfile.builder("entity_tick")
            .reads(ResourceType.ENTITY_POSITION, ResourceType.ENTITY_CHUNK_LOCATION)
            .writes(ResourceType.ENTITY_POSITION, ResourceType.ENTITY_MOTION, ResourceType.ENTITY_AI_STATE,
                    ResourceType.ENTITY_VITALS, ResourceType.ENTITY_INVENTORY, ResourceType.ENTITY_RIDING)
            .priority(10)
            .build();

    /**
     * 方块实体 tick 系统。
     *
     * <p>读写方块实体数据和方块状态。
     * 与 {@link #ENTITY_TICK} 资源类型完全不同，因此即使同一 chunk 也可与实体 tick 并行。
     */
    public static final SystemProfile BLOCK_ENTITY_TICK = SystemProfile.builder("block_entity_tick")
            .reads(ResourceType.TILE_ENTITY_DATA, ResourceType.BLOCK_STATE)
            .writes(ResourceType.TILE_ENTITY_DATA, ResourceType.BLOCK_STATE)
            .priority(5)
            .build();

    /**
     * Chunk 随机 tick 系统。
     *
     * <p>读写方块状态（作物生长、树叶腐烂、流体扩散等）。
     * 仅操作 BLOCK_STATE，与 ENTITY_TICK 无资源冲突可同时运行。
     */
    public static final SystemProfile CHUNK_RANDOM_TICK = SystemProfile.builder("chunk_random_tick")
            .reads(ResourceType.BLOCK_STATE)
            .writes(ResourceType.BLOCK_STATE)
            .priority(1)
            .build();

    /**
     * 返回所有内置系统 profile 数组。
     */
    public static SystemProfile @NotNull [] all() {
        return new SystemProfile[] { ENTITY_TICK, BLOCK_ENTITY_TICK, CHUNK_RANDOM_TICK };
    }
}
