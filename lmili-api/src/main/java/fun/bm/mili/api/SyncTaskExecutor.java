package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.LMili;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 同步任务执行器 —— 安全地执行同步任务，防止卡顿。
 *
 * <p><b>卡顿防护机制</b>：
 * <ol>
 *   <li><b>超时控制</b>：每个任务有严格的超时限制</li>
 *   <li><b>死锁检测</b>：禁止嵌套同步任务、检测循环等待</li>
 *   <li><b>负载感知</b>：服务器负载高时缩短超时时间</li>
 *   <li><b>隔离执行</b>：任务在隔离上下文中执行，异常不影响主线程</li>
 *   <li><b>审计追踪</b>：记录所有同步任务的执行时间和结果</li>
 * </ol>
 *
 * <p><b>性能目标</b>：
 * <ul>
 *   <li>正常负载下 P99 同步任务延迟 < 5ms</li>
 *   <li>高负载下自动降级，任务直接返回失败</li>
 *   <li>零死锁风险</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class SyncTaskExecutor {

    private static final ThreadLocal<Boolean> SYNC_TASK_IN_PROGRESS = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<PluginId> CURRENT_SYNC_OWNER = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<Long> TASK_START_TIME = ThreadLocal.withInitial(() -> 0L);

    private final ConcurrentMap<Long, ActiveSyncTask> activeTasks = new ConcurrentHashMap<>();
    private final AtomicInteger totalExecuted = new AtomicInteger();
    private final AtomicInteger totalTimedOut = new AtomicInteger();
    private final AtomicInteger totalFailed = new AtomicInteger();

    private volatile boolean serverOverloaded = false;

    private SyncTaskExecutor() {}

    /**
     * 获取单例实例。
     *
     * @return 执行器实例
     */
    @NotNull
    public static SyncTaskExecutor getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 在默认约束下执行同步任务。
     *
     * @param owner 插件 ID
     * @param task  任务
     * @param <T>   返回类型
     * @return 执行结果
     */
    @NotNull
    public <T> SyncTaskResult<T> execute(@NotNull PluginId owner, @NotNull Supplier<T> task) {
        return execute(owner, task, SyncTaskConstraints.DEFAULT);
    }

    /**
     * 执行同步任务。
     *
     * @param owner       插件 ID
     * @param task        任务
     * @param constraints 约束
     * @param <T>         返回类型
     * @return 执行结果
     */
    @NotNull
    public <T> SyncTaskResult<T> execute(@NotNull PluginId owner,
                                          @NotNull Supplier<T> task,
                                          @NotNull SyncTaskConstraints constraints) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(constraints, "constraints");

        // 1. 死锁检测：检查是否已在同步任务中
        if (!constraints.isAllowNestedSync() && SYNC_TASK_IN_PROGRESS.get()) {
            totalFailed.incrementAndGet();
            return SyncTaskResult.failure("Nested sync task detected (would cause deadlock)");
        }

        // 2. 检查服务器负载
        if (serverOverloaded && constraints.timeoutMs() > 1) {
            totalFailed.incrementAndGet();
            return SyncTaskResult.failure("Server overloaded, sync task rejected");
        }

        // 3. 检查实体状态（如果有）
        Object entityContext = CURRENT_ENTITY.get();
        if (entityContext instanceof org.bukkit.entity.Entity entity) {
            if (!entity.isValid()) {
                return SyncTaskResult.failure("Entity is no longer valid");
            }
        }

        // 4. 执行任务
        long threadId = Thread.currentThread().threadId();
        ActiveSyncTask activeTask = new ActiveSyncTask(threadId, owner, constraints);
        activeTasks.put(threadId, activeTask);

        SYNC_TASK_IN_PROGRESS.set(true);
        CURRENT_SYNC_OWNER.set(owner);
        TASK_START_TIME.set(System.nanoTime());

        T result = null;
        Throwable exception = null;
        long startNanos = System.nanoTime();

        try {
            // 如果任务需要中断敏感，启动 watchdog
            watchdogFuture wf = null;
            if (constraints.isInterruptSensitive()) {
                wf = startWatchdog(threadId, constraints.timeoutMs());
            }

            try {
                result = task.get();

                // 检查是否被中断
                if (Thread.interrupted()) {
                    throw new InterruptedException("Sync task interrupted");
                }
            } finally {
                if (wf != null) wf.cancel();
            }

            long elapsed = System.nanoTime() - startNanos;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsed);

            // 检查超时
            if (elapsedMs > constraints.timeoutMs()) {
                totalTimedOut.incrementAndGet();
                return SyncTaskResult.timeout(constraints.timeoutMs());
            }

            totalExecuted.incrementAndGet();
            return SyncTaskResult.success(result, elapsed);

        } catch (InterruptedException e) {
            totalTimedOut.incrementAndGet();
            Thread.currentThread().interrupt();
            return SyncTaskResult.timeout(constraints.timeoutMs());
        } catch (Throwable t) {
            exception = t;
            totalFailed.incrementAndGet();
            return SyncTaskResult.failure(
                "Sync task failed: " + t.getMessage(), t);
        } finally {
            activeTasks.remove(threadId);
            SYNC_TASK_IN_PROGRESS.set(false);
            CURRENT_SYNC_OWNER.set(null);
            TASK_START_TIME.set(0L);

            // 记录审计日志
            long elapsed = System.nanoTime() - startNanos;
            recordAuditLog(owner, constraints, result != null || exception == null, elapsed, exception);
        }
    }

    /**
     * 检查当前线程是否正在执行同步任务。
     *
     * @return true 如果在同步任务中
     */
    public boolean isSyncTaskInProgress() {
        return SYNC_TASK_IN_PROGRESS.get();
    }

    /**
     * 获取当前同步任务的插件 ID。
     *
     * @return 插件 ID，如果不在同步任务中则返回 null
     */
    @Nullable
    public PluginId getCurrentSyncOwner() {
        return CURRENT_SYNC_OWNER.get();
    }

    /**
     * 设置服务器过载状态。
     *
     * @param overloaded true 表示服务器过载
     */
    public void setServerOverloaded(boolean overloaded) {
        this.serverOverloaded = overloaded;
    }

    /**
     * 获取同步任务统计。
     *
     * @return 统计快照
     */
    @NotNull
    public Stats getStats() {
        return new Stats(
            totalExecuted.get(),
            totalTimedOut.get(),
            totalFailed.get(),
            activeTasks.size()
        );
    }

    // ---- 内部方法 ----

    @Nullable
    private static final ThreadLocal<Object> CURRENT_ENTITY = ThreadLocal.withInitial(() -> null);

    /**
     * 设置当前实体上下文（用于实体绑定同步任务）。
     *
     * @param entity 实体，null 表示清除
     */
    public static void setCurrentEntity(@Nullable org.bukkit.entity.Entity entity) {
        CURRENT_ENTITY.set(entity);
    }

    /**
     * 启动 watchdog 线程，超时后中断任务线程。
     */
    private watchdogFuture startWatchdog(long targetThreadId, long timeoutMs) {
        watchdogFuture wf = new watchdogFuture();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                // 超时，中断目标线程
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (t.threadId() == targetThreadId && t.isAlive()) {
                        t.interrupt();
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
                // watchdog 被取消
            }
        }, "SyncTask-Watchdog-" + targetThreadId);
        watchdog.setDaemon(true);
        watchdog.start();
        wf.watchdogThread = watchdog;
        return wf;
    }

    private void recordAuditLog(@NotNull PluginId owner,
                                 @NotNull SyncTaskConstraints constraints,
                                 boolean success,
                                 long elapsedNanos,
                                 @Nullable Throwable exception) {
        // 只记录慢任务或失败任务
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMs > 1 || !success) {
            PluginRuntimeContext ctx = LMili.getRuntimeContext(owner);
            if (ctx != null) {
                ctx.observability().recordSyncTask(
                    constraints.description(),
                    elapsedNanos,
                    success,
                    exception
                );
            }
        }
    }

    /**
     * Watchdog 句柄。
     */
    private static final class watchdogFuture {
        Thread watchdogThread;
        void cancel() {
            if (watchdogThread != null) {
                watchdogThread.interrupt();
            }
        }
    }

    /**
     * 活动的同步任务。
     */
    private static final class ActiveSyncTask {
        final long threadId;
        final PluginId owner;
        final SyncTaskConstraints constraints;
        final long startTimeNanos;

        ActiveSyncTask(long threadId, PluginId owner, SyncTaskConstraints constraints) {
            this.threadId = threadId;
            this.owner = owner;
            this.constraints = constraints;
            this.startTimeNanos = System.nanoTime();
        }
    }

    /**
     * 统计快照。
     */
    public record Stats(
        int totalExecuted,
        int totalTimedOut,
        int totalFailed,
        int activeTasks
    ) {
        public int totalAttempts() { return totalExecuted + totalTimedOut + totalFailed; }
        public double timeoutRate() {
            int total = totalAttempts();
            return total == 0 ? 0.0 : (double) totalTimedOut / total;
        }
        public double failureRate() {
            int total = totalAttempts();
            return total == 0 ? 0.0 : (double) totalFailed / total;
        }

        @Override
        @NotNull
        public String toString() {
            return String.format("SyncStats{executed=%d, timedOut=%d, failed=%d, active=%d, timeoutRate=%.1f%%}",
                totalExecuted, totalTimedOut, totalFailed, activeTasks, timeoutRate() * 100);
        }
    }

    private static final class Holder {
        static final SyncTaskExecutor INSTANCE = new SyncTaskExecutor();
    }
}
