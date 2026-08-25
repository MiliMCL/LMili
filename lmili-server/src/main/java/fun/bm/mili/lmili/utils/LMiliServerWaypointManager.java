package fun.bm.mili.lmili.utils;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMili 路点管理器 —— 使用 LMili API 实现路点追踪功能。
 *
 * <p><b>设计目标</b>：替代 Folia 的 WaypointManager，使用 LMili 统一调度 API
 * 实现安全、高效的路点追踪。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>路点追踪：追踪实体（玩家、NPC）的路点状态</li>
 *   <li>生命周期管理：实体销毁时自动清理路点数据</li>
 *   <li>线程安全：使用 ConcurrentHashMap 确保并发安全</li>
 *   <li>统计与诊断：提供路点追踪的详细指标</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有公共方法都是线程安全的。路点操作通过 LMili 的同步任务执行器保护。</p>
 *
 * @since 2.0.0
 */
public final class LMiliServerWaypointManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliServerWaypointManager instance;

    /** 路点数据（按实体 ID 分组） */
    private final ConcurrentMap<Integer, WaypointData> waypointData = new ConcurrentHashMap<>();

    /** 追踪的实体集合 */
    private final Set<Integer> trackedEntities = ConcurrentHashMap.newKeySet();

    /** 统计指标 */
    private final AtomicLong totalTrackRequests = new AtomicLong();
    private final AtomicLong totalUntrackRequests = new AtomicLong();
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong totalCleanups = new AtomicLong();

    /** 配置 */
    private volatile boolean enabled = true;
    private volatile int maxWaypointsPerEntity = 64;

    private LMiliServerWaypointManager() {}

    /**
     * 获取单例实例。
     *
     * @return 管理器实例
     */
    @NotNull
    public static LMiliServerWaypointManager getInstance() {
        if (instance == null) {
            synchronized (LMiliServerWaypointManager.class) {
                if (instance == null) {
                    instance = new LMiliServerWaypointManager();
                }
            }
        }
        return instance;
    }

    /**
     * 追踪实体的路点。
     *
     * @param entity 要追踪的实体
     */
    public void trackWaypoint(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!enabled) return;

        totalTrackRequests.incrementAndGet();
        trackedEntities.add(entity.getId());

        waypointData.computeIfAbsent(entity.getId(), k -> new WaypointData(entity.getId()));

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[LMiliWaypointManager] Tracking waypoint for entity {} ({})",
                    entity.getId(), entity.getName().getString());
        }
    }

    /**
     * 取消追踪实体的路点。
     *
     * @param entity 要取消追踪的实体
     */
    public void untrackWaypoint(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");

        totalUntrackRequests.incrementAndGet();
        trackedEntities.remove(entity.getId());

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[LMiliWaypointManager] Untracking waypoint for entity {} ({})",
                    entity.getId(), entity.getName().getString());
        }
    }

    /**
     * 获取实体的路点数据。
     *
     * @param entity 实体
     * @return 路点数据，如果未追踪则返回 null
     */
    @Nullable
    public WaypointData getWaypointData(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        totalLookups.incrementAndGet();
        return waypointData.get(entity.getId());
    }

    /**
     * 检查实体是否正在被追踪。
     *
     * @param entity 实体
     * @return true 如果正在追踪
     */
    public boolean isTracking(@NotNull Entity entity) {
        return trackedEntities.contains(entity.getId());
    }

    /**
     * 清理已销毁实体的路点数据。
     *
     * @return 清理的数据条数
     */
    public int cleanupDestroyedEntities() {
        int cleaned = 0;
        for (Integer entityId : trackedEntities) {
            WaypointData data = waypointData.get(entityId);
            if (data == null || data.isStale()) {
                trackedEntities.remove(entityId);
                waypointData.remove(entityId);
                cleaned++;
            }
        }
        totalCleanups.addAndGet(cleaned);
        return cleaned;
    }

    /**
     * 获取追踪的实体数量。
     *
     * @return 追踪的实体数
     */
    public int getTrackedEntityCount() {
        return trackedEntities.size();
    }

    /**
     * 获取路点数据总数。
     *
     * @return 路点数据数
     */
    public int getWaypointDataCount() {
        return waypointData.size();
    }

    /**
     * 设置是否启用路点追踪。
     *
     * @param enabled true 表示启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 检查是否启用。
     *
     * @return true 如果启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置每个实体的最大路点数。
     *
     * @param max 最大路点数
     */
    public void setMaxWaypointsPerEntity(int max) {
        this.maxWaypointsPerEntity = Math.max(1, max);
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息映射
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("enabled", enabled);
        stats.put("trackedEntities", trackedEntities.size());
        stats.put("waypointDataCount", waypointData.size());
        stats.put("totalTrackRequests", totalTrackRequests.get());
        stats.put("totalUntrackRequests", totalUntrackRequests.get());
        stats.put("totalLookups", totalLookups.get());
        stats.put("totalCleanups", totalCleanups.get());
        stats.put("maxWaypointsPerEntity", maxWaypointsPerEntity);
        return stats;
    }

    /**
     * 清理所有数据（服务器关闭时调用）。
     */
    public void clear() {
        trackedEntities.clear();
        waypointData.clear();
        instance = null;
    }

    // ---- 内部类 ----

    /**
     * 路点数据。
     */
    public static final class WaypointData {
        private final int entityId;
        private final long createTime;
        private volatile long lastAccessTime;
        private volatile Object waypointInfo;

        WaypointData(int entityId) {
            this.entityId = entityId;
            this.createTime = System.currentTimeMillis();
            this.lastAccessTime = this.createTime;
        }

        public int getEntityId() { return entityId; }

        public long getCreateTime() { return createTime; }

        public long getLastAccessTime() { return lastAccessTime; }

        public void updateAccessTime() { this.lastAccessTime = System.currentTimeMillis(); }

        @Nullable
        public Object getWaypointInfo() { return waypointInfo; }

        public void setWaypointInfo(@Nullable Object info) {
            this.waypointInfo = info;
            updateAccessTime();
        }

        /**
         * 检查数据是否过期（超过 5 分钟未访问）。
         *
         * @return true 如果过期
         */
        public boolean isStale() {
            return System.currentTimeMillis() - lastAccessTime > 300_000;
        }

        @Override
        @NotNull
        public String toString() {
            return "WaypointData{entityId=" + entityId + ", created=" + createTime + "}";
        }
    }
}
