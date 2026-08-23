package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import dev.kaiijumc.kaiiju.KaiijuEntityThrottler;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.config.modules.optimizations.EntityTickPerformanceConfig;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // Mili: 每 region 的实体 tick 指标（LongAdder 高并发累加器），用于现代运维诊断与自适应调优
    private final ConcurrentHashMap<Long, EntityTickMetrics> regionMetrics = new ConcurrentHashMap<>();

    // Mili: 每 region 的世界级实体清理上次执行时间（毫秒），用于 entityCleanupIntervalMs 节流
    private final ConcurrentHashMap<Long, Long> lastCleanupMs = new ConcurrentHashMap<>();

    // Mili: 每 region 的 region tick 计数器（用于 EntityPriorityScheduler 的重算间隔判断）
    private final ConcurrentHashMap<Long, Long> regionTickCounters = new ConcurrentHashMap<>();

    // Mili: 每 region 的实体优先级调度器（按玩家距离分桶），懒初始化
    private final ConcurrentHashMap<Long, EntityPriorityScheduler> regionPriorities = new ConcurrentHashMap<>();

    // Mili: 时间/shard 预算超限控制流异常 —— 单例 + 关闭 stack trace，零开销跳出 forEachTickingEntity
    private static final TickBudgetExceededException BUDGET_EXCEEDED = new TickBudgetExceededException();

    /**
     * 分派实体 tick。
     */
    public void dispatch(long regionId,
                         RegionTickContext context,
                         ServerLevel level,
                         RegionizedWorldData regionizedWorldData) {
        // Mili: 虚拟线程 fallback —— 当前 dispatch 在 region tick thread 上跑，Folia 设置了
        // currentTickingWorldRegionizedData，但虚拟线程未来可能执行本 dispatch。
        // 显式设置 RegionDataThreadLocal，finally 清除（try-with-resources 模式）。
        final boolean threadLocalSet;
        if (RegionDataThreadLocal.getCurrent() == null && regionizedWorldData != null) {
            RegionDataThreadLocal.setCurrent(regionizedWorldData);
            threadLocalSet = true;
        } else {
            threadLocalSet = false;
        }
        try {
            dispatchInternal(regionId, context, level, regionizedWorldData);
        } finally {
            if (threadLocalSet) {
                RegionDataThreadLocal.clear();
            }
        }
    }

    /**
     * 实际 dispatch 逻辑 —— 由 dispatch() 调用（包装 RegionDataThreadLocal 设置/清除）。
     */
    private void dispatchInternal(long regionId,
                                   RegionTickContext context,
                                   ServerLevel level,
                                   RegionizedWorldData regionizedWorldData) {
        // Mili start - 现代化高性能实体 tick 系统（七层防卡死协同）
        //
        // ┌─────────────────────────────────────────────────────────────────────────┐
        // │ Layer 7（兜底）   全局时间/shard 预算   —— 类型无关本 tick 跳出          │
        // │ Layer 6（精细）   Kaiiju 类型级限流     —— removal 销毁 + limit 降频分片 │
        // │ Layer 5（分级）   按玩家距离分桶排序    —— 玩家近→远，远处 skip          │
        // │ Layer 4（治标）   玩家周围 chunk 预加载  —— chunk load 与移动并行        │
        // │ Layer 3（治本）   世界级硬上限清理       —— cap 触发按距离优先级 remove  │
        // │ Layer 2（监控）   LongAdder 高并发指标   —— ticked/skipped/removed/耗时  │
        // │ Layer 1（微观）   pushEntities 有界查询 + sensor 排序（上一轮已做）       │
        // └─────────────────────────────────────────────────────────────────────────┘
        //
        // 所有优化默认禁用：KaiijuEntityLimits.enabled=false（Kaiiju YAML）/
        // EntityTickPerformanceConfig 全 false/0/-1（原版行为），启用任意一项时按需激活，零功能回退。

        // Mili: Kaiiju tickLimiterStart/Finish 由外层 mili$tickRegion (line 1123 / line 1172)
        // 负责开/关 —— 这里**不**重复调用，避免 tickLimiterStart 被调两次（每次 region tick）。
        final boolean kaiijuEnabled = KaiijuEntityLimits.enabled;
        final EntityTickMetrics metrics = EntityTickPerformanceConfig.metricsEnabled
            ? regionMetrics.computeIfAbsent(regionId, k -> new EntityTickMetrics())
            : null;

        // ─── Layer 4：玩家周围 chunk 异步预加载（region tick 开头） ─────────────
        if (EntityTickPerformanceConfig.chunkPreloadEnabled) {
            try {
                PlayerChunkPreloader.preload(level, regionizedWorldData, context,
                    EntityTickPerformanceConfig.chunkPreloadRadiusChunks);
            } catch (Throwable t) {
                LOGGER.warn("[EntityTickDispatcher] PlayerChunkPreloader failed: {}", t.getMessage());
            }
        }

        // ─── Layer 5：实体优先级调度器（懒初始化） ───────────────────────────
        final boolean priorityEnabled = EntityTickPerformanceConfig.tickPriorityByDistance;
        final EntityPriorityScheduler priorityScheduler = priorityEnabled
            ? regionPriorities.computeIfAbsent(regionId, k -> new EntityPriorityScheduler(regionId, regionizedWorldData,
                EntityTickPerformanceConfig.tickPriorityBuckets,
                EntityTickPerformanceConfig.tickPriorityMaxDistanceChunks))
            : null;

        // ─── Layer 4b：新加载实体预激活 + 距离预算 ─────────────────────────────
        if (EntityTickPerformanceConfig.entityPreActivateEnabled && priorityScheduler != null) {
            try {
                EntityWarmupManager.warmup(level, regionizedWorldData, priorityScheduler);
            } catch (Throwable t) {
                LOGGER.warn("[EntityTickDispatcher] EntityWarmupManager failed: {}", t.getMessage());
            }
        }

        // ─── 获取当前 tick 的 ticking entities（按优先级排序，或原版顺序） ─────
        final long tickCounter = regionTickCounters.merge(regionId, 1L, Long::sum);
        final Entity[] tickingEntities;
        final int skippedByPriority;
        if (priorityScheduler != null) {
            tickingEntities = priorityScheduler.getSortedTicking(tickCounter,
                EntityTickPerformanceConfig.tickPriorityRecalcIntervalTicks);
            skippedByPriority = priorityScheduler.getSkippedCount();
            if (metrics != null && skippedByPriority > 0) {
                metrics.recordSkippedBatch(skippedByPriority);
            }
        } else {
            tickingEntities = null; // 走 forEachTickingEntity 路径
            skippedByPriority = 0;
        }

        final boolean budgetEnabled = EntityTickPerformanceConfig.entityTickBudgetMs > 0.0
            || EntityTickPerformanceConfig.entityTickShardSize > 0;
        final long budgetNanos = (long) (EntityTickPerformanceConfig.entityTickBudgetMs * 1_000_000.0);
        final int shardSize = EntityTickPerformanceConfig.entityTickShardSize;
        final long startNanos = budgetEnabled ? System.nanoTime() : 0L;
        final int[] tickedCounter = {0};

        // 用于循环 ticking entities 的辅助 consumer
        final java.util.function.Consumer<Entity> tickAction = entity -> {
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
            // Layer 6: Kaiiju 限流
            if (kaiijuEnabled) {
                KaiijuEntityThrottler.EntityThrottlerReturn throttle =
                    regionizedWorldData.entityThrottler.tickLimiterShouldSkip(entity);
                if (throttle.remove && !entity.hasCustomName()) {
                    entity.remove(Entity.RemovalReason.DISCARDED);
                    if (metrics != null) metrics.recordRemoved();
                    return;
                }
                if (throttle.skip) {
                    if (metrics != null) metrics.recordSkipped();
                    return;
                }
            }
            // Layer 7: 全局时间/shard 预算
            if (budgetEnabled) {
                if (shardSize > 0 && tickedCounter[0] >= shardSize) {
                    throw BUDGET_EXCEEDED;
                }
                if (budgetNanos > 0L && System.nanoTime() - startNanos > budgetNanos) {
                    throw BUDGET_EXCEEDED;
                }
            }
            level.guardEntityTick(level::tickNonPassenger, entity);
            tickedCounter[0]++;

            long entityElapsedNanos = System.nanoTime() - entityStartNanos;
            if (metrics != null) metrics.recordTicked(entityElapsedNanos);
            if (entityElapsedNanos >= RegionTickPoolConfig.perEntityWarnMs * 1_000_000L) {
                recordSlowEntity(entity, entityElapsedNanos / 1_000_000, regionId);
            }
        };

        boolean budgetExceeded = false;
        try {
            if (tickingEntities != null) {
                // 优先级排序路径：用 for 替代 forEachTickingEntity（与 Consumer 同语义但允许 throw 跳出）
                for (int i = 0; i < tickingEntities.length; i++) {
                    tickAction.accept(tickingEntities[i]);
                }
            } else {
                // 原版路径：forEachTickingEntity
                regionizedWorldData.forEachTickingEntity(tickAction);
            }
        } catch (TickBudgetExceededException e) {
            budgetExceeded = true;
        } finally {
            // Mili: tickLimiterFinish 由外层 mili$tickRegion line 1172 统一调用
            // （不依赖 dagResult，两条路径都会执行）—— 这里**不**调用，避免双边重复
            // finish 会让 continueFrom 翻倍、toTick 翻倍，彻底破坏限流逻辑
            if (metrics != null) {
                metrics.recordTickCompletion(budgetExceeded);
                metrics.recordLastTick(System.nanoTime() - startNanos);
            }
        }

        // Layer 3: 世界级硬上限清理
        if (EntityTickPerformanceConfig.entityCleanupCap > 0) {
            long now = System.currentTimeMillis();
            Long lastMs = lastCleanupMs.get(regionId);
            if (lastMs == null || now - lastMs >= EntityTickPerformanceConfig.entityCleanupIntervalMs) {
                lastCleanupMs.put(regionId, now);
                EntityCleanupManager.tick(level, regionizedWorldData,
                    EntityTickPerformanceConfig.entityCleanupCap,
                    EntityTickPerformanceConfig.entityCleanupMinPlayerDistance,
                    metrics);
            }
        }
        // Mili end

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
     * region 销毁钩子 —— 由 {@link RegionTickDispatcher#unregisterRegion} 调用。
     *
     * <p>清理该 region 在 EntityTickDispatcher 内部的 per-region 状态：
     * <ul>
     *   <li>{@link EntityPriorityScheduler} 实例（按 regionId 缓存的优先级调度器）</li>
     *   <li>region 指标（{@link EntityTickMetrics}）</li>
     *   <li>上次清理时间戳（{@code lastCleanupMs}）</li>
     *   <li>region tick 计数器（{@code regionTickCounters}）</li>
     * </ul>
     *
     * <p>不清理 {@code slowEntities} 队列 —— 慢实体诊断是跨 region 全局日志，保留供运维事后分析。
     */
    public void onRegionDestroyed(final long regionId) {
        final EntityPriorityScheduler scheduler = regionPriorities.remove(regionId);
        if (scheduler != null) {
            scheduler.clear();
        }
        regionMetrics.remove(regionId);
        lastCleanupMs.remove(regionId);
        regionTickCounters.remove(regionId);
        LOGGER.debug("[EntityTickDispatcher] Cleaned up per-region state for region #{}", regionId);
    }

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("slow_entity_diagnostics", new ArrayList<>(slowEntities));
        // Mili: 每 region 指标快照 —— 运维诊断 / 自适应调优用
        final Map<Long, Map<String, Object>> regionStats = new LinkedHashMap<>();
        regionMetrics.forEach((regionId, m) -> regionStats.put(regionId, m.snapshot()));
        stats.put("region_entity_metrics", regionStats);
        return stats;
    }

    /**
     * 获取指定 region 的指标快照（不存在则返回 null）。
     */
    public Map<String, Object> getRegionMetrics(final long regionId) {
        final EntityTickMetrics m = regionMetrics.get(regionId);
        return m == null ? null : m.snapshot();
    }

    /**
     * 时间/shard 预算超限控制流异常 —— 单例 + 关闭 stack trace，零开销跳出 forEachTickingEntity。
     * 不参与正常错误处理，仅作控制流信号。
     */
    private static final class TickBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        TickBudgetExceededException() {
            // disable stack trace + suppression + writable —— 纯控制流异常
            super(null, null, false, false);
        }
        // 再次防御性关闭 fillInStackTrace，防止子类/JVM 反射启用
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
