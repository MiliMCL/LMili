package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 共享 Scheduler 版 DAG 节点路由器 —— P0-1 的核心接线。
 *
 * <p>把 DAG "ready" 节点通过 {@link MiliScheduler#submit(RegionTask)} 提交给
 * 全 Mili 共享的 {@link MiliScheduler}（而不是在 region tick 线程上直接
 * {@code body.run()}）。由 MiliScheduler 内部路由：
 * <ul>
 *   <li>节点 regionId &gt; 0 → 对应 Region Worker（WorkStealingCoordinator →
 *       RegionQueue → ExecutionToken 串行化执行）</li>
 *   <li>节点 regionId == {@link CompiledDag#GLOBAL_REGION_ID} (-1) → Global Scheduler
 *       （与 {@link RegionTask#regionId()} 的全局任务语义一致，无 region 约束）</li>
 * </ul>
 *
 * <p><b>完成语义</b>（P0-1 §1.2.C）：<code>submit() 提交成功 ≠ 节点执行完</code>。
 * 本类只保证 <b>submit 段</b>：
 * <ol>
 *   <li>scheduler 已 shutdown → 抛 {@link DagSchedulerUnavailableException}（显式拒绝）</li>
 *   <li>{@link MiliScheduler#submit(RegionTask)} 返回的 handle 立即 CANCELLED
 *       （admission 拒绝，发生在 shutdown 竞态窗口）→ 同样抛
 *       {@link DagSchedulerUnavailableException}</li>
 * </ol>
 * 两条拒绝路径都登记到 {@link #getUnavailableRejectionCount()}，供
 * {@code RegionTickDispatcher.getStats()} 指标暴露 —— 绝不静默丢弃节点。</p>
 *
 * <p>节点 body 一旦被 MiliScheduler 接受，后续的完成 / 失败 / 后继节点派发
 * 全部由 body 自身（DagExecutionEngine 闭包）负责，本类不持有任何 DAG 状态。</p>
 *
 * @see MiliScheduler
 * @see NodeScheduler
 */
public final class MiliSchedulerNodeScheduler implements NodeScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MiliScheduler scheduler;

    /** 派发总数（指标）。 */
    private final LongAdder dispatchCount = new LongAdder();

    /** Scheduler 不可用导致的显式拒绝次数（指标）—— 非静默丢弃的证明。 */
    private final LongAdder unavailableRejections = new LongAdder();

    public MiliSchedulerNodeScheduler(@NotNull final MiliScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void dispatch(final int nodeId,
                         final long nodeRegionId,
                         @NotNull final Runnable body,
                         @NotNull final RegionTickContext currentContext) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(currentContext, "currentContext");
        dispatchCount.increment();

        // 前置检查：共享 scheduler 已关闭 —— 显式拒绝，绝不静默丢弃、绝不重复调度。
        if (scheduler.isShutdown()) {
            unavailableRejections.increment();
            throw new DagSchedulerUnavailableException(
                    "DAG node " + nodeId + " (region #" + nodeRegionId
                            + ") cannot be dispatched: shared MiliScheduler is shutdown ("
                            + scheduler.getClass().getSimpleName() + ")");
        }

        // 构造 RegionTask：regionId 直接携带节点约束
        // (nodeRegionId == CompiledDag.GLOBAL_REGION_ID == -1L 与全局任务语义一致)。
        final RegionTask task = RegionTask.builder(nodeRegionId)
                .task(body)
                .name(dagTaskName(nodeId, nodeRegionId))
                .build();

        final TaskHandle handle;
        try {
            handle = scheduler.submit(task);
        } catch (RuntimeException submitFailure) {
            // 例如调度器实现抛出的拒绝/依赖错误 —— 同样显式失败，交由引擎处理
            unavailableRejections.increment();
            throw new DagSchedulerUnavailableException(
                    "DAG node " + nodeId + " (region #" + nodeRegionId
                            + ") submit rejected by shared MiliScheduler: " + submitFailure.getMessage(),
                    submitFailure);
        }

        // 后置检查：submit 与 shutdown 之间的 admission 竞态 —— handle 立即 CANCELLED 意味着
        // 任务从未入队。此时不能把 "submit 已调用" 误当成 "节点已提交成功"。
        if (handle.state() == TaskHandle.State.CANCELLED) {
            unavailableRejections.increment();
            throw new DagSchedulerUnavailableException(
                    "DAG node " + nodeId + " (region #" + nodeRegionId
                            + ") admission rejected by shared MiliScheduler (state=CANCELLED)");
        }

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[DAG] Node {} (region #{}) submitted to shared MiliScheduler {}",
                    nodeId, nodeRegionId, scheduler.getClass().getSimpleName());
        }
    }

    /**
     * 获取当前共享 scheduler（诊断）。
     */
    @NotNull
    public MiliScheduler scheduler() {
        return scheduler;
    }

    /**
     * 获取累计派发数（指标）。
     */
    public long getDispatchCount() {
        return dispatchCount.sum();
    }

    /**
     * 获取 Scheduler 不可用导致的显式拒绝次数（指标）。
     */
    public long getUnavailableRejectionCount() {
        return unavailableRejections.sum();
    }

    private static String dagTaskName(final int nodeId, final long nodeRegionId) {
        return "dag-node-" + nodeId + "@region-" + nodeRegionId;
    }
}