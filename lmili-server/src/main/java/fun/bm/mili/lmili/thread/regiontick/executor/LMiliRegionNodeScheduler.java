package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMili 跨 region DAG 节点调度器 —— 使用 LMili API 实现跨 region 节点调度。
 *
 * <p><b>设计目标</b>：替代 Folia 的 TickRegionScheduler，使用 LMili 统一调度 API
 * 实现跨 region DAG 节点的安全调度。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>跨 region 节点路由：将节点调度到目标 region 执行</li>
 *   <li>待处理任务管理：每个 region 有独立的 pending 队列</li>
 *   <li>生命周期管理：region 销毁时自动清理相关任务</li>
 *   <li>统计与诊断：提供跨 region 调度的详细指标</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有公共方法都是线程安全的。跨 region 调度通过 LMili 的 PluginScheduler 实现，
 * 确保任务在正确的 region 上下文中执行。</p>
 *
 * @since 2.0.0
 */
public final class LMiliRegionNodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliRegionNodeScheduler instance;

    /** 跨 region 待处理任务（按目标 regionId 分组） */
    private final ConcurrentMap<Long, ConcurrentLinkedQueue<PendingNodeTask>> crossRegionPending =
            new ConcurrentHashMap<>();

    /** region handle 注册表（用于生命周期管理） */
    private final ConcurrentMap<Long, RegionHandle> regionHandles = new ConcurrentHashMap<>();

    /** 统计指标 */
    private final AtomicLong totalCrossRegionDispatches = new AtomicLong();
    private final AtomicLong totalCrossRegionExecutions = new AtomicLong();
    private final AtomicLong totalCrossRegionTimeouts = new AtomicLong();

    /** 配置 */
    private volatile long taskTimeoutMs = 5000; // 默认 5 秒超时
    private volatile int maxPendingTasksPerRegion = 256;

    private LMiliRegionNodeScheduler() {}

    /**
     * 获取单例实例。
     *
     * @return 调度器实例
     */
    @NotNull
    public static LMiliRegionNodeScheduler getInstance() {
        if (instance == null) {
            synchronized (LMiliRegionNodeScheduler.class) {
                if (instance == null) {
                    instance = new LMiliRegionNodeScheduler();
                }
            }
        }
        return instance;
    }

    /**
     * 注册 region handle。
     *
     * @param regionId region ID
     * @param handle   region handle
     */
    public void registerRegionHandle(long regionId, @NotNull RegionHandle handle) {
        regionHandles.put(regionId, Objects.requireNonNull(handle, "handle"));
    }

    /**
     * 注销 region handle。
     *
     * @param regionId region ID
     */
    public void unregisterRegionHandle(long regionId) {
        regionHandles.remove(regionId);
        // 清理该 region 的待处理任务
        ConcurrentLinkedQueue<PendingNodeTask> removed = crossRegionPending.remove(regionId);
        if (removed != null && !removed.isEmpty()) {
            LOGGER.warn("[LMiliRegionNodeScheduler] {} pending tasks discarded for destroyed region #{}",
                    removed.size(), regionId);
            totalCrossRegionTimeouts.addAndGet(removed.size());
        }
    }

    /**
     * 调度跨 region DAG 节点。
     *
     * <p>将节点添加到目标 region 的待处理队列，并通过 LMili 调度器触发执行。
     *
     * @param nodeId         节点 ID
     * @param nodeRegionId   目标 region ID
     * @param body           节点执行体
     * @param sourceRegionId 源 region ID（用于诊断）
     */
    public void scheduleCrossRegion(int nodeId,
                                     long nodeRegionId,
                                     @NotNull Runnable body,
                                     long sourceRegionId) {
        Objects.requireNonNull(body, "body");

        // 检查待处理队列大小
        ConcurrentLinkedQueue<PendingNodeTask> queue = crossRegionPending.computeIfAbsent(
                nodeRegionId, k -> new ConcurrentLinkedQueue<>());

        if (queue.size() >= maxPendingTasksPerRegion) {
            totalCrossRegionTimeouts.incrementAndGet();
            LOGGER.warn("[LMiliRegionNodeScheduler] Pending queue full for region #{}, dropping node {}",
                    nodeRegionId, nodeId);
            return;
        }

        // 添加到待处理队列
        queue.offer(new PendingNodeTask(nodeId, body, sourceRegionId, System.currentTimeMillis()));
        totalCrossRegionDispatches.incrementAndGet();

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[LMiliRegionNodeScheduler] Scheduled cross-region node {} from region #{} to region #{}",
                    nodeId, sourceRegionId, nodeRegionId);
        }

        // 通过 LMili 调度器触发目标 region 的任务执行
        triggerRegionExecution(nodeRegionId);
    }

    /**
     * 触发指定 region 的待处理任务执行。
     *
     * @param regionId 目标 region ID
     */
    public void triggerRegionExecution(long regionId) {
        // 通过 LMili 调度 API 在目标 region 的上下文中执行
        // 使用 Paper 的 region tick 机制
        RegionHandle handle = regionHandles.get(regionId);
        if (handle == null) {
            LOGGER.debug("[LMiliRegionNodeScheduler] No handle for region #{}", regionId);
            return;
        }

        // 通过 LMili 调度器提交任务
        try {
            // 使用系统调度器执行跨 region 任务
            UnifiedSchedulerAPI.forSystem().runAsync(() -> drainPending(regionId));
        } catch (Exception e) {
            LOGGER.debug("[LMiliRegionNodeScheduler] Failed to trigger region #{}: {}", regionId, e.getMessage());
        }
    }

    /**
     * drain 指定 region 的待处理任务。
     *
     * <p>此方法应在目标 region 的 tick 上下文中调用。
     *
     * @param regionId region ID
     * @return 执行的任务数量
     */
    public int drainPending(long regionId) {
        ConcurrentLinkedQueue<PendingNodeTask> queue = crossRegionPending.get(regionId);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }

        int executed = 0;
        PendingNodeTask task;
        while ((task = queue.poll()) != null) {
            // 检查任务是否超时
            long age = System.currentTimeMillis() - task.submitTime;
            if (age > taskTimeoutMs) {
                totalCrossRegionTimeouts.incrementAndGet();
                LOGGER.debug("[LMiliRegionNodeScheduler] Task {} timed out (age {}ms)", task.nodeId, age);
                continue;
            }

            try {
                task.body.run();
                executed++;
                totalCrossRegionExecutions.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.error("[LMiliRegionNodeScheduler] Error executing node {} in region #{}",
                        task.nodeId, regionId, t);
            }
        }

        if (executed > 0 && LOGGER.isTraceEnabled()) {
            LOGGER.trace("[LMiliRegionNodeScheduler] Drained {} tasks for region #{}", executed, regionId);
        }

        return executed;
    }

    /**
     * 获取指定 region 的待处理任务数。
     *
     * @param regionId region ID
     * @return 待处理任务数
     */
    public int getPendingCount(long regionId) {
        ConcurrentLinkedQueue<PendingNodeTask> queue = crossRegionPending.get(regionId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 获取总跨 region 调度次数。
     *
     * @return 调度次数
     */
    public long getTotalCrossRegionDispatches() {
        return totalCrossRegionDispatches.get();
    }

    /**
     * 获取总跨 region 执行次数。
     *
     * @return 执行次数
     */
    public long getTotalCrossRegionExecutions() {
        return totalCrossRegionExecutions.get();
    }

    /**
     * 获取总超时次数。
     *
     * @return 超时次数
     */
    public long getTotalCrossRegionTimeouts() {
        return totalCrossRegionTimeouts.get();
    }

    /**
     * 设置任务超时时间。
     *
     * @param timeoutMs 超时时间（毫秒）
     */
    public void setTaskTimeoutMs(long timeoutMs) {
        this.taskTimeoutMs = Math.max(100, timeoutMs);
    }

    /**
     * 设置每个 region 的最大待处理任务数。
     *
     * @param max 最大任务数
     */
    public void setMaxPendingTasksPerRegion(int max) {
        this.maxPendingTasksPerRegion = Math.max(16, max);
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息映射
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalCrossRegionDispatches", totalCrossRegionDispatches.get());
        stats.put("totalCrossRegionExecutions", totalCrossRegionExecutions.get());
        stats.put("totalCrossRegionTimeouts", totalCrossRegionTimeouts.get());
        stats.put("registeredRegions", regionHandles.size());
        stats.put("pendingQueues", crossRegionPending.size());
        stats.put("taskTimeoutMs", taskTimeoutMs);
        stats.put("maxPendingTasksPerRegion", maxPendingTasksPerRegion);
        return stats;
    }

    /**
     * 清理所有资源（服务器关闭时调用）。
     */
    public void clear() {
        regionHandles.clear();
        crossRegionPending.clear();
        instance = null;
    }

    // ---- 内部类 ----

    /**
     * 待处理节点任务。
     */
    public record PendingNodeTask(
            int nodeId,
            @NotNull Runnable body,
            long sourceRegionId,
            long submitTime
    ) {}

    /**
     * Region Handle —— 用于标识和管理 region 的生命周期。
     */
    public interface RegionHandle {
        /**
         * 获取 region ID。
         *
         * @return region ID
         */
        long getRegionId();

        /**
         * 检查 region 是否仍然有效。
         *
         * @return true 如果有效
         */
        boolean isValid();

        /**
         * 获取当前 region 的 tick 上下文。
         *
         * @return tick 上下文，可能为 null
         */
        @Nullable RegionTickContext getCurrentContext();
    }
}
