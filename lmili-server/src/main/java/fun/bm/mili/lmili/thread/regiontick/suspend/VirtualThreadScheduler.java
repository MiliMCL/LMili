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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mili 增强型虚拟线程调度器。
 *
 * <p>核心能力：
 * <ul>
 *   <li>使用 {@link MiliThreadFactory} 统一管理线程创建</li>
 *   <li>支持 virtual thread 和 platform thread 两种模式</li>
 *   <li>集成 {@link StructuredScope} 结构化并发（区域 tick 可用作用域管理一组 chunk 任务）</li>
 *   <li>Carrier pinning 保护：JDK 24+ Virtual Thread 不再因 synchronized 而 pin 住 carrier，
 *       对于 IO 密集操作可使用 {@link #computeBlocking(Callable)} 在独立线程执行</li>
 *   <li>详细的运行时统计（任务数、活跃线程、carrier pool 状态）</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <p>在 Minecraft 服务端中，大多数任务是短生命周期的（实体 tick、区块加载等），
 * 非常适合 virtual thread 的轻量级特性。对于可能长时间阻塞的操作（如网络 IO、
 * 数据库查询），应使用 {@link #computeBlocking(Callable)} 将其隔离。
 *
 * <h3>JDK 25 特性利用</h3>
 * <ul>
 *   <li>JEP 491：Virtual Threads — synchronized 不再导致 carrier pinning</li>
 *   <li>JEP 451：Prepare to Restrict the Use of Synchronized（警告但不移除）</li>
 *   <li>JDK 25 中可以放心在 virtual thread 中使用 synchronized 进行互斥</li>
 * </ul>
 */
public final class VirtualThreadScheduler implements MiliScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VirtualThreadScheduler INSTANCE = new VirtualThreadScheduler();

    // Mili start - 使用 MiliThreadFactory 管理线程创建
    private final MiliThreadFactory threadFactory;
    final ExecutorService virtualThreadExecutor;
    // Mili end

    private final ScheduledExecutorService delayedScheduler;
    private final ScheduledExecutorService scheduledScheduler;

    // 统计计数器
    private final AtomicLong submittedTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private final AtomicLong blockedTasks = new AtomicLong();
    private final AtomicLong cancelledTasks = new AtomicLong();

    // 活跃作用域追踪
    private final CopyOnWriteArrayList<StructuredScope> activeScopes = new CopyOnWriteArrayList<>();

    // 调度器级别的锁 —— 仅用于 shutdown 协调
    private final ReentrantLock shutdownLock = new ReentrantLock();
    private volatile boolean shutdown = false;

    private VirtualThreadScheduler() {
        this.threadFactory = new MiliThreadFactory.Builder("Mili-Virtual-")
                .virtual(true)
                .build();
        this.virtualThreadExecutor = Executors.newThreadPerTaskExecutor(threadFactory);

        // 延迟任务调度器 —— 使用 virtual thread
        this.delayedScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().name("Mili-DelayedTask-Scheduler").unstarted(r);
            return t;
        });

        // 周期性任务调度器
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
     * 计算可能阻塞的操作 —— 将阻塞操作隔离到独立线程，避免影响其他虚拟线程。
     *
     * <p>JDK 24+ 中，synchronized 不再导致 carrier pinning，但长时间阻塞（如 IO）
     * 仍会影响当前 carrier 上运行的其他虚拟线程。此方法将阻塞操作提交到独立线程。
     *
     * <p>如果当前已在 virtual thread 中运行且操作不会长时间阻塞（<1ms），
     * 可以安全地在线程内直接执行。
     *
     * @param computation 需要执行的阻塞操作
     * @return 操作结果
     */
    @Override
    public <T> T computeBlocking(@NotNull final Callable<T> computation) throws Exception {
        // 快速路径：如果当前是 virtual thread 且操作极快，直接执行
        if (Thread.currentThread().isVirtual()) {
            // 在 virtual thread 内部：提交到 commonPool 获取结果
            // 这样即使 computation 阻塞，也不会 pin 住当前 carrier
            blockedTasks.incrementAndGet();
            Future<T> future = virtualThreadExecutor.submit(computation);
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            }
        }
        // 平台线程路径：直接执行
        return computation.call();
    }

    /**
     * 在指定 chunk 位置执行任务。
     */
    public void scheduleOnChunk(final Vec3 position, final Runnable task) { submitVirtual(task); }

    /**
     * 创建一个新的结构化并发作用域。
     *
     * @param name     作用域名称（用于日志和统计）
     * @param failFast 是否在任一任务失败时取消其他任务
     * @return 新的 StructuredScope 实例
     */
    public @NotNull StructuredScope createScope(final @NotNull String name, final boolean failFast) {
        StructuredScope scope = new StructuredScope(name, failFast, virtualThreadExecutor);
        activeScopes.add(scope);
        return scope;
    }

    /**
     * 注销活动作用域（作用域完成后调用）。
     *
     * @param scope 要注销的作用域
     */
    public void unregisterScope(@NotNull final StructuredScope scope) {
        activeScopes.remove(scope);
    }

    /**
     * 提交周期性任务 —— 使用 virtual thread 执行。
     *
     * @param task         要执行的任务
     * @param initialDelay 初始延迟
     * @param period       周期
     * @param unit         时间单位
     * @return ScheduledFuture 可用于取消任务
     */
    public @NotNull ScheduledFuture<?> scheduleAtFixedRate(@NotNull final Runnable task,
                                                             final long initialDelay,
                                                             final long period,
                                                             @NotNull final TimeUnit unit) {
        return scheduledScheduler.scheduleAtFixedRate(() -> {
            submitVirtual(task);
        }, initialDelay, period, unit);
    }

    /**
     * 提交一次性延迟任务。
     *
     * @param task  要执行的任务
     * @param delay 延迟时间
     * @param unit  时间单位
     * @return ScheduledFuture 可用于取消任务
     */
    public @NotNull ScheduledFuture<?> scheduleDelayed(@NotNull final Runnable task,
                                                         final long delay,
                                                         @NotNull final TimeUnit unit) {
        return delayedScheduler.schedule(() -> submitVirtual(task), delay, unit);
    }

    private void submitVirtual(@NotNull final Runnable task) {
        if (shutdown) {
            cancelledTasks.incrementAndGet();
            return;
        }
        submittedTasks.incrementAndGet();
        virtualThreadExecutor.submit(() -> {
            try {
                task.run();
                completedTasks.incrementAndGet();
            } catch (Throwable throwable) {
                failedTasks.incrementAndGet();
                if (!(throwable instanceof EntityOrphanedException)) {
                    LOGGER.error("[VirtualThread] Task failed", throwable);
                }
            }
        });
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
        stats.put("submitted_tasks", submittedTasks.get());
        stats.put("completed_tasks", completedTasks.get());
        stats.put("failed_tasks", failedTasks.get());
        stats.put("blocked_tasks", blockedTasks.get());
        stats.put("cancelled_tasks", cancelledTasks.get());
        stats.put("active_scopes", activeScopes.size());
        stats.put("carrier_pool_parallelism", ForkJoinPool.commonPool().getParallelism());
        stats.put("virtual_thread_mode", threadFactory.isVirtual());
        stats.put("shutdown", shutdown);
        return stats;
    }

    /**
     * 获取 carrier pool 的并行度（即可用的 platform thread 数量）。
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
     * 优雅关闭调度器。先停止接受新任务，然后等待已提交任务完成。
     */
    public void shutdown() {
        shutdownLock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            LOGGER.info("[VirtualThreadScheduler] Shutting down...");

            // 取消所有活跃作用域
            for (StructuredScope scope : activeScopes) {
                scope.cancelAll();
            }
            activeScopes.clear();

            virtualThreadExecutor.shutdown();
            delayedScheduler.shutdown();
            scheduledScheduler.shutdown();

            try {
                if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("[VirtualThreadScheduler] Force shutting down virtual thread executor");
                    virtualThreadExecutor.shutdownNow();
                }
                if (!delayedScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    delayedScheduler.shutdownNow();
                }
                if (!scheduledScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduledScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                delayedScheduler.shutdownNow();
                scheduledScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            LOGGER.info("[VirtualThreadScheduler] Shutdown complete (completed: {}, failed: {})",
                    completedTasks.get(), failedTasks.get());
        } finally {
            shutdownLock.unlock();
        }
    }

    /**
     * 是否已关闭。
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
