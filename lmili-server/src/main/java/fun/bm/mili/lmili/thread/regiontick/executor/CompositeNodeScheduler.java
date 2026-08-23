package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * 组合 NodeScheduler —— 按节点 regionId 自动路由到合适的执行器。
 *
 * <p><b>路由规则</b>（来自 Mili DAG-region 协调约束）：
 * <ul>
 *   <li>节点 regionId == {@link CompiledDag#GLOBAL_REGION_ID} → 同 region 快路径</li>
 *   <li>节点 regionId == 当前 region → 同 region 快路径（Folia tickingRegion 上下文保留）</li>
 *   <li>节点 regionId != 当前 region → FoliaRegionNodeScheduler 跨 region 路径
 *       （重新入 Folia 调度，由目标 region 的 acquire 路径执行）</li>
 * </ul>
 * </p>
 *
 * <p>这是 {@code DagExecutionEngine} 实际使用的 NodeScheduler。</p>
 */
public final class CompositeNodeScheduler implements NodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SameRegionNodeScheduler sameRegion;
    private final FoliaRegionNodeScheduler foliaRegion;

    public CompositeNodeScheduler(@NotNull final SameRegionNodeScheduler sameRegion,
                                   @NotNull final FoliaRegionNodeScheduler foliaRegion) {
        this.sameRegion = Objects.requireNonNull(sameRegion, "sameRegion");
        this.foliaRegion = Objects.requireNonNull(foliaRegion, "foliaRegion");
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
            // 跨 region —— 重新入 Folia 调度
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[DAG] Routing cross-region node {} from region #{} to region #{}",
                        nodeId, currentRegionId, nodeRegionId);
            }
            foliaRegion.dispatch(nodeId, nodeRegionId, body, currentContext);
        }
    }

    public SameRegionNodeScheduler sameRegionScheduler() { return sameRegion; }
    public FoliaRegionNodeScheduler foliaRegionScheduler() { return foliaRegion; }
}