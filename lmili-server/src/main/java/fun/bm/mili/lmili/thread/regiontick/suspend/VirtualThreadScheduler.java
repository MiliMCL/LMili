package fun.bm.mili.lmili.thread.regiontick.suspend;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.api.EntityOrphanedException;
import fun.bm.mili.lmili.thread.regiontick.api.EntityTaskContext;
import fun.bm.mili.lmili.thread.regiontick.api.MiliScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mili 增强型虚拟线程调度器。
 *
 * <p>核心能力：
 * <ul>
 *   <li>使用 {@link MiliThreadFactory} 统一管理线程创建</li>
 *   <li>支持 virtual thread 和 platform thread 两种模式</li>
 *   <li>集成 {@link StructuredScope} 结构化并发</li>
 *   <li>Carrier pinning 安全：JDK 24+ Virtual Thread 不再因 synchronized 而 pin 住 carrier</li>
 *   <li>详细的运行时统计（任务数、活跃线程、carrier pool 状态）</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <p>在 Minecraft 服务端中，大多数任务是短生命周期的（实体 tick、区块加载等），
 * 非常适合 virtual thread 的轻量级特性。对于可能长时间阻塞的操作（如网络 IO、
 * 数据库查询），应使用 {@link #computeBlocking(Callable)} 将其隔离到 platform thread 执行。
 */
public final class VirtualThreadScheduler implements MiliScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VirtualThreadScheduler INSTANCE = new VirtualThreadScheduler();

    // 阻塞操作隔离池 —— 独立于 virtual thread executor，避免阻塞 carrier
    private static final ExecutorService BLOCKING_TASK_POOL = new ThreadPoolExecutor(
            0, Math.max(2, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "Mili-Blocking-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final MiliThreadFactory threadFactory;
    final ExecutorService virtualThreadExecutor;

    private final ScheduledExecutorService delayedScheduler;
    private final ScheduledExecutorService scheduledScheduler;

    // 统计计数器 —— 使用 LongAdder 提高高并发下写入性能
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final LongAdder failedTasks = new LongAdder();
    private final LongAdder blockedTasks = new LongAdder();
    private final LongAdder cancelledTasks = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();

    // 活跃作用域追踪
    private final CopyOnWriteArrayList<StructuredScope> activeScopes = new CopyOnWriteArrayList<>();

    private volatile boolean shutdown = false;

    private VirtualThreadScheduler() {
        this.threadFactory = new MiliThreadFactory.Builder("Mili-Virtual-")
                .virtual(true)
                .build();
        this.virtualThreadExecutor = Executors.newThreadPerTaskExecutor(threadFactory);

        this.delayedScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().name("Mili-DelayedTask-Scheduler").unstarted(r);
            return t;
        });

        this.scheduledScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().name("Mili-ScheduledTask-Scheduler").unstarted(r);
            return t;
        });

        LOGGER.info("[VirtualThreadScheduler] Initialized (carrier pool: {}, virtual threads enabled)",
                ForkJoinPool.commonPool().getParallelism());
    }

    public static VirtualThreadScheduler getInstance() { return INSTANCE; }

    @Override
    public @NotNull EntityScheduler forEntity(@NotNull final Entity entity) {
        return new EntitySchedulerImpl(entity);
    }

    @Override
    public void runAt(@NotNull final ServerLevel level, @NotNull final Vec3 position,
                      @NotNull final ThrowingConsumer<EntityTaskContext> task) {
        submitVirtual(() -> {
            long regionId = resolveRegionId(level, position);
            try {
                task.accept(new EntityTaskContext(-1, level, regionId));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void runAsync(@NotNull final Runnable task) { submitVirtual(task); }

    /**
     * 在平台线程上执行可能阻塞的操作，避免影响 virtual thread carrier。
     *
     * <p>如果当前已在 platform thread 上，直接执行避免不必要的调度开销。
     * 如果当前在 virtual thread 上，提交到 {@link #BLOCKING_TASK_POOL} 独立线程池执行。
     *
     * @param computation 需要执行的阻塞操作
     * @return 操作结果
     */
    @Override
    public <T> T computeBlocking(@NotNull final Callable<T> computation) throws Exception {
        if (!Thread.currentThread().isVirtual()) {
            // 已在平台线程上，直接执行
            return computation.call();
        }

        // Virtual thread 路径：提交到独立阻塞池
        blockedTasks.increment();
        Future<T> future = BLOCKING_TASK_POOL.submit(computation);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    /**
     * 在指定 chunk 位置执行任务。
     */
    public void scheduleOnChunk(final Vec3 position, final Runnable task) { submitVirtual(task); }

    /**
     * 创建一个新的结构化并发作用域。
     */
    public @NotNull StructuredScope createScope(final @NotNull String name, final boolean failFast) {
        StructuredScope scope = new StructuredScope(name, failFast, virtualThreadExecutor);
        activeScopes.add(scope);
        return scope;
    }

    /**
     * 注销活动作用域。
     */
    public void unregisterScope(@NotNull final StructuredScope scope) {
        activeScopes.remove(scope);
    }

    /**
     * 提交周期性任务 —— 使用 virtual thread 执行。
     */
    public @NotNull ScheduledFuture<?> scheduleAtFixedRate(@NotNull final Runnable task,
                                                             final long initialDelay,
                                                             final long period,
                                                             @NotNull final TimeUnit unit) {
        return scheduledScheduler.scheduleAtFixedRate(() -> submitVirtual(task), initialDelay, period, unit);
    }

    /**
     * 提交一次性延迟任务。
     */
    public @NotNull ScheduledFuture<?> scheduleDelayed(@NotNull final Runnable task,
                                                         final long delay,
                                                         @NotNull final TimeUnit unit) {
        return delayedScheduler.schedule(() -> submitVirtual(task), delay, unit);
    }

    private void submitVirtual(@NotNull final Runnable task) {
        if (shutdown) {
            cancelledTasks.increment();
            return;
        }
        submittedTasks.increment();
        try {
            virtualThreadExecutor.submit(() -> {
                try {
                    task.run();
                    completedTasks.increment();
                } catch (Throwable throwable) {
                    failedTasks.increment();
                    if (!(throwable instanceof EntityOrphanedException)) {
                        LOGGER.error("[VirtualThread] Task failed", throwable);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedTasks.increment();
            cancelledTasks.increment();
            LOGGER.error("[VirtualThread] Task rejected — executor is shutting down");
        }
    }

    /**
     * 内部实体调度器实现。
     */
    private static final class EntitySchedulerImpl implements EntityScheduler {
        private final Entity entity;
        private final ServerLevel level;
        private final long regionId;

        EntitySchedulerImpl(final Entity entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
            this.level = (ServerLevel) entity.level();
            this.regionId = resolveRegionId(level, entity.position());
        }

        @Override
        public void run(@NotNull final ThrowingConsumer<EntityTaskContext> task) {
            VirtualThreadScheduler scheduler = VirtualThreadScheduler.getInstance();
            scheduler.submitVirtual(() -> {
                if (!entity.isAlive()) throw new RuntimeException(new EntityOrphanedException(
                        entity.getId(), entity.getName().getString(), EntityOrphanedException.Reason.REMOVED));
                try {
                    task.accept(new EntityTaskContext(entity.getId(), level, regionId));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public void runDelayed(@NotNull final ThrowingConsumer<EntityTaskContext> task, final long delayTicks) {
            VirtualThreadScheduler.getInstance().delayedScheduler.schedule(
                    () -> run(task), delayTicks * 50L, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isOnOwningThread() {
            return TickThread.isTickThreadFor(level, ((int) entity.getX()) >> 4, ((int) entity.getZ()) >> 4);
        }

        @Override
        public void ensureOnOwningThread() throws EntityOrphanedException {
            if (!entity.isAlive()) throw new EntityOrphanedException(
                    entity.getId(), entity.getName().getString(), EntityOrphanedException.Reason.REMOVED);
        }
    }

    private static long resolveRegionId(final ServerLevel level, final Vec3 position) {
        if (position == null) return -1;
        int cx = net.minecraft.core.BlockPos.containing(position).getX() >> 4;
        int cz = net.minecraft.core.BlockPos.containing(position).getZ() >> 4;
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    /**
     * 获取调度器当前统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("submitted_tasks", submittedTasks.sum());
        stats.put("completed_tasks", completedTasks.sum());
        stats.put("failed_tasks", failedTasks.sum());
        stats.put("blocked_tasks", blockedTasks.sum());
        stats.put("cancelled_tasks", cancelledTasks.sum());
        stats.put("rejected_tasks", rejectedTasks.sum());
        stats.put("active_scopes", activeScopes.size());
        stats.put("carrier_pool_parallelism", ForkJoinPool.commonPool().getParallelism());
        stats.put("virtual_thread_mode", threadFactory.isVirtual());
        stats.put("shutdown", shutdown);
        return stats;
    }

    /**
     * 获取 carrier pool 的并行度。
     */
    public int getCarrierParallelism() {
        return ForkJoinPool.commonPool().getParallelism();
    }

    /**
     * 获取活跃作用域数量。
     */
    public int getActiveScopeCount() {
        return activeScopes.size();
    }

    /**
     * 优雅关闭调度器。
     */
    public void shutdown() {
        if (shutdown) return;
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
            LOGGER.info("[VirtualThreadScheduler] Shutting down...");

            for (StructuredScope scope : activeScopes) {
                scope.cancelAll();
            }
            activeScopes.clear();

            virtualThreadExecutor.shutdown();
            delayedScheduler.shutdown();
            scheduledScheduler.shutdown();
            BLOCKING_TASK_POOL.shutdown();

            try {
                if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("[VirtualThreadScheduler] Force shutting down virtual thread executor");
                    virtualThreadExecutor.shutdownNow();
                }
                if (!delayedScheduler.awaitTermination(1, TimeUnit.SECONDS)) delayedScheduler.shutdownNow();
                if (!scheduledScheduler.awaitTermination(1, TimeUnit.SECONDS)) scheduledScheduler.shutdownNow();
                if (!BLOCKING_TASK_POOL.awaitTermination(1, TimeUnit.SECONDS)) BLOCKING_TASK_POOL.shutdownNow();
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                delayedScheduler.shutdownNow();
                scheduledScheduler.shutdownNow();
                BLOCKING_TASK_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }

            LOGGER.info("[VirtualThreadScheduler] Shutdown complete (completed: {}, failed: {})",
                    completedTasks.sum(), failedTasks.sum());
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }
}
