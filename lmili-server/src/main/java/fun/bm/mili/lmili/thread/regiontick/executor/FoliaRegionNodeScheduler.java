package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨 region DAG 节点路由器 —— 把跨 region 节点重新入 Folia 调度。
 *
 * <p><b>核心约束（Mili 新约束）</b>：DAG 节点 B 所属 region 与当前 region 不一致时，
 * <b>绝对不能</b>在当前线程直接执行 B —— 当前线程的
 * {@code TickThread.currentTickingRegion} 指向的是当前 region，不是 B 所属 region。
 * 直接执行会破坏 Folia 的 region 不变量，导致 entity 数据竞争和
 * {@code TickThread.isTickThreadFor} 校验失败。</p>
 *
 * <p>本 scheduler 的正确做法：
 * <ol>
 *   <li>把 B 的执行体包装成目标 region 的"子任务" —— 通过
 *       {@link TickRegionScheduler.RegionScheduleHandle#setHasTasks} 触发目标 region
 *       的重新调度</li>
 *   <li>目标 region 在它的下一个 tick 中由 Folia 自身通过
 *       {@code acquire} → {@code setTickingRegion} → {@code tickRegion} 路径执行</li>
 *   <li>B 的执行会发生在正确的 region 上下文中</li>
 * </ol>
 * </p>
 *
 * <p><b>实现说明</b>：当前实现通过 "target region handle" + 一个 pending 子任务列表
 * 实现 hook：每次跨 region 节点入队时，把节点 append 到目标 region 的 pending 列表，
 * 然后调用 {@code handle.setHasTasks()} 触发 Folia 调度；目标 region 的 tick 进入后
 * 通过 {@link #drainPending(long)} 取走并执行这些子任务。</p>
 *
 * <p><b>挂接点</b>：{@link fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor}
 * 在 {@code executeSlice} 路径上调用 {@link #drainPending}，确保跨 region 节点
 * 能在目标 region 的 tick 中获得执行。</p>
 *
 * <p>注意：本 scheduler <b>不会</b>阻塞当前 region tick —— 它只是把节点"投递"给
 * 目标 region，由 Folia 在合适时机执行。这符合"DAG 只决定 ready 顺序、不绕过
 * Folia acquire"的语义。</p>
 */
public final class FoliaRegionNodeScheduler implements NodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pending 子任务表：regionId → 队列（节点执行体列表）。 */
    private final ConcurrentHashMap<Long, java.util.ArrayDeque<PendingNodeTask>> pendingByRegion
            = new ConcurrentHashMap<>();

    /** 序列号生成器（用于诊断）。 */
    private static final AtomicLong TASK_SEQ = new AtomicLong();

    /**
     * Pending 节点任务描述符。
     */
    public record PendingNodeTask(int nodeId, Runnable body) {
        public static PendingNodeTask of(int nodeId, Runnable body) {
            return new PendingNodeTask(nodeId, body);
        }
    }

    @Override
    public void dispatch(int nodeId,
                         long nodeRegionId,
                         @NotNull final Runnable body,
                         @NotNull final RegionTickContext currentContext) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(currentContext, "currentContext");

        // global 节点不应路由到本 scheduler
        if (nodeRegionId == CompiledDag.GLOBAL_REGION_ID) {
            throw new IllegalArgumentException(
                    "FoliaRegionNodeScheduler received GLOBAL node " + nodeId
                            + "; global nodes should be dispatched via SameRegionNodeScheduler");
        }

        // 同 region 也走本路径是允许的（语义无害，仅多一次调度开销）
        // 但通常 CompositeNodeScheduler 会先走 SameRegion 快路径

        final long targetRegionId = nodeRegionId;
        final PendingNodeTask task = PendingNodeTask.of(nodeId, body);

        // 1. 把任务入到目标 region 的 pending 队列
        pendingByRegion.compute(targetRegionId, (rid, existing) -> {
            if (existing == null) existing = new java.util.ArrayDeque<>();
            existing.add(task);
            return existing;
        });

        // 2. 触发目标 region 重新调度（Folia 会按正常 acquire 路径执行 tick）
        boolean scheduled = triggerRegionReschedule(targetRegionId, currentContext);
        if (!scheduled) {
            // 目标 region 不存在或已不可调度 —— 移除 pending 任务，避免泄漏
            java.util.ArrayDeque<PendingNodeTask> removed = pendingByRegion.computeIfPresent(targetRegionId, (rid, q) -> {
                q.remove(task);
                return q.isEmpty() ? null : q;
            });
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("[DAG] Cross-region node {} for region #{} failed to enqueue "
                                + "(region not found or not schedulable). Pending removed: {}",
                        nodeId, targetRegionId, removed != null);
            }
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[DAG] Cross-region node {} queued for region #{}", nodeId, targetRegionId);
        }
    }

    /**
     * 触发目标 region 重新调度。
     *
     * <p>通过 {@link TickRegionScheduler} 让 Folia 把目标 region 重新入队，
     * Folia 内部会按正常流程 acquire 并 tick 该 region。</p>
     *
     * @param targetRegionId 目标 region 的 ID
     * @param currentContext 当前 region 上下文（用于从当前 region 找到目标 region 的 handle）
     * @return true 表示成功调度；false 表示目标 region 不可达
     */
    private boolean triggerRegionReschedule(long targetRegionId, RegionTickContext currentContext) {
        try {
            // 通过当前 region 的 regionizer 找到目标 region 的 ThreadedRegion
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentRegion =
                    currentContext.region;
            if (currentRegion == null || currentRegion.regioniser == null) {
                return false;
            }
            // 从当前 region 所属的 worldRegionData 找 region 列表
            // 简化：通过 regionizer 的全局 region map 找到
            final RegionizedWorldData regionized = currentRegion.regioniser.world.worldRegionData.get();
            if (regionized == null) {
                return false;
            }
            // 这里直接通过 TickRegions.getScheduler().scheduleRegion(handle) 触发
            // 由于当前 RegionTickContext 不直接暴露"通过 regionId 查 handle"的 API，
            // 我们提供一个 getRegionById helper —— 由 RegionTickDispatcher 注册
            TickRegionScheduler.RegionScheduleHandle handle =
                    FoliaRegionNodeSchedulerHandleRegistry.findHandle(targetRegionId);
            if (handle == null) {
                // 兜底：尝试通过 TickRegions 的全局 region 数据查找
                handle = tryFindRegionHandle(targetRegionId, regionized);
                if (handle == null) return false;
            }

            // 触发 Folia 调度目标 region
            TickRegions.getScheduler().scheduleRegion(handle);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DAG] Failed to trigger region reschedule for region #{}", targetRegionId, t);
            return false;
        }
    }

    /**
     * 尝试通过 RegionizedWorldData 找到 regionId 对应的 handle（兜底查找）。
     *
     * <p>当前 RegionizedWorldData 不直接暴露 region-by-id API；此处简化实现为
     * 返回 null，让上层决定是否忽略或回退。</p>
     */
    private TickRegionScheduler.RegionScheduleHandle tryFindRegionHandle(
            long targetRegionId, RegionizedWorldData regionized) {
        // 兜底查找：当前 Folia API 不直接支持 regionId → handle 映射
        // 因此需要上层通过 FoliaRegionNodeSchedulerHandleRegistry 注册
        return null;
    }

    /**
     * 取出指定 region 的 pending 子任务队列（用于在目标 region 的 tick 中执行）。
     *
     * <p>由 {@link fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor#executeSlice}
     * 在目标 region tick 进入时调用，以 atomic swap 方式清空并返回 pending 列表。</p>
     *
     * @param regionId 目标 region 的 ID
     * @return pending 任务列表（已清空）；若无任务返回空列表
     */
    public java.util.List<PendingNodeTask> drainPending(long regionId) {
        java.util.ArrayDeque<PendingNodeTask> q = pendingByRegion.remove(regionId);
        if (q == null || q.isEmpty()) return java.util.List.of();
        java.util.List<PendingNodeTask> result = new java.util.ArrayList<>(q);
        q.clear();
        return result;
    }

    /**
     * 当前是否有 pending 任务（用于诊断）。
     */
    public int pendingCount(long regionId) {
        java.util.ArrayDeque<PendingNodeTask> q = pendingByRegion.get(regionId);
        return q == null ? 0 : q.size();
    }

    /**
     * handle 注册器 —— FoliaRegionNodeScheduler 不知道如何从 regionId 找 handle，
     * 因此 RegionTickDispatcher 注册全局 handle 表供本 scheduler 查询。
     *
     * <p>线程安全：使用 ConcurrentHashMap，put/remove 由 dispatcher 在 registerRegion/unregisterRegion
     * 中调用。</p>
     */
    public static final class FoliaRegionNodeSchedulerHandleRegistry {
        private static final ConcurrentHashMap<Long, TickRegionScheduler.RegionScheduleHandle> HANDLES
                = new ConcurrentHashMap<>();

        public static void register(long regionId, TickRegionScheduler.RegionScheduleHandle handle) {
            HANDLES.put(regionId, handle);
        }

        public static void unregister(long regionId) {
            HANDLES.remove(regionId);
        }

        public static TickRegionScheduler.RegionScheduleHandle findHandle(long regionId) {
            return HANDLES.get(regionId);
        }

        /**
         * 清空所有 handle 注册（在 dispatcher 关闭时调用，避免泄漏）。
         */
        public static void clear() {
            HANDLES.clear();
        }
    }
}