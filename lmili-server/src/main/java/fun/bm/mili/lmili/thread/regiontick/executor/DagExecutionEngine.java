package fun.bm.mili.lmili.thread.regiontick.executor;

import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import fun.bm.mili.lmili.thread.regiontick.dag.DagExecutionState;
import fun.bm.mili.lmili.thread.scheduler.tick.TickContext;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 非阻塞 DAG 执行引擎。
 *
 * <p>相比原有 {@code RegionDagExecutor} 的核心改进：
 * <ul>
 *   <li><b>零阻塞</b>：不使用 {@link java.util.concurrent.CountDownLatch}，region tick 线程永不阻塞</li>
 *   <li><b>回调驱动</b>：节点完成后自动触发就绪的后继节点</li>
 *   <li><b>CompletableFuture 集成</b>：与现代 Java 异步编程模型无缝衔接</li>
 * </ul>
 *
 * <h3>执行流程：</h3>
 * <pre>
 * 1. 创建 TaskHandle（用于追踪整体完成状态）
 * 2. 获取就绪节点（入度为 0），提交到 Executor
 * 3. 节点执行完毕 → 回调：
 *    a. 递减所有后继节点的入度
 *    b. 如果后继入度降为 0 → 提交执行
 *    c. 标记节点完成
 * 4. 所有节点完成 → TaskHandle 完成
 * </pre>
 *
 * <h3>性能目标：</h3>
 * <ul>
 *   <li>调度延迟：&lt;10μs（vs 原方案 ~100μs）</li>
 *   <li>零 tick 分配：热路径无对象分配</li>
 * </ul>
 */
