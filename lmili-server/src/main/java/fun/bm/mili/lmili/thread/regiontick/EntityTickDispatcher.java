package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Entity Tick 分派器 —— 在 region tick 线程上同步执行实体 tick。
 *
 * <p>注意：实体 tick 会调用 {@code Level.getLocalPlayers()}，依赖 Folia 的线程本地 region 数据，
 * 因此必须在 region tick 线程上直接执行，不能派发到其他线程。
 */
public final class EntityTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SLOW_ENTITY_LOG = 20;

    // 实体 tick 诊断队列 —— 记录最近 N 个慢实体信息（用于运维排查）
    private final ConcurrentLinkedQueue<String> slowEntities = new ConcurrentLinkedQueue<>();

    /**
     * 分派实体 tick。
     */
    public void dispatch(long regionId,
                         RegionTickContext context,
                         ServerLevel level,
                         RegionizedWorldData regionizedWorldData) {
        regionizedWorldData.forEachTickingEntity(entity -> {
            if (entity.isRemoved()) return;
            if (level.tickRateManager().isEntityFrozen(entity)) return;

            long entityStartNanos = System.nanoTime();

            try {
                entity.checkDespawn();
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Entity checkDespawn failed for {} in region #{}", entity, regionId, throwable);
            }
            if (entity.isRemoved()) return;
            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) return;
                entity.stopRiding();
            }
            level.guardEntityTick(level::tickNonPassenger, entity);

            // 单个实体 tick 过慢时记录诊断信息
            long entityElapsedNanos = System.nanoTime() - entityStartNanos;
            if (entityElapsedNanos >= RegionTickPoolConfig.perEntityWarnMs * 1_000_000L) {
                recordSlowEntity(entity, entityElapsedNanos / 1_000_000, regionId);
            }
        });

        // 限制慢实体诊断队列大小
        trimSlowEntityLog();
    }

    /**
     * 记录慢实体的诊断信息。
     */
    private void recordSlowEntity(net.minecraft.world.entity.Entity entity, long elapsedMs, long regionId) {
        String entityInfo = String.format("%s[id=%d] at [%.1f, %.1f, %.1f] took %dms in region #%d",
                entity.getType().toString(), entity.getId(),
                entity.getX(), entity.getY(), entity.getZ(),
                elapsedMs, regionId);
        slowEntities.offer(entityInfo);
    }

    /**
     * 限制慢实体诊断队列大小（保留最近 N 条）。
     */
    private void trimSlowEntityLog() {
        while (slowEntities.size() > MAX_SLOW_ENTITY_LOG) {
            slowEntities.poll();
        }
    }

    /**
     * 获取慢实体诊断信息。
     */
    public ArrayList<String> getSlowEntityDiagnostics() {
        return new ArrayList<>(slowEntities);
    }

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("slow_entity_diagnostics", new ArrayList<>(slowEntities));
        return stats;
    }
}
