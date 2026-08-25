package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * LMili 统一调度 API —— 插件调度的唯一合法入口。
 *
 * <p><b>设计原则</b>：所有插件的任务调度必须通过 LMili 统一管理，
 * 禁止插件自行创建线程、ExecutorService、ScheduledExecutor 等调度资源。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 获取当前插件的调度器
 * PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
 *
 * // 提交异步任务
 * scheduler.runAsync(() -> {
 *     // 异步逻辑
 * });
 *
 * // 在指定位置执行任务
 * scheduler.runAt(location, ctx -> {
 *     // 位置相关逻辑
 * });
 *
 * // 实体绑定任务
 * scheduler.forEntity(entity).run(ctx -> {
 *     // 实体相关逻辑
 * });
 * }</pre>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>插件不得自行创建 Thread、ExecutorService、ScheduledExecutor</li>
 *   <li>所有调度任务必须通过本 API 提交</li>
 *   <li>违反安全策略将被记录并拒绝执行</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class UnifiedSchedulerAPI {

    private static volatile SchedulerSecurityManager securityManager;
    private static volatile PluginThreadMonitor threadMonitor;

    private UnifiedSchedulerAPI() {}

    /**
     * 初始化统一调度 API（由 LMili 运行时调用）。
     *
     * @param securityMgr 安全管理器
     * @param monitor     线程监控器
     */
    public static void initialize(@NotNull SchedulerSecurityManager securityMgr,
                                   @NotNull PluginThreadMonitor monitor) {
        securityManager = Objects.requireNonNull(securityMgr, "securityManager");
        threadMonitor = Objects.requireNonNull(monitor, "threadMonitor");
        monitor.startMonitoring();
    }

    /**
     * 获取当前调用线程关联的插件调度器。
     *
     * <p>通过栈追踪或线程本地变量识别调用方插件，
     * 返回绑定到该插件的 {@link PluginScheduler} 实例。
     *
     * @return 当前插件的调度器，如果无法识别则返回系统调度器
     */
    @NotNull
    public static PluginScheduler forCurrentPlugin() {
        PluginId owner = resolveCurrentPlugin();
        return forPlugin(owner);
    }

    /**
     * 获取指定插件的调度器。
     *
     * @param pluginId 插件 ID
     * @return 绑定到该插件的调度器
     * @throws SecurityException 如果插件未注册或无权调度
     */
    @NotNull
    public static PluginScheduler forPlugin(@NotNull PluginId pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        SchedulerSecurityManager mgr = securityManager;
        if (mgr != null) {
            mgr.checkPluginPermission(pluginId);
        }
        return new PluginSchedulerImpl(pluginId);
    }

    /**
     * 获取实体调度器（在当前插件上下文中）。
     *
     * @param entity 目标实体
     * @return 实体绑定调度器
     */
    @NotNull
    public static EntityScheduler forEntity(@NotNull org.bukkit.entity.Entity entity) {
        Objects.requireNonNull(entity, "entity");
        PluginId owner = resolveCurrentPlugin();
        return new PluginEntityScheduler(owner, entity);
    }

    /**
     * 获取系统级调度器（仅供 LMili 内部使用）。
     *
     * @return 系统调度器
     */
    @NotNull
    public static PluginScheduler forSystem() {
        return forPlugin(fun.bm.mili.lmili.api.LMili.SYSTEM_OWNER_ID);
    }

    /**
     * 检查当前线程是否有权创建线程。
     *
     * <p>插件不应自行创建线程，所有异步任务应通过调度 API 提交。
     * 此方法用于 {@link Thread} 构造函数的安全检查。
     *
     * @return true 如果当前线程被允许创建线程
     */
    public static boolean canCreateThread() {
        PluginThreadMonitor monitor = threadMonitor;
        if (monitor == null) return true; // 未初始化时允许
        return monitor.allowThreadCreation();
    }

    /**
     * 获取当前插件的运行时上下文。
     *
     * @return 运行时上下文，如果无法识别则返回 null
     */
    @Nullable
    public static PluginRuntimeContext currentPluginContext() {
        PluginId owner = resolveCurrentPlugin();
        if (owner.equals(fun.bm.mili.lmili.api.LMili.SYSTEM_OWNER_ID)) {
            return null;
        }
        return fun.bm.mili.lmili.api.LMili.getRuntimeContext(owner);
    }

    /**
     * 获取当前插件的调度指标。
     *
     * @return 调度指标快照
     */
    @NotNull
    public static SchedulerMetrics metrics() {
        PluginId owner = resolveCurrentPlugin();
        PluginRuntimeContext ctx = currentPluginContext();
        if (ctx == null) {
            return SchedulerMetrics.empty(owner);
        }
        return new SchedulerMetrics(
            owner,
            ctx.resourceQuota().tasksSubmitted(),
            ctx.resourceQuota().tasksRunning(),
            ctx.resourceQuota().tasksQueued(),
            ctx.resourceQuota().tasksCompleted(),
            ctx.resourceQuota().tasksFailed(),
            ctx.resourceQuota().averageExecutionNanos(),
            ctx.resourceQuota().rejectedCount()
        );
    }

    // ---- 内部方法 ----

    /**
     * 解析当前调用线程关联的插件 ID。
     */
    @NotNull
    private static PluginId resolveCurrentPlugin() {
        // 1. 检查线程本地绑定的 owner
        PluginId bound = fun.bm.mili.lmili.api.LMili.currentOwner();
        if (bound != null) return bound;

        // 2. 栈追踪识别
        return resolveCallingPlugin();
    }

    /**
     * 通过栈追踪识别调用方插件。
     */
    @NotNull
    private static PluginId resolveCallingPlugin() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 20); i++) {
                String className = stack[i].getClassName();
                // 跳过 LMili 和 Bukkit 框架类
                if (className.startsWith("fun.bm.mili.") ||
                    className.startsWith("org.bukkit.") ||
                    className.startsWith("java.") ||
                    className.startsWith("jdk.") ||
                    className.startsWith("sun.")) {
                    continue;
                }
                // 查找匹配的插件
                for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.isEnabled() &&
                        className.startsWith(plugin.getClass().getPackage().getName())) {
                        return fun.bm.mili.lmili.api.LMili.getPluginIdentityManager()
                            .findByBukkitName(plugin.getName())
                            .map(identity -> identity.id())
                            .orElse(fun.bm.mili.lmili.api.LMili.SYSTEM_OWNER_ID);
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return fun.bm.mili.lmili.api.LMili.SYSTEM_OWNER_ID;
    }

    // ---- 内部实现类 ----

    /**
     * 插件调度器实现。
     */
    private static final class PluginSchedulerImpl implements PluginScheduler {
        private final PluginId owner;

        PluginSchedulerImpl(@NotNull PluginId owner) {
            this.owner = owner;
        }

        @Override
        public void runAsync(@NotNull Runnable task) {
            Objects.requireNonNull(task, "task");
            Mili.scheduler().runAsync(task);
        }

        @Override
        public void runAt(@NotNull org.bukkit.Location location,
                          @NotNull java.util.function.Consumer<EntityTaskContext> task) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(task, "task");
            Mili.scheduler().runAt(location, task);
        }

        @Override
        public void runDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(unit, "unit");
            if (delay < 0) throw new IllegalArgumentException("delay must be >= 0");
            // 使用内部调度器的延迟执行
            long delayTicks = Math.max(1, unit.toMillis(delay) / 50);
            Mili.scheduler().runAsync(() -> {
                try {
                    Thread.sleep(unit.toMillis(delay));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            });
        }

        @Override
        @NotNull
        public <T> SyncTaskResult<T> runSync(@NotNull Supplier<T> task) {
            Objects.requireNonNull(task, "task");
            return SyncTaskExecutor.getInstance().execute(owner, task);
        }

        @Override
        @NotNull
        public <T> SyncTaskResult<T> runSync(@NotNull Supplier<T> task, @NotNull SyncTaskConstraints constraints) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(constraints, "constraints");
            return SyncTaskExecutor.getInstance().execute(owner, task, constraints);
        }

        @Override
        @NotNull
        public <T> SyncTaskResult<T> runAtSync(@NotNull org.bukkit.Location location, @NotNull Supplier<T> task) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(task, "task");
            // 在指定位置的 region 上下文中执行同步任务
            // 委托给位置绑定调度器的同步执行能力
            return SyncTaskExecutor.getInstance().execute(owner, () -> {
                // 确保区块已加载，在 region tick 上下文中安全访问
                if (!location.isWorldLoaded() || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    throw new IllegalStateException("Target chunk is not loaded");
                }
                return task.get();
            });
        }

        @Override
        @NotNull
        public SyncEntityScheduler forEntitySync(@NotNull org.bukkit.entity.Entity entity) {
            Objects.requireNonNull(entity, "entity");
            return new PluginSyncEntityScheduler(owner, entity);
        }

        @Override
        @NotNull
        public EntityScheduler forEntity(@NotNull org.bukkit.entity.Entity entity) {
            Objects.requireNonNull(entity, "entity");
            return new PluginEntityScheduler(owner, entity);
        }

        @Override
        @NotNull
        public PluginId owner() {
            return owner;
        }

        @Override
        @NotNull
        public SchedulerMetrics metrics() {
            return UnifiedSchedulerAPI.metrics();
        }
    }

    /**
     * 插件实体调度器实现。
     */
    private static final class PluginEntityScheduler implements EntityScheduler {
        private final PluginId owner;
        private final org.bukkit.entity.Entity entity;

        PluginEntityScheduler(@NotNull PluginId owner, @NotNull org.bukkit.entity.Entity entity) {
            this.owner = owner;
            this.entity = entity;
        }

        @Override
        public void run(@NotNull java.util.function.Consumer<EntityTaskContext> task) {
            Objects.requireNonNull(task, "task");
            Mili.scheduler().forEntity(entity).run(task);
        }

        @Override
        public void runDelayed(@NotNull java.util.function.Consumer<EntityTaskContext> task, long delayTicks) {
            Objects.requireNonNull(task, "task");
            if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
            Mili.scheduler().forEntity(entity).runDelayed(task, delayTicks);
        }
    }

    /**
     * 插件同步实体调度器实现。
     */
    private static final class PluginSyncEntityScheduler implements SyncEntityScheduler {
        private final PluginId owner;
        private final org.bukkit.entity.Entity entity;
        private final java.util.concurrent.atomic.AtomicInteger activeTaskCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

        PluginSyncEntityScheduler(@NotNull PluginId owner, @NotNull org.bukkit.entity.Entity entity) {
            this.owner = owner;
            this.entity = entity;
        }

        @Override
        @NotNull
        public <T> SyncTaskResult<T> run(@NotNull Supplier<T> task) {
            Objects.requireNonNull(task, "task");
            return run(task, SyncTaskConstraints.DEFAULT);
        }

        @Override
        @NotNull
        public <T> SyncTaskResult<T> run(@NotNull Supplier<T> task, @NotNull SyncTaskConstraints constraints) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(constraints, "constraints");

            // 检查实体是否有效
            if (!entity.isValid()) {
                return SyncTaskResult.failure("Entity is no longer valid: " + entity);
            }

            // 增加活动任务计数
            activeTaskCount.incrementAndGet();
            SyncTaskExecutor.setCurrentEntity(entity);

            try {
                return SyncTaskExecutor.getInstance().execute(owner, () -> {
                    // 双重检查实体状态
                    if (!entity.isValid()) {
                        throw new IllegalStateException("Entity became invalid during sync task execution");
                    }
                    return task.get();
                }, constraints);
            } finally {
                activeTaskCount.decrementAndGet();
                SyncTaskExecutor.setCurrentEntity(null);
            }
        }

        @Override
        public boolean canExecute() {
            return entity.isValid() && activeTaskCount.get() == 0;
        }

        @Override
        @NotNull
        public org.bukkit.entity.Entity entity() {
            return entity;
        }

        @Override
        public int activeTaskCount() {
            return activeTaskCount.get();
        }
    }

    /**
     * 调度指标快照。
     */
    public record SchedulerMetrics(
        @NotNull PluginId owner,
        long tasksSubmitted,
        long tasksRunning,
        long tasksQueued,
        long tasksCompleted,
        long tasksFailed,
        long averageExecutionNanos,
        long rejectedCount
    ) {
        @NotNull
        public static SchedulerMetrics empty(@NotNull PluginId owner) {
            return new SchedulerMetrics(owner, 0, 0, 0, 0, 0, 0, 0);
        }

        public double averageExecutionMs() {
            return averageExecutionNanos / 1_000_000.0;
        }

        public double successRate() {
            return tasksSubmitted == 0 ? 1.0 :
                (double) tasksCompleted / tasksSubmitted;
        }
    }
}
