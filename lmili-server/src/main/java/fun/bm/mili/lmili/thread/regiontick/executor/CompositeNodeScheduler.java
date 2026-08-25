package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组合 NodeScheduler —— 按节点 regionId 自动路由到合适的执行器。
 *
 * <p><b>路由规则</b>：
 * <ul>
 *   <li>节点 regionId == {@link CompiledDag#GLOBAL_REGION_ID} → 同 region 快路径</li>
 *   <li>节点 regionId == 当前 region → 同 region 快路径</li>
 *   <li>节点 regionId != 当前 region → 通过 {@link LMiliRegionNodeScheduler} 路由到目标 region</li>
 * </ul>
 * </p>
 *
 * <p>这是 {@code DagExecutionEngine} 实际使用的 NodeScheduler。</p>
 *
 * <p>使用 LMili API 实现，不再依赖 Folia API。</p>
 */
public final class CompositeNodeScheduler implements NodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SameRegionNodeScheduler sameRegion;
    private final LMiliRegionNodeScheduler crossRegion;

    /** 跨 region 任务计数 */
    private final AtomicLong crossRegionDispatchCount = new AtomicLong();

    public CompositeNodeScheduler(@NotNull final SameRegionNodeScheduler sameRegion) {
        this.sameRegion = Objects.requireNonNull(sameRegion, "sameRegion");
        this.crossRegion = LMiliRegionNodeScheduler.getInstance();
    }

    @Override
    public void dispatch(int nodeId,
                         long nodeRegionId,
                         @NotNull final Runnable body,
                         @NotNull final RegionTickContext currentContext) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(currentContext, "currentContext");

        final long currentRegionId = currentContext.regionId;

        if (nodeRegionId == CompiledDag.GLOBAL_REGION_ID || nodeRegionId == currentRegionId) {
            // 快路径：global 或同 region
            sameRegion.dispatch(nodeId, nodeRegionId, body, currentContext);
        } else {
            // 跨 region —— 通过 LMiliRegionNodeScheduler 路由
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[DAG] Routing cross-region node {} from region #{} to region #{}",
                        nodeId, currentRegionId, nodeRegionId);
            }
            dispatchCrossRegion(nodeId, nodeRegionId, body, currentRegionId);
        }
    }

    /**
     * 跨 region 调度 —— 通过 LMiliRegionNodeScheduler 将任务路由到目标 region。
     */
    private void dispatchCrossRegion(int nodeId, long targetRegionId, @NotNull Runnable body, long sourceRegionId) {
        crossRegionDispatchCount.incrementAndGet();
        crossRegion.scheduleCrossRegion(nodeId, targetRegionId, body, sourceRegionId);
    }

    /**
     * drain 指定 region 的跨 region 待处理任务。
     */
    public void drainCrossRegionPending(long regionId) {
        crossRegion.drainPending(regionId);
    }

    /**
     * 获取跨 region 调度计数。
     */
    public long getCrossRegionDispatchCount() {
        return crossRegionDispatchCount.get();
    }

    /**
     * 获取指定 region 的跨 region 待处理任务数。
     */
    public int getCrossRegionPendingCount(long regionId) {
        return crossRegion.getPendingCount(regionId);
    }

    public SameRegionNodeScheduler sameRegionScheduler() { return sameRegion; }
}