public final class DagExecutionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DagExecutionEngine.class);

    /**
     * 执行编译后的 DAG。
     *
     * <p>此方法立即返回 {@link TaskHandle}，不阻塞调用者。
     * 调用者可选择：
     * <ul>
     *   <li>忽略返回值（fire-and-forget）</li>
     *   <li>{@link TaskHandle#await()} 等待所有节点完成</li>
     *   <li>{@link TaskHandle#thenRun(Runnable)} 注册完成回调</li>
     *   <li>{@link TaskHandle#completion()} 获取 CompletionStage 用于非阻塞观察</li>
 * </ul>
     *
     * @param dag                  编译后的 DAG（不可变）
     * @param nodeScheduler        节点路由器（按 regionId 路由到正确的执行路径）
     * @param tick                 当前 tick 数
     * @param currentRegionContext 当前 region tick 上下文（用于路由决策）
     * @return TaskHandle 用于追踪完成状态
     */
    public @NotNull TaskHandle execute(
            final @NotNull CompiledDag dag,
            final @NotNull NodeScheduler nodeScheduler,
            final long tick,
            final @NotNull RegionTickContext currentRegionContext
    ) {
        return execute(dag, nodeScheduler, tick, currentRegionContext, null);
    }

    /**
     * 执行编译后的 DAG，并连接到 {@link TickContext} 的 barrier。
     *
     * <p>当所有 DAG 节点完成后，会自动完成 tick barrier。</p>
     *
     * @param dag                  编译后的 DAG（不可变）
     * @param nodeScheduler        节点路由器
     * @param tick                 当前 tick 数
     * @param currentRegionContext 当前 region tick 上下文
     * @param tickCtx              tick 上下文（可为 null）
     * @return TaskHandle 用于追踪完成状态
     */
    public @NotNull TaskHandle execute(
            final @NotNull CompiledDag dag,
            final @NotNull NodeScheduler nodeScheduler,
            final long tick,
            final @NotNull RegionTickContext currentRegionContext,
            final @Nullable TickContext tickCtx
    ) {
        int nodeCount = dag.nodeCount();
        if (nodeCount == 0) {
            if (tickCtx != null) {
                tickCtx.complete();
            }
            return TaskHandle.completed();
        }

        // 创建追踪句柄
        TaskHandle handle = new TaskHandle(nodeCount);

        // 使用 DagExecutionState 替代 int[]，保证原子递减
        DagExecutionState state = new DagExecutionState(dag);

        // 注册到 tick barrier（如果提供了 tick context）
        if (tickCtx != null) {
            tickCtx.register();
        }

        // 初始就绪节点（入度为 0）
        IntList readyNodes = findReadyNodes(dag, state);

        if (readyNodes.isEmpty()) {
            LOGGER.warn("DAG has {} nodes but no ready nodes (possible cycle)", nodeCount);
            handle.fail(new IllegalStateException("DAG has no ready nodes"));
            if (tickCtx != null) {
                tickCtx.reportFailure(new IllegalStateException("DAG has no ready nodes"));
                tickCtx.complete();
            }
            return handle;
        }

        // 派发所有初始就绪节点
        for (int nodeId : readyNodes) {
            submitNode(dag, nodeScheduler, handle, state, nodeId, tick, currentRegionContext, tickCtx);
        }

        return handle;
    }

    /**
     * 提交单个节点执行（通过 NodeScheduler 路由）。
     *
     * <p><b>Mili 关键修复</b>：节点派发改为通过 {@link NodeScheduler}，按 regionId
     * 路由 —— 同 region 节点在当前线程同步执行（保留 tickingRegion 上下文），
     * 跨 region 节点重新入 Folia 调度（由目标 region 的 acquire 路径执行）。</p>
     *
     * <p>节点执行完成后，会自动：
     * <ol>
     *   <li>递减所有后继节点的入度（原子操作）</li>
     *   <li>将新就绪的节点通过 NodeScheduler 派发</li>
     *   <li>标记当前节点完成</li>
     * </ol>
     */
    private void submitNode(
            final CompiledDag dag,
            final NodeScheduler nodeScheduler,
            final TaskHandle handle,
            final DagExecutionState state,
            final int nodeId,
            final long tick,
            final @NotNull RegionTickContext currentRegionContext,
            final @Nullable TickContext tickCtx
    ) {
        if (handle.isCancelled() || state.isCancelled()) {
            return; // 已取消，不再提交新节点
        }

        final long nodeRegionId = dag.regionId(nodeId);

        // 构造节点 body：执行节点逻辑 + 后继节点派发
        final Runnable body = () -> {
            if (handle.isCancelled() || state.isCancelled()) {
                return; // 执行前再次检查取消状态
            }

            try {
                // 创建执行上下文
                DagExecutionContextImpl ctx = new DagExecutionContextImpl(nodeId, tick);

                // 执行节点逻辑
                dag.executor(nodeId).execute(ctx);
            } catch (Throwable t) {
                LOGGER.error("Error executing DAG node {}", nodeId, t);
                state.markFailed();
                handle.fail(t);
                if (tickCtx != null) {
                    tickCtx.reportFailure(t);
                }
                // 节点失败，不再提交后继节点
                return;
            }

            // 检查是否已失败/取消
            if (handle.isCancelled() || state.isCancelled()) {
                return;
            }

            // 节点完成，原子递减后继节点入度
            IntList successors = dag.successors(nodeId);
            for (int i = 0; i < successors.size(); i++) {
                int succId = successors.getInt(i);
                // 原子递减入度 —— 多个 predecessor 可能同时完成
                // 只有最后一个将入度降为 0 的线程才会提交该后继节点
                if (state.decrementDependency(succId)) {
                    // 入度降为 0，派发后继节点（通过 NodeScheduler 路由）
                    submitNode(dag, nodeScheduler, handle, state, succId, tick, currentRegionContext, tickCtx);
                }
            }

            // 标记完成
            handle.signalNodeComplete(nodeId);

            // 所有节点完成时，完成 tick barrier
            if (state.nodeCompleted() && tickCtx != null) {
                tickCtx.complete();
            }
        };

        // 通过 NodeScheduler 派发 —— 按 regionId 自动路由
        try {
            nodeScheduler.dispatch(nodeId, nodeRegionId, body, currentRegionContext);
        } catch (SameRegionNodeScheduler.CrossRegionExecutionException cee) {
            // 内部断言失败：SameRegionNodeScheduler 不应收到跨 region 节点
            LOGGER.error("DAG node routing assertion failed: {}", cee.getMessage(), cee);
            state.markFailed();
            handle.fail(cee);
            if (tickCtx != null) {
                tickCtx.reportFailure(cee);
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to dispatch DAG node {}", nodeId, t);
            state.markFailed();
            handle.fail(t);
            if (tickCtx != null) {
                tickCtx.reportFailure(t);
            }
        }
    }

    /**
     * 查找所有入度为 0 的节点。
     */
    private @NotNull IntList findReadyNodes(final CompiledDag dag, final DagExecutionState state) {
        IntList ready = new IntArrayList();
        for (int i = 0; i < dag.nodeCount(); i++) {
            if (state.remainingDependencies(i) == 0) {
                ready.add(i);
            }
        }
        return ready;
    }

    // ==================== 内部类 ====================

    /**
     * DAG 执行上下文实现。
     */
    private static final class DagExecutionContextImpl implements CompiledDag.DagExecutionContext {
        private final int nodeId;
        private final long currentTick;

        DagExecutionContextImpl(final int nodeId, final long currentTick) {
            this.nodeId = nodeId;
            this.currentTick = currentTick;
        }

        @Override
        public int nodeId() {
            return nodeId;
        }

        @Override
        public long currentTick() {
            return currentTick;
        }
    }

    // ==================== TaskHandle ====================

    /**
     * 任务句柄 — 用于追踪 DAG 执行的整体完成状态。
     *
     * <p>与 {@link CompletableFuture} 类似，但专为 DAG 执行设计：
     * <ul>
     *   <li>轻量级，无额外的 Executor 调度开销</li>
     *   <li>支持异步回调（非阻塞）</li>
     *   <li>支持等待完成（仅用于需要同步等待的场景）</li>
     * </ul>
     */
    public static final class TaskHandle {

        /** 所有节点的 Future */
        private final CompletableFuture<Void> allDone;

        /** 剩余待完成的节点数 */
        private final AtomicInteger remaining;

        /** 总节点数（用于调试） */
        private final int totalNodes;

        /** 节点级完成的 Future 数组 */
        private final CompletableFuture<Void>[] nodeFutures;

        /** 是否已失败 */
        private volatile Throwable failure;

        @SuppressWarnings("unchecked")
        TaskHandle(final int totalNodes) {
            this.totalNodes = totalNodes;
            this.remaining = new AtomicInteger(totalNodes);
            this.nodeFutures = new CompletableFuture[totalNodes];

            // 初始化每个节点的 Future
            for (int i = 0; i < totalNodes; i++) {
                nodeFutures[i] = new CompletableFuture<>();
            }

            // 所有节点完成时的 Future
            this.allDone = CompletableFuture.allOf(nodeFutures);
        }

        /**
         * 创建一个已完成的空 TaskHandle（用于空 DAG）。
         */
        static TaskHandle completed() {
            TaskHandle handle = new TaskHandle(0);
            // 空 DAG：allDone 已经完成（allOf 无参数）
            return handle;
        }

        /**
         * 获取完成阶段 —— 非阻塞观察 DAG 完成状态。
         *
         * <p>DAG 完成必须可观察，不能 fire-and-forget。
         *
         * @return 完成阶段，在所有节点完成时完成
         */
        public @NotNull CompletionStage<Void> completion() {
            return allDone;
        }

        /**
         * 标记节点完成。
         *
         * @param nodeId 完成的节点 ID
         */
        void signalNodeComplete(final int nodeId) {
            CompletableFuture<Void> future = nodeFutures[nodeId];
            if (future != null) {
                future.complete(null);
            }

            // 递减剩余计数
            if (remaining.decrementAndGet() == 0) {
                // 所有节点已完成，allDone 会自动完成
            }
        }

        /**
         * 标记执行失败。
         *
         * @param throwable 失败原因
         */
        void fail(final Throwable throwable) {
            this.failure = throwable;
            // 取消所有未完成节点的 Future
            for (CompletableFuture<Void> future : nodeFutures) {
                future.completeExceptionally(throwable);
            }
        }

        /**
         * 取消执行 —— 标记为已取消，阻止后续节点提交。
         */
        public void cancel() {
            this.failure = new java.util.concurrent.CancellationException("DAG execution cancelled");
            for (CompletableFuture<Void> future : nodeFutures) {
                future.cancel(false);
            }
        }

        /**
         * 检查是否已取消/失败。
         */
        boolean isCancelled() {
            return failure != null || allDone.isCancelled();
        }

        /**
         * 等待所有节点完成（阻塞方法）。
         *
         * <p>仅在必须同步等待时使用，一般推荐使用 {@link #thenRun(Runnable)}。
         *
         * @throws Throwable 如果执行过程中发生异常
         */
        public void await() throws Throwable {
            allDone.join();
            if (failure != null) {
                throw failure;
            }
        }

        /**
         * 注册完成回调（非阻塞）。
         *
         * @param callback 所有节点完成后执行的回调
         * @return this（链式调用）
         */
        public @NotNull TaskHandle thenRun(final @NotNull Runnable callback) {
            allDone.thenRun(callback);
            return this;
        }

        /**
         * 注册每个节点完成的回调。
         *
         * @param nodeId   关注的节点 ID
         * @param callback 节点完成后执行的回调
         * @return this（链式调用）
         */
        public @NotNull TaskHandle whenNodeComplete(final int nodeId, final @NotNull Runnable callback) {
            CompletableFuture<Void> future = nodeFutures[nodeId];
            if (future != null) {
                future.thenRun(callback);
            }
            return this;
        }

        /**
         * 获取总节点数。
         */
        public int totalNodes() {
            return totalNodes;
        }

        /**
         * 获取剩余未完成节点数。
         */
        public int remainingNodes() {
            return remaining.get();
        }

        /**
         * 检查是否所有节点都已完成。
         */
        public boolean isDone() {
            return remaining.get() == 0;
        }

        /**
         * 获取所有节点完成的 Future。
         *
         * <p>用于与现有 CompletableFuture 代码集成。
         */
        public @NotNull CompletableFuture<Void> allDoneFuture() {
            return allDone;
        }
    }
}
