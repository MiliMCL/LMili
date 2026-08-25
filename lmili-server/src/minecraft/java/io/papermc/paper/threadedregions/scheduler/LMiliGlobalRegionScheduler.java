package io.papermc.paper.threadedregions.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.LMili;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LMili 全局 Region 调度器 —— 使用 LMili API 实现全局任务调度。
 *
 * <p><b>设计目标</b>：替代 Folia 的 GlobalRegionScheduler，使用 LMili 统一调度 API
 * 实现安全的全局任务调度。
 *
 * <p>全局 region 负责维护世界日时间、世界游戏时间、天气周期、
 * 跳过夜晚、执行控制台命令等不属于任何特定 region 的任务。
 *
 * @since 2.0.0
 */
public final class LMiliGlobalRegionScheduler implements GlobalRegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliGlobalRegionScheduler instance;

    /** 统计指标 */
    private final AtomicLong totalScheduledTasks = new AtomicLong();
    private final AtomicLong totalExecutedTasks = new AtomicLong();
    private final AtomicLong totalCancelledTasks = new AtomicLong();
    private final AtomicLong totalFailedTasks = new AtomicLong();

    private LMiliGlobalRegionScheduler() {}

    /**
     * 获取单例实例。
     *
     * @return 调度器实例
     */
    @NotNull
    public static LMiliGlobalRegionScheduler getInstance() {
        if (instance == null) {
            synchronized (LMiliGlobalRegionScheduler.class) {
                if (instance == null) {
                    instance = new LMiliGlobalRegionScheduler();
                }
            }
        }
        return instance;
    }

    @Override
    public void execute(@NotNull Plugin plugin, @NotNull Runnable run) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(run, "run");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);

            // 使用系统调度器执行全局任务
            UnifiedSchedulerAPI.forPlugin(pluginId).runAsync(() -> {
                try {
                    run.run();
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliGlobalRegionScheduler] Task execution failed for plugin {}",
                            plugin.getName(), e);
                }
            });
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliGlobalRegionScheduler] Failed to schedule task for plugin {}",
                    plugin.getName(), e);
        }
    }

    @Override
    @NotNull
    public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
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
                    LOGGER.error("[LMiliGlobalRegionScheduler] Task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliGlobalRegionScheduler] Failed to schedule task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");

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
                    LOGGER.error("[LMiliGlobalRegionScheduler] Delayed task failed for plugin {}",
                            plugin.getName(), e);
                }
            }, delayTicks * 50, java.util.concurrent.TimeUnit.MILLISECONDS);

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliGlobalRegionScheduler] Failed to schedule delayed task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");

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
                    LOGGER.error("[LMiliGlobalRegionScheduler] Fixed rate task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliGlobalRegionScheduler] Failed to schedule fixed rate task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    public void cancelTasks(@NotNull Plugin plugin) {
        // 简化实现：LMili API 不直接支持按插件取消
        LOGGER.debug("[LMiliGlobalRegionScheduler] cancelTasks called for plugin {}", plugin.getName());
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

    /**
     * Tick 方法 —— 在全局 tick 时调用。
     *
     * <p>LMili 的调度器是立即执行的，所以此方法为空操作。
     * 保留此方法是为了兼容 Folia 的调度器接口。
     */
    public void tick() {
        // LMili 调度器是立即执行的，无需 tick
        // 此方法仅用于兼容 RegionizedServer 的调用
    }

    // ---- 内部类 ----

    /**
     * LMili 计划任务实现。
     */
    private static final class LMiliScheduledTask implements ScheduledTask {
        private final Plugin plugin;
        private final ExecutionState state;
        private final LMiliGlobalRegionScheduler scheduler;

        LMiliScheduledTask(Plugin plugin, ExecutionState state, LMiliGlobalRegionScheduler scheduler) {
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
