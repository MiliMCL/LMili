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

    public void executeDag(@NotNull final RegionDag dag, @NotNull final RegionTickContext context) {
        if (closed) throw new IllegalStateException("DagExecutor is closed");
        int n = dag.size();
        if (n == 0) return;

        int[] topoOrder = dag.topologicalSort();
        AtomicInteger[] inDegrees = new AtomicInteger[n];
        for (int i = 0; i < n; i++) inDegrees[i] = new AtomicInteger(dag.getInDegree(i));

        // Mili start - fix: Use blocking queue to avoid busy-wait when dependencies aren't ready
        LinkedBlockingQueue<Integer> workQueue = new LinkedBlockingQueue<>();
        for (int nodeId : topoOrder) if (inDegrees[nodeId].get() == 0) workQueue.add(nodeId);

        int workerCount = Math.min(n, executor.getParallelism());
        CountDownLatch completionLatch = new CountDownLatch(n);
        // Track active workers to know when to stop feeding the queue
        AtomicInteger activeWorkers = new AtomicInteger(workerCount);

        for (int w = 0; w < workerCount; w++) {
            executor.submit(() -> {
                try {
                    while (!closed) {
                        Integer nodeId = workQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (nodeId == null) {
                            // No work available - check if all workers are idle and no work remains
                            if (activeWorkers.get() == 0 && workQueue.isEmpty()) break;
                            continue;
                        }
                        DagNode node = dag.getNode(nodeId);
                        if (!node.tryBeginExecution()) continue;

                        executeNode(node, context);

                        for (int succ : dag.getSuccessors(nodeId)) {
                            if (inDegrees[succ].decrementAndGet() == 0) workQueue.add(succ);
                        }
                        completionLatch.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    activeWorkers.decrementAndGet();
                }
            });
        }

        try {
            if (!completionLatch.await(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[DagExecutor] DAG execution timed out for region #{} (remaining: {})",
                        dag.regionId, completionLatch.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("[DagExecutor] Interrupted for region #{}", dag.regionId, e);
        }
        // Mili end
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
