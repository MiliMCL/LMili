package fun.bm.mili.lmili.thread.regiontick.dag;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class RegionDagExecutor implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final ForkJoinPool executor;
    private final Map<String, BiConsumer<DagNode, RegionTickContext>> systemExecutors = new ConcurrentHashMap<>();
    private volatile boolean closed;

    // Mili start - fix: Accept shared pool instead of creating a new one per tick
    public RegionDagExecutor(final int threadCount, final ForkJoinPool sharedPool) {
        this.executor = sharedPool;
    }
    // Mili end

    public void registerSystemExecutor(final @NotNull String systemName,
                                        final @NotNull BiConsumer<DagNode, RegionTickContext> exec) {
        this.systemExecutors.put(systemName, exec);
    }

    /**
     * 使用 CompletableFuture 链式调度执行 DAG。
     *
     * <p>旧实现使用 LinkedBlockingQueue + busy-wait poll(100ms)，存在：
     * <ul>
     *   <li>忙等待消耗 CPU 周期</li>
     *   <li>每次 poll 有最高 100ms 的固有延迟</li>
     *   <li>activeWorkers 计数器逻辑复杂</li>
     * </ul>
     *
     * <p>新实现使用前驱完成回调立即触发后继就绪节点，零延迟、零忙等待。
     */
    public void executeDag(@NotNull final RegionDag dag, @NotNull final RegionTickContext context) {
        if (closed) throw new IllegalStateException("DagExecutor is closed");
        int n = dag.size();
        if (n == 0) return;

        // in-degree 追踪，使用 AtomicInteger 支持 CAS 递减
        AtomicInteger[] remainingDeps = new AtomicInteger[n];
        for (int i = 0; i < n; i++) {
            remainingDeps[i] = new AtomicInteger(dag.getInDegree(i));
        }

        // 节点完成回调 —— 当节点执行完毕后调用，触发后继节点检查
        CountDownLatch completionLatch = new CountDownLatch(n);

        // 首先提交所有入度为 0 的节点（无依赖，可直接执行）
        for (int i = 0; i < n; i++) {
            if (remainingDeps[i].get() == 0) {
                submitNode(dag, context, i, remainingDeps, completionLatch);
            }
        }

        // 等待所有节点完成
        try {
            if (!completionLatch.await(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[DagExecutor] DAG execution timed out for region #{} (remaining: {})",
                        dag.regionId, completionLatch.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("[DagExecutor] Interrupted for region #{}", dag.regionId, e);
        }
    }

    /**
     * 提交单个节点到线程池执行，完成后自动触发后继节点调度。
     */
    private void submitNode(@NotNull final RegionDag dag,
                             @NotNull final RegionTickContext context,
                             final int nodeId,
                             final AtomicInteger[] remainingDeps,
                             final CountDownLatch completionLatch) {
        executor.submit(() -> {
            try {
                DagNode node = dag.getNode(nodeId);
                if (node.tryBeginExecution()) {
                    executeNode(node, context);
                }
            } catch (Throwable throwable) {
                LOGGER.error("[DagExecutor] Node #{} execution error in region #{}", nodeId, dag.regionId, throwable);
            } finally {
                // 节点完成（无论成功/失败），触发后继
                completionLatch.countDown();
                for (int succ : dag.getSuccessors(nodeId)) {
                    if (remainingDeps[succ].decrementAndGet() == 0) {
                        // 后继所有依赖已满足，立即调度
                        submitNode(dag, context, succ, remainingDeps, completionLatch);
                    }
                }
            }
        });
    }

    private void executeNode(@NotNull final DagNode node, @NotNull final RegionTickContext context) {
        try {
            BiConsumer<DagNode, RegionTickContext> exec = systemExecutors.get(node.profile().name());
            if (exec != null) exec.accept(node, context);
            node.complete();
        } catch (Throwable throwable) {
            node.fail();
            LOGGER.error("[DagExecutor] Node {} failed in region #{}", node, context.regionId, throwable);
        }
    }

    // Mili start - fix: Don't shutdown the shared pool; just signal this executor is done.
    // The shared pool is owned by DagBasedTickExecutor and lives for the server lifetime.
    @Override
    public void close() {
        this.closed = true;
    }
    // Mili end
}
