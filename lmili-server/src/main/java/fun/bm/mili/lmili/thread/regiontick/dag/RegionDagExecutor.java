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
    private final ExecutorService executor;
    private final Map<String, BiConsumer<DagNode, RegionTickContext>> systemExecutors = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RegionDagExecutor(final int threadCount) {
        this.executor = Executors.newWorkStealingPool(threadCount);
    }

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

        ConcurrentLinkedDeque<Integer> workQueue = new ConcurrentLinkedDeque<>();
        for (int nodeId : topoOrder) if (inDegrees[nodeId].get() == 0) workQueue.add(nodeId);

        int workerCount = Math.min(n, ((ForkJoinPool) executor).getParallelism());
        CountDownLatch completionLatch = new CountDownLatch(n);

        for (int w = 0; w < workerCount; w++) {
            executor.submit(() -> {
                while (!closed) {
                    Integer nodeId = workQueue.poll();
                    if (nodeId == null) {
                        if (completionLatch.getCount() == 0) break;
                        Thread.yield();
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
            });
        }

        try {
            completionLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("[DagExecutor] Interrupted for region #{}", dag.regionId, e);
        }
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

    @Override
    public void close() {
        this.closed = true;
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) this.executor.shutdownNow();
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
