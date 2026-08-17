package fun.bm.mili.lmili.thread.regiontick.dag;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * DAG 拓扑执行引擎。
 *
 * <p>基于前驱完成回调驱动：节点完成后自动检查其后继的依赖是否满足，
 * 一旦满足立即提交执行，零延迟、零忙等待。
 *
 * <p>本类实例为"单次 tick 使用"：每次 tick 创建新实例，注册该 tick 的执行器，
 * 执行完毕后丢弃。避免了跨 tick 的状态泄漏和并发问题。
 */
public final class RegionDagExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DAG_EXECUTE_TIMEOUT_SECONDS = 5;

    private final ForkJoinPool executor;
    private volatile Map<String, BiConsumer<DagNode, RegionTickContext>> tickExecutors;

    public RegionDagExecutor(final int threadCount, final ForkJoinPool sharedPool) {
        this.executor = sharedPool;
    }

    /**
     * 一次性注册本 tick 所有系统的执行器。
     */
    public void registerAll(@NotNull final Map<String, BiConsumer<DagNode, RegionTickContext>> executors) {
        this.tickExecutors = executors;
    }

    /**
     * 使用 CompletableFuture 链式调度执行 DAG。
     *
     * <p>每个节点包装为 CompletableFuture，通过 thenCompose 链式触发后继就绪节点，
     * 实现零延迟调度。使用 CountDownLatch 等待整层完成。
     */
    public void executeDag(@NotNull final RegionDag dag, @NotNull final RegionTickContext context) {
        int n = dag.size();
        if (n == 0) return;

        Map<String, BiConsumer<DagNode, RegionTickContext>> executors = this.tickExecutors;
        if (executors == null || executors.isEmpty()) {
            LOGGER.warn("[DagExecutor] No executors registered for region #{}", dag.regionId);
            return;
        }

        // in-degree 追踪，使用 AtomicInteger 支持 CAS 递减
        AtomicInteger[] remainingDeps = new AtomicInteger[n];
        for (int i = 0; i < n; i++) {
            remainingDeps[i] = new AtomicInteger(dag.getInDegree(i));
        }

        CountDownLatch completionLatch = new CountDownLatch(n);

        // 提交所有入度为 0 的节点
        for (int i = 0; i < n; i++) {
            if (remainingDeps[i].get() == 0) {
                submitNode(dag, context, i, remainingDeps, completionLatch, executors);
            }
        }

        // 等待所有节点完成
        try {
            if (!completionLatch.await(DAG_EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                long remaining = completionLatch.getCount();
                LOGGER.warn("[DagExecutor] DAG execution timed out for region #{} after {}s (remaining: {}/{})",
                        dag.regionId, DAG_EXECUTE_TIMEOUT_SECONDS, remaining, n);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("[DagExecutor] DAG execution interrupted for region #{}", dag.regionId, e);
        }
    }

    /**
     * 提交单个节点到线程池执行。
     *
     * <p>节点完成后（无论成功/失败），自动触发后继节点的依赖检查和调度。
     * 如果节点失败，后继节点会被标记为失败状态而非执行。
     */
    private void submitNode(@NotNull final RegionDag dag,
                             @NotNull final RegionTickContext context,
                             final int nodeId,
                             final AtomicInteger[] remainingDeps,
                             final CountDownLatch completionLatch,
                             final Map<String, BiConsumer<DagNode, RegionTickContext>> executors) {
        executor.submit(() -> {
            try {
                DagNode node = dag.getNode(nodeId);
                if (node.tryBeginExecution()) {
                    executeNode(node, context, executors);
                }
            } catch (Throwable throwable) {
                LOGGER.error("[DagExecutor] Node #{} execution error in region #{}",
                        nodeId, dag.regionId, throwable);
            } finally {
                // 无论成功/失败，都要 countDown 和触发后继
                completionLatch.countDown();
                // Mili start - fix: check if current node failed before scheduling successors
                DagNode currentNode = dag.getNode(nodeId);
                boolean currentNodeFailed = currentNode.state() == DagNode.NodeState.FAILED;
                // Mili end
                for (int succ : dag.getSuccessors(nodeId)) {
                    if (remainingDeps[succ].decrementAndGet() == 0) {
                        // Mili start - fix: if predecessor failed, mark successor as failed instead of executing
                        if (currentNodeFailed) {
                            DagNode succNode = dag.getNode(succ);
                            succNode.fail();
                            // Recursively mark all downstream nodes as failed
                            markSuccessorsFailed(dag, succ, remainingDeps, completionLatch);
                        } else {
                            submitNode(dag, context, succ, remainingDeps, completionLatch, executors);
                        }
                        // Mili end
                    }
                }
            }
        });
    }

    /**
     * 递归标记所有后继节点为失败状态，避免执行依赖失败节点的任务。
     */
    private void markSuccessorsFailed(@NotNull final RegionDag dag,
                                       final int nodeId,
                                       final AtomicInteger[] remainingDeps,
                                       final CountDownLatch completionLatch) {
        for (int succ : dag.getSuccessors(nodeId)) {
            if (remainingDeps[succ].decrementAndGet() == 0) {
                DagNode succNode = dag.getNode(succ);
                succNode.fail();
                completionLatch.countDown();
                markSuccessorsFailed(dag, succ, remainingDeps, completionLatch);
            }
        }
    }

    private void executeNode(@NotNull final DagNode node,
                              @NotNull final RegionTickContext context,
                              final Map<String, BiConsumer<DagNode, RegionTickContext>> executors) {
        try {
            BiConsumer<DagNode, RegionTickContext> exec = executors.get(node.profile().name());
            if (exec != null) {
                exec.accept(node, context);
                node.complete();
            } else {
                LOGGER.error("[DagExecutor] No executor found for system '{}'", node.profile().name());
                node.fail();
            }
        } catch (Throwable throwable) {
            node.fail();
            LOGGER.error("[DagExecutor] Node {} failed in region #{}", node.profile().name(), context.regionId, throwable);
        }
    }

    /**
     * @deprecated 不再支持单独注册，改为批量注册 {@link #registerAll(Map)}
     */
    @Deprecated
    public void registerSystemExecutor(@NotNull final String systemName,
                                        @NotNull final BiConsumer<DagNode, RegionTickContext> exec) {
        // 保留以兼容旧调用，但不再生效
        LOGGER.warn("[DagExecutor] registerSystemExecutor is deprecated, use registerAll instead");
    }
}
