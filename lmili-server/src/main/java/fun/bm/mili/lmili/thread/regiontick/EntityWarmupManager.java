package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import io.papermc.paper.entity.activation.ActivationRange;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * 新加载实体预激活管理器 —— 在 region tick 开头对刚加载的实体做预激活与距离预算。
 *
 * <p><b>目的</b>：Paper 的 {@code ActivationRange} 通常在 entity tick 路径上判断实体是否"激活"
 * （即是否真正需要 tick）。对于刚加载的实体，第一个 tick 时 ActivationRange 才第一次评估它们，
 * 会带来额外的判断开销（cold start）。本类在 region tick 开头**提前**调用 ActivationRange，
 * 让新实体的激活状态在 tick 路径之前就准备好。
 *
 * <p><b>距离预算</b>：将新加载的实体加入 {@link EntityPriorityScheduler}（如果启用），
 * 让玩家附近的新加载实体优先 tick。
 *
 * <p><b>线程</b>：必须在 region tick 线程上调用。
 */
public final class EntityWarmupManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityWarmupManager() {}

    /**
     * 对 region 内所有实体预激活 + 距离预算。
     *
     * <p>Folia 的 {@code ActivationRange.activateEntities} 本身是为 region tick 设计的，
     * 在 region tick 开头调用是无害的（幂等），但会"热启动"刚加载实体的激活判断。
     *
     * @param level            region tick 上下文
     * @param data             region 数据
     * @param priorityScheduler 优先级调度器（可为 null），新实体的距离预算会写入其中
     */
    public static void warmup(final ServerLevel level,
                              final RegionizedWorldData data,
                              final EntityPriorityScheduler priorityScheduler) {
        // 修复：检查 level 是否为 null，避免空操作
        if (level == null) {
            return;
        }
        try {
            // 预激活：调用 Paper 的 ActivationRange 提前评估所有实体（幂等）
            ActivationRange.activateEntities(level);
        } catch (Throwable t) {
            LOGGER.warn("[EntityWarmup] activateEntities failed: {}", t.getMessage());
        }

        // 距离预算：如果启用了优先级调度，触发一次重算（让新加载实体进入排序）
        // 修复：移除冗余的 null 检查，直接调用（invalidateRecalc 内部已处理）
        if (priorityScheduler != null) {
            priorityScheduler.invalidateRecalc();
        }
    }
}