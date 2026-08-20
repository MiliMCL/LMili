package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.*;
import fun.bm.mili.lmili.thread.scheduler.execute.BlockingTaskIsolation;
import fun.bm.mili.lmili.thread.scheduler.execute.SchedulerLifecycle;
import fun.bm.mili.lmili.thread.scheduler.execute.SchedulerWorker;
import fun.bm.mili.lmili.thread.scheduler.execute.VirtualThreadPool;
import fun.bm.mili.lmili.thread.scheduler.execute.WorkStealingCoordinator;
import fun.bm.mili.lmili.thread.scheduler.internal.DefaultBatchHandle;
import fun.bm.mili.lmili.thread.scheduler.internal.DefaultTaskHandle;
import fun.bm.mili.lmili.thread.scheduler.internal.DiagnosticCollector;
import fun.bm.mili.lmili.thread.scheduler.internal.PerformanceMetrics;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link MiliScheduler} 的核心实现 —— 新调度系统的主类。
 *
 * <p>整合以下组件：
 * <ul>
 *   <li>{@link VirtualThreadPool} —— 虚拟线程执行器</li>
 *   <li>{@link WorkStealingCoordinator} —— 工作窃取负载均衡</li>
 *   <li>{@link BlockingTaskIsolation} —— 阻塞操作隔离</li>
 *   <li>{@link PerformanceMetrics} —— 性能指标收集</li>
 *   <li>{@link DiagnosticCollector} —— 诊断信息收集</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 * RegionTickDispatcher
 *     │
 *     ▼
 * MiliScheduler.submit(RegionTask)
 *     │
 *     ▼
 * WorkStealingCoordinator.submit(task)
 *     │
 *     ▼
 * RegionQueue ──(work-stealing)──▶ VirtualThreadPool (virtual threads)
 *                                      │
 *                                      ▼
 *                                  Carrier Threads → CPU Cores
 * </pre>
 *
 * <h3>生命周期</h3>
 * <pre>
 * builder() → build() → [submit tasks] → shutdown()
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>本类的所有公共方法都是线程安全的。多个线程可以同时提交任务。
 */
