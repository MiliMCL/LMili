package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * 同 region 快路径 DAG 节点路由器。
 *
 * <p>当 DAG 节点所属 region 与当前 region tick 上下文一致时（或者节点是 global），
 * 直接在当前线程同步执行。</p>
 *
 * <p>这是高效的快路径：避免跨 region 调度开销，前提是"节点属于当前 region"。</p>
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>当前线程是当前 region 的 tick 线程</li>
 *   <li>节点 regionId == 当前 regionId 或节点为 global</li>
 * </ul>
 */
public final class SameRegionNodeScheduler implements NodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void dispatch(int nodeId,
                         long nodeRegionId,
                         @NotNull final Runnable body,
                         @NotNull final RegionTickContext currentContext) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(currentContext, "currentContext");

        final long currentRegionId = currentContext.regionId;

        // 验证：节点 regionId 必须匹配当前 region（不允许跨 region 走此路径）
        if (nodeRegionId != CompiledDag.GLOBAL_REGION_ID
                && nodeRegionId != currentRegionId) {
            // 违反约束 —— 抛错让 CompositeNodeScheduler/调用方处理
            throw new CrossRegionExecutionException(
                    "DAG node " + nodeId + " belongs to region #" + nodeRegionId
                            + " but is being dispatched from region #" + currentRegionId
                            + " via SameRegionNodeScheduler. Cross-region execution must go through "
                            + "CompositeNodeScheduler to route to the target region.");
        }

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[DAG] Executing node {} in-region {} (fast path)", nodeId, currentRegionId);
        }
        // 直接在当前线程同步执行
        body.run();
    }

    /**
     * 跨 region 执行异常 —— 用于 CompositeNodeScheduler 检测错误路由。
     */
    public static final class CrossRegionExecutionException extends RuntimeException {
        public CrossRegionExecutionException(String message) {
            super(message);
        }
    }
}
