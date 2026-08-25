package fun.bm.mili.lmili.runtime.tps;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.optimizations.TPSStabilityConfig;
import fun.bm.mili.utils.performance.TPSTracker;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态实体限制器 —— 根据 TPS 状态动态调整实体数量限制。
 *
 * <p>当 TPS 降低时，自动减少允许的实体数量，优先移除：
 * <ol>
 *   <li>远距离的被动生物</li>
 *   <li>掉落物</li>
 *   <li>经验球</li>
 *   <li>箭矢等投射物</li>
 * </ol>
 *
 * @since 2.0.0
 */
public final class DynamicEntityLimiter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile DynamicEntityLimiter instance;

    /** 当前实体数量 */
    private final AtomicInteger currentEntityCount = new AtomicInteger();

    /** 最大实体数量（动态调整） */
    private final AtomicInteger maxEntityCount = new AtomicInteger(10000);

    /** 清理计数 */
    private final AtomicLong cleanupCount = new AtomicLong();

    /** 上次清理时间 */
    private final AtomicLong lastCleanupTime = new AtomicLong();

    /** 清理间隔（ms） */
    private static final long CLEANUP_INTERVAL_MS = 5000;

    private DynamicEntityLimiter() {}

    /**
     * 获取动态实体限制器实例。
     *
     * @return 动态实体限制器
     */
    public static DynamicEntityLimiter getInstance() {
        if (instance == null) {
            synchronized (DynamicEntityLimiter.class) {
                if (instance == null) {
                    instance = new DynamicEntityLimiter();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化动态实体限制器。
     */
    public void initialize() {
        if (!TPSStabilityConfig.enabled) {
            LOGGER.info("[DynamicEntityLimiter] TPS 稳定器已禁用，跳过初始化");
            return;
        }
        LOGGER.info("[DynamicEntityLimiter]已初始化");
    }

    /**
     * 更新实体数量限制。
     * 应由 TPS 稳定器定期调用。
     */
    public void updateLimit() {
        if (!TPSStabilityConfig.enabled) return;

        TPSStabilizer stabilizer = TPSStabilizer.getInstance();
        double ratio = stabilizer.getEntityLimitRatio();
        int baseMax = 10000; // 基础最大实体数

        int newMax = (int) (baseMax * ratio);
        int oldMax = maxEntityCount.getAndSet(newMax);

        if (oldMax != newMax) {
            LOGGER.info("[DynamicEntityLimiter] 实体限制调整: {} -> {} (TPS={}, ratio={})",
                    oldMax, newMax, String.format("%.2f", TPSTracker.getTPS()), ratio);
        }
    }

    /**
     * 检查是否可以生成新实体。
     *
     * @return true 如果允许生成
     */
    public boolean canSpawnEntity() {
        if (!TPSStabilityConfig.enabled) return true;
        return currentEntityCount.get() < maxEntityCount.get();
    }

    /**
     * 增加实体计数。
     */
    public void incrementEntityCount() {
        currentEntityCount.incrementAndGet();
    }

    /**
     * 减少实体计数。
     */
    public void decrementEntityCount() {
        currentEntityCount.decrementAndGet();
    }

    /**
     * 获取当前实体数量。
     *
     * @return 当前实体数量
     */
    public int getCurrentEntityCount() {
        return currentEntityCount.get();
    }

    /**
     * 获取最大实体数量。
     *
     * @return 最大实体数量
     */
    public int getMaxEntityCount() {
        return maxEntityCount.get();
    }

    /**
     * 检查是否需要清理实体。
     *
     * @return true 如果需要清理
     */
    public boolean shouldCleanup() {
        if (!TPSStabilityConfig.enabled) return false;
        if (!TPSStabilizer.getInstance().shouldLimitEntities()) return false;

        long now = System.currentTimeMillis();
        long last = lastCleanupTime.get();
        if (now - last < CLEANUP_INTERVAL_MS) return false;

        return lastCleanupTime.compareAndSet(last, now);
    }

    /**
     * 记录清理操作。
     *
     * @param count 清理的实体数量
     */
    public void recordCleanup(int count) {
        cleanupCount.addAndGet(count);
        currentEntityCount.addAndGet(-count);
    }

    /**
     * 获取清理计数。
     *
     * @return 清理计数
     */
    public long getCleanupCount() {
        return cleanupCount.get();
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息
     */
    public Stats getStats() {
        return new Stats(
                currentEntityCount.get(),
                maxEntityCount.get(),
                cleanupCount.get(),
                TPSTracker.getTPS()
        );
    }

    /**
     * 重置统计信息。
     */
    public void reset() {
        currentEntityCount.set(0);
        maxEntityCount.set(10000);
        cleanupCount.set(0);
        lastCleanupTime.set(0);
    }

    /**
     * 统计信息记录。
     */
    public record Stats(
            int currentEntities,
            int maxEntities,
            long totalCleaned,
            double currentTps
    ) {
        @Override
        public String toString() {
            return String.format("EntityLimiter{current=%d, max=%d, cleaned=%d, tps=%.2f}",
                    currentEntities, maxEntities, totalCleaned, currentTps);
        }
    }
}
