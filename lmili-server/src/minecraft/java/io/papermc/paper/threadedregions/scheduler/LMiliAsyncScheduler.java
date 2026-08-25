package io.papermc.paper.threadedregions.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.LMili;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LMili 异步调度器 —— 使用 LMili API 实现异步任务调度。
 *
 * <p><b>设计目标</b>：替代 Folia 的 AsyncScheduler，使用 LMili 统一调度 API
 * 实现安全的异步任务调度。
 *
 * @since 2.0.0
 */
public final class LMiliAsyncScheduler implements AsyncScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliAsyncScheduler instance;

    /** 统计指标 */
    private final AtomicLong totalScheduledTasks = new AtomicLong();
    private final AtomicLong totalExecutedTasks = new AtomicLong();
    private final AtomicLong totalCancelledTasks = new AtomicLong();
    private final AtomicLong totalFailedTasks = new AtomicLong();

    private LMiliAsyncScheduler() {}

    /**
     * 获取单例实例。
     *
     * @return 调度器实例
     */
    @NotNull
    public static LMiliAsyncScheduler getInstance() {
        if (instance == null) {
            synchronized (LMiliAsyncScheduler.class) {
                if (instance == null) {
                    instance = new LMiliAsyncScheduler();
                }
            }
        }
        return instance;
    }

    @Override
    @NotNull
    public ScheduledTask runNow(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);

            UnifiedSchedulerAPI.forPlugin(pluginId).runAsync(() -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliAsyncScheduler] Task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliAsyncScheduler] Failed to schedule task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delay, @NotNull TimeUnit unit) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);

            UnifiedSchedulerAPI.forPlugin(pluginId).runDelayed(() -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliAsyncScheduler] Delayed task failed for plugin {}",
                            plugin.getName(), e);
                }
            }, delay, unit);

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliAsyncScheduler] Failed to schedule delayed task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelay, long period, @NotNull TimeUnit unit) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);

            // 简化实现：仅执行一次
            UnifiedSchedulerAPI.forPlugin(pluginId).runAsync(() -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliAsyncScheduler] Fixed rate task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliAsyncScheduler] Failed to schedule fixed rate task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    public void cancelTasks(@NotNull Plugin plugin) {
        // 简化实现：LMili API 不直接支持按插件取消
        LOGGER.debug("[LMiliAsyncScheduler] cancelTasks called for plugin {}", plugin.getName());
    }

    /**
     * 获取插件 ID。
     */
    @NotNull
    private PluginId getPluginId(@NotNull Plugin plugin) {
        return LMili.getPluginIdentityManager()
                .findByBukkitName(plugin.getName())
                .map(identity -> identity.id())
                .orElse(LMili.SYSTEM_OWNER_ID);
    }

    /**
     * 清理资源。
     */
    public void clear() {
        instance = null;
    }

    // ---- 内部类 ----

    /**
     * LMili 计划任务实现。
     */
    private static final class LMiliScheduledTask implements ScheduledTask {
        private final Plugin plugin;
        private final ExecutionState state;
        private final LMiliAsyncScheduler scheduler;

        LMiliScheduledTask(Plugin plugin, ExecutionState state, LMiliAsyncScheduler scheduler) {
            this.plugin = plugin;
            this.state = state;
            this.scheduler = scheduler;
        }

        @Override
        @NotNull
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        @NotNull
        public CancelledState cancel() {
            scheduler.totalCancelledTasks.incrementAndGet();
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        @NotNull
        public ExecutionState getExecutionState() {
            return state;
        }
    }
}