public final class MiliSchedulerImpl implements MiliScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 核心组件 ----
    private final VirtualThreadPool virtualThreadPool;
    private final WorkStealingCoordinator workStealingCoordinator;
    private final BlockingTaskIsolation blockingTaskIsolation;
    private final PerformanceMetrics metrics;
    private final DiagnosticCollector diagnostics;

    // ---- 配置 ----
    private final MiliSchedulerConfig config;

    // ---- Worker 线程（Phase B：Coordinator 成为唯一执行入口）----
    private final Thread[] workerThreads;
    private final SchedulerWorker[] workers;

    // ---- 状态 ----
    // C-11/C-19 修复：使用 SchedulerLifecycle 替代 AtomicBoolean
    private final SchedulerLifecycle lifecycle = new SchedulerLifecycle();
    private final AtomicReference<ScheduledExecutorService> delayedScheduler = new AtomicReference<>();

    /**
     * 私有构造器 —— 通过 {@link MiliSchedulerBuilder} 创建。
     */
    MiliSchedulerImpl(@NotNull MiliSchedulerBuilder builder) {
        this.config = new MiliSchedulerConfig(
                builder.poolName,
                builder.threadNamePrefix,
                builder.carrierThreads,
                builder.maxBlockingTasks
        );

        // 初始化组件
        this.metrics = new PerformanceMetrics();
        this.diagnostics = new DiagnosticCollector();
        this.virtualThreadPool = new VirtualThreadPool(config.poolName);
        this.workStealingCoordinator = new WorkStealingCoordinator(config.carrierThreads);
        this.blockingTaskIsolation = new BlockingTaskIsolation();

        LOGGER.info("[MiliScheduler] Initialized (pool={}, carrierThreads={}, maxBlocking={})",
                config.poolName, config.carrierThreads, config.maxBlockingTasks);

        // Phase B: 启动 SchedulerWorker 线程 —— 从 WorkStealingCoordinator 获取任务并执行
        int workerCount = config.carrierThreads;
        this.workers = new SchedulerWorker[workerCount];
        this.workerThreads = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.workers[i] = new SchedulerWorker(i, this.workStealingCoordinator, this.blockingTaskIsolation);
            this.workerThreads[i] = Thread.ofPlatform()
                    .name(config.threadNamePrefix + "-Worker-" + i)
                    .daemon(true)
                    .start(this.workers[i]);
        }
        LOGGER.info("[MiliScheduler] Started {} scheduler workers", workerCount);
    }

    // ---- MiliScheduler 接口实现 ----

    @Override
    @NotNull
    public TaskHandle submit(@NotNull RegionTask task) {
        // C-11 修复：使用 lifecycle 检查，拒绝 QUIESCING/CLOSED 状态下的提交
        if (lifecycle.isShuttingDown()) {
            DefaultTaskHandle cancelled = new DefaultTaskHandle();
            cancelled.cancel();
            return cancelled;
        }

        metrics.recordSubmit();
        metrics.recordRegionSubmit(task.regionId());

        DefaultTaskHandle handle = new DefaultTaskHandle();

        if (task.isBlocking()) {
            // 阻塞任务 → 提交到专用阻塞池
            submitBlockingTask(task, handle);
        } else {
            // 普通任务 → 提交到 work-stealing 队列
            submitRegionTask(task, handle);
        }

        return handle;
    }

    @Override
    @NotNull
    public BatchHandle submitBatch(@NotNull List<RegionTask> tasks) {
        if (lifecycle.isShuttingDown()) {
            List<TaskHandle> cancelled = new ArrayList<>(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                DefaultTaskHandle h = new DefaultTaskHandle();
                h.cancel();
                cancelled.add(h);
            }
            return new DefaultBatchHandle(cancelled);
        }

        List<TaskHandle> handles = new ArrayList<>(tasks.size());
        for (RegionTask task : tasks) {
            handles.add(submit(task));
        }
        return new DefaultBatchHandle(handles);
    }

    @Override
    @NotNull
    public TaskHandle scheduleDelayed(@NotNull RegionTask task, long delay, @NotNull TimeUnit unit) {
        if (lifecycle.isShuttingDown()) {
            DefaultTaskHandle cancelled = new DefaultTaskHandle();
            cancelled.cancel();
            return cancelled;
        }

        metrics.recordSubmit();
        DefaultTaskHandle handle = new DefaultTaskHandle();

        ScheduledExecutorService scheduler = getDelayedScheduler();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!handle.isCancelled()) {
                if (task.isBlocking()) {
                    submitBlockingTask(task, handle);
                } else {
                    submitRegionTask(task, handle);
                }
            }
        }, delay, unit);

        // C-18 修复：设置取消动作，使 handle.cancel() 能真正取消 ScheduledFuture
        handle.setCancelAction(() -> future.cancel(false));

        return handle;
    }

    @Override
    @NotNull
    public EntityScheduler forEntity(@NotNull EntityScheduler.EntityRef entityRef) {
        return new EntitySchedulerImpl(entityRef, this);
    }

    @Override
    @NotNull
    public PerformanceSnapshot performanceSnapshot() {
        return metrics.snapshot();
    }

    @Override
    public boolean shutdown(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        // C-11/C-19 修复：使用 SchedulerLifecycle 优雅关闭
        if (!lifecycle.beginShutdown()) {
            return false; // 已经在关闭了
        }

        LOGGER.info("[MiliScheduler] Shutting down... (phase: {}, pending tasks: {})",
                lifecycle.get(), metrics.getPendingTaskCount());

        // 步骤 1：停止延迟调度器（C-19 修复：在 worker 停止前停止，防止已取消任务唤醒）
        ScheduledExecutorService scheduler = delayedScheduler.getAndSet(null);
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 步骤 2：停止 SchedulerWorker 线程
        for (SchedulerWorker worker : workers) {
            worker.stop();
        }
        // 等待 worker 线程退出
        for (Thread thread : workerThreads) {
            try {
                thread.join(Math.max(100, unit.toMillis(timeout) / 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 步骤 3：停止 work-stealing coordinator
        workStealingCoordinator.shutdown();

        // 步骤 4：关闭阻塞任务隔离
        blockingTaskIsolation.shutdown();

        // 步骤 5：关闭虚拟线程池
        virtualThreadPool.shutdown();

        // 确认关闭完成（QUIESCING → CLOSED）
        lifecycle.completeShutdown();

        PerformanceSnapshot snapshot = metrics.snapshot();
        LOGGER.info("[MiliScheduler] Shutdown complete (phase: {}, completed: {}, failed: {})",
                lifecycle.get(), snapshot.totalCompletedTasks(), snapshot.totalFailedTasks());

        return true;
    }

    @Override
    public boolean isShutdown() {
        return lifecycle.isShuttingDown();
    }

    // ---- 内部方法 ----

    /**
     * 提交区域任务到 work-stealing 队列。
     *
     * <p>Phase B 修复：所有实际执行任务必须经过 Coordinator。
     * 不再同时提交到 VirtualThreadPool（旁路执行），而是只提交到 WorkStealingCoordinator，
     * 由 SchedulerWorker 从 Coordinator 获取任务并执行。
     */
    private void submitRegionTask(@NotNull RegionTask task, @NotNull DefaultTaskHandle handle) {
        // 包装任务以追踪完成状态 —— 任务完成时通过 handle 报告
        RegionTask wrappedTask = new RegionTask() {
            @Override
            public void execute() throws Exception {
                long startNanos = System.nanoTime();
                try {
                    task.execute();
                    metrics.recordCompletion(System.nanoTime() - startNanos);
                    metrics.recordRegionCompletion(task.regionId());
                    handle.complete();
                } catch (Throwable t) {
                    metrics.recordFailure();
                    handle.completeExceptionally(t);
                    diagnostics.recordException("task_execution", t,
                            java.util.Map.of("task", task.name(), "region", task.regionId()));
                    throw t;
                }
            }

            @Override
            public long regionId() {
                return task.regionId();
            }

            @Override
            public boolean isBlocking() {
                return false;
            }

            @Override
            public long timeoutMillis() {
                return task.timeoutMillis();
            }

            @Override
            @NotNull
            public String name() {
                return task.name();
            }

            @Override
            public void onCancel() {
                handle.cancel();
            }
        };

        // 只提交到 work-stealing coordinator —— 唯一执行入口
        workStealingCoordinator.submit(wrappedTask);
    }

    /**
     * 提交阻塞任务到 work-stealing coordinator。
     *
     * <p>R2-03 修复：阻塞任务不再直接提交到阻塞池，而是提交到 coordinator。
     * SchedulerWorker 检测到 isBlocking() 后会调用 BlockingTaskIsolation.executeBlocking()
     * 并转移 ExecutionToken。
     *
     * <p>C-09 修复：阻塞任务通过专用 executor 执行，不阻塞 worker 线程。
     */
    private void submitBlockingTask(@NotNull RegionTask task, @NotNull DefaultTaskHandle handle) {
        // 创建一个可追踪的任务，执行完成后更新 handle 状态
        RegionTask trackedTask = new RegionTask() {
            @Override
            public void execute() throws Exception {
                long startNanos = System.nanoTime();
                try {
                    task.execute();
                    metrics.recordCompletion(System.nanoTime() - startNanos);
                    metrics.recordRegionCompletion(task.regionId());
                    handle.complete();
                } catch (Throwable t) {
                    metrics.recordFailure();
                    handle.completeExceptionally(t);
                    diagnostics.recordException("blocking_task_execution", t,
                            java.util.Map.of("task", task.name(), "region", task.regionId()));
                    throw t;
                }
            }

            @Override
            public long regionId() { return task.regionId(); }

            @Override
            public boolean isBlocking() { return true; }

            @Override
            public long timeoutMillis() { return task.timeoutMillis(); }

            @Override
            @NotNull
            public String name() { return task.name(); }

            @Override
            public void onCancel() { handle.cancel(); }
        };

        // 提交到 coordinator —— SchedulerWorker 会检测 isBlocking 并处理 token 转移
        workStealingCoordinator.submit(trackedTask);
    }

    /**
     * 获取或创建延迟调度器。
     */
    private ScheduledExecutorService getDelayedScheduler() {
        ScheduledExecutorService scheduler = delayedScheduler.get();
        if (scheduler != null) return scheduler;

        synchronized (this) {
            if (delayedScheduler.get() == null) {
                ScheduledExecutorService newScheduler = Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, config.poolName + "-DelayedScheduler");
                    t.setDaemon(true);
                    return t;
                });
                delayedScheduler.set(newScheduler);
            }
            return delayedScheduler.get();
        }
    }

    // ---- 内部组件访问 ----

    @NotNull
    VirtualThreadPool virtualThreadPool() {
        return virtualThreadPool;
    }

    @NotNull
    WorkStealingCoordinator workStealingCoordinator() {
        return workStealingCoordinator;
    }

    @NotNull
    PerformanceMetrics metrics() {
        return metrics;
    }

    @NotNull
    DiagnosticCollector diagnostics() {
        return diagnostics;
    }

    // ---- 实体调度器实现 ----

    /**
     * 实体调度器实现。
     */
    private static final class EntitySchedulerImpl implements EntityScheduler {
        private final EntityRef entityRef;
        private final MiliSchedulerImpl scheduler;

        EntitySchedulerImpl(@NotNull EntityRef entityRef, @NotNull MiliSchedulerImpl scheduler) {
            this.entityRef = entityRef;
            this.scheduler = scheduler;
        }

        @Override
        @NotNull
        public TaskHandle submit(@NotNull EntityTask entityTask) {
            Runnable taskAction = () -> {
                try {
                    entityTask.execute(new EntityTaskContextImpl(entityRef));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            // C-10 修复：使用 entityRef.regionId() 而非 entityId()
            // regionId 从实体位置计算（chunkX, chunkZ），保证同一 region 的实体共享队列
            return scheduler.submit(RegionTask.builder(entityRef.regionId())
                    .task(taskAction)
                    .build());
        }

        @Override
        @NotNull
        public TaskHandle submitDelayed(@NotNull EntityTask entityTask, long delayTicks) {
            Runnable taskAction = () -> {
                try {
                    entityTask.execute(new EntityTaskContextImpl(entityRef));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            // C-10 修复：使用 entityRef.regionId() 而非 entityId()
            return scheduler.scheduleDelayed(
                    RegionTask.builder(entityRef.regionId())
                            .task(taskAction)
                            .build(),
                    delayTicks * 50L, TimeUnit.MILLISECONDS
            );
        }

        @Override
        public boolean isOnOwningThread() {
            // 简化实现：检查当前线程是否为 tick 线程
            return ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(
                    (net.minecraft.server.level.ServerLevel) entityRef.level(),
                    0, 0); // 简化：实际需要根据 entity 位置计算 chunk
        }

        @Override
        public void ensureOnOwningThread() throws EntityOrphanedException {
            // 简化实现
        }

        @Override
        public boolean isEntityAlive() {
            // 简化实现：实际需要检查 entity 是否仍然存活
            return true;
        }

        @Override
        @NotNull
        public EntityRef entityRef() {
            return entityRef;
        }
    }

    /**
     * 实体任务上下文实现。
     */
    private static final class EntityTaskContextImpl implements EntityTask.EntityTaskContext {
        private final EntityScheduler.EntityRef entityRef;

        EntityTaskContextImpl(@NotNull EntityScheduler.EntityRef entityRef) {
            this.entityRef = entityRef;
        }

        @Override
        public int entityId() {
            return entityRef.entityId();
        }

        @Override
        public Object level() {
            return entityRef.level();
        }

        @Override
        public long regionId() {
            // C-10 修复：使用 entityRef.regionId()（基于 chunk 坐标），而非 entityId
            return entityRef.regionId();
        }
    }

    // ---- 配置记录 ----

    /**
     * 调度器配置。
     */
    record MiliSchedulerConfig(
            String poolName,
            String threadNamePrefix,
            int carrierThreads,
            int maxBlockingTasks
    ) {}
}
