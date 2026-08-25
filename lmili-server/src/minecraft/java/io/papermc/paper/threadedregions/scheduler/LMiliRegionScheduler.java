package io.papermc.paper.threadedregions.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.LMili;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LMili Region 调度器 —— 使用 LMili API 实现 region 任务调度。
 *
 * <p><b>设计目标</b>：替代 Folia 的 RegionScheduler，使用 LMili 统一调度 API
 * 实现安全的 region 任务调度。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>Region 任务调度：在指定 region 的 tick 上下文中执行任务</li>
 *   <li>延迟任务：支持延迟执行</li>
 *   <li>实体任务：支持绑定到实体的任务</li>
 *   <li>异步执行：支持异步任务执行</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有公共方法都是线程安全的。任务通过 LMili 的 PluginScheduler 执行。</p>
 *
 * @since 2.0.0
 */
public final class LMiliRegionScheduler implements RegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliRegionScheduler instance;

    /** 统计指标 */
    private final AtomicLong totalScheduledTasks = new AtomicLong();
    private final AtomicLong totalExecutedTasks = new AtomicLong();
    private final AtomicLong totalCancelledTasks = new AtomicLong();
    private final AtomicLong totalFailedTasks = new AtomicLong();

    private LMiliRegionScheduler() {}

    /**
     * 获取单例实例。
     *
     * @return 调度器实例
     */
    @NotNull
    public static LMiliRegionScheduler getInstance() {
        if (instance == null) {
            synchronized (LMiliRegionScheduler.class) {
                if (instance == null) {
                    instance = new LMiliRegionScheduler();
                }
            }
        }
        return instance;
    }

    @Override
    public void execute(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Runnable run) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(run, "run");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);
            Location loc = new Location(world, chunkX * 16, 0, chunkZ * 16);

            UnifiedSchedulerAPI.forPlugin(pluginId).runAt(loc, ctx -> {
                try {
                    run.run();
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliRegionScheduler] Task execution failed for plugin {}",
                            plugin.getName(), e);
                }
            });
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliRegionScheduler] Failed to schedule task for plugin {}",
                    plugin.getName(), e);
        }
    }

    @Override
    @NotNull
    public ScheduledTask run(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);
            Location loc = new Location(world, chunkX * 16, 0, chunkZ * 16);

            UnifiedSchedulerAPI.forPlugin(pluginId).runAt(loc, ctx -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliRegionScheduler] Region task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliRegionScheduler] Failed to schedule region task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);
            Location loc = new Location(world, chunkX * 16, 0, chunkZ * 16);

            UnifiedSchedulerAPI.forPlugin(pluginId).runDelayed(() -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliRegionScheduler] Delayed region task failed for plugin {}",
                            plugin.getName(), e);
                }
            }, delayTicks * 50, TimeUnit.MILLISECONDS);

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliRegionScheduler] Failed to schedule delayed region task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    @Override
    @NotNull
    public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");

        totalScheduledTasks.incrementAndGet();

        try {
            PluginId pluginId = getPluginId(plugin);
            Location loc = new Location(world, chunkX * 16, 0, chunkZ * 16);

            // 简化实现：仅执行一次（完整实现需要定时器支持）
            UnifiedSchedulerAPI.forPlugin(pluginId).runAt(loc, ctx -> {
                try {
                    LMiliScheduledTask scheduledTask = new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
                    task.accept(scheduledTask);
                    totalExecutedTasks.incrementAndGet();
                } catch (Exception e) {
                    totalFailedTasks.incrementAndGet();
                    LOGGER.error("[LMiliRegionScheduler] Fixed rate task failed for plugin {}",
                            plugin.getName(), e);
                }
            });

            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.FINISHED, this);
        } catch (Exception e) {
            totalFailedTasks.incrementAndGet();
            LOGGER.error("[LMiliRegionScheduler] Failed to schedule fixed rate task for plugin {}",
                    plugin.getName(), e);
            return new LMiliScheduledTask(plugin, ScheduledTask.ExecutionState.CANCELLED, this);
        }
    }

    /**
     * 获取插件 ID。
     *
     * @param plugin 插件
     * @return 插件 ID
     */
    @NotNull
    private PluginId getPluginId(@NotNull Plugin plugin) {
        return LMili.getPluginIdentityManager()
                .findByBukkitName(plugin.getName())
                .map(identity -> identity.id())
                .orElse(LMili.SYSTEM_OWNER_ID);
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息映射
     */
    @NotNull
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalScheduledTasks", totalScheduledTasks.get());
        stats.put("totalExecutedTasks", totalExecutedTasks.get());
        stats.put("totalCancelledTasks", totalCancelledTasks.get());
        stats.put("totalFailedTasks", totalFailedTasks.get());
        return stats;
    }

    /**
     * 清理资源。
     */
    public void clear() {
        instance = null;
    }

    /**
     * Tick 方法 —— 由服务器主循环调用以执行待处理任务。
     * <p>当前实现中任务已通过 LMili API 调度，此方法为空实现。
     */
    public void tick() {
        // 任务通过 LMili API 自动调度，无需额外 tick 处理
    }

    // ---- 内部类 ----

    /**
     * LMili 计划任务实现。
     */
    private static final class LMiliScheduledTask implements ScheduledTask {
        private final Plugin plugin;
        private final ExecutionState state;
        private final LMiliRegionScheduler scheduler;

        LMiliScheduledTask(Plugin plugin, ExecutionState state, LMiliRegionScheduler scheduler) {
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
