package fun.bm.mili.api.world;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Mili 多世界 API 入口点 —— 多世界任务调度的统一访问接口。
 *
 * <p>提供按世界隔离的调度能力，每个世界可以拥有独立的调度器和 tick 配置。
 * 适用于多世界服务器（如多世界插件、BungeeCord 子服务器等）。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 检查多世界 API 是否可用
 * if (MiliWorlds.isSupported()) {
 *     // 获取指定世界的调度器
 *     WorldScheduler scheduler = MiliWorlds.forWorld(world);
 *
 *     // 在世界上下文中执行异步任务
 *     scheduler.runAsync(() -> {
 *         // 任务逻辑
 *     });
 *
 *     // 获取世界 tick 配置
 *     WorldTickConfig config = MiliWorlds.getWorldConfig(world);
 *     double tps = config.tpsTarget();
 * }
 * }</pre>
 *
 * <h3>兼容性</h3>
 * <p>如果服务端未实现多世界功能，所有 API 调用将安全降级为 no-op 或返回默认值，
 * 不会抛出异常。使用 {@link #isSupported()} 检查可用性。
 *
 * <h3>线程安全</h3>
 * <p>本类的所有方法都是线程安全的。
 *
 * @since 2.0.0
 */
public final class MiliWorlds {

    private static volatile WorldSchedulerRegistry registry;

    private MiliWorlds() {}

    /**
     * 获取指定世界的调度器。
     *
     * <p>如果多世界功能未启用或世界未注册，返回 no-op 调度器。
     *
     * @param world 目标世界，不能为 null
     * @return 世界绑定调度器，never null
     */
    @NotNull
    public static WorldScheduler forWorld(@NotNull World world) {
        WorldSchedulerRegistry r = registry;
        if (r != null) {
            return r.forWorld(world);
        }
        return NoopWorldScheduler.INSTANCE;
    }

    /**
     * 获取指定世界的 tick 配置。
     *
     * <p>如果多世界功能未启用或世界未注册，返回默认配置。
     *
     * @param world 目标世界，不能为 null
     * @return 世界 tick 配置，never null
     */
    @NotNull
    public static WorldTickConfig getWorldConfig(@NotNull World world) {
        WorldSchedulerRegistry r = registry;
        if (r != null) {
            return r.getConfig(world);
        }
        return WorldTickConfig.DEFAULT;
    }

    /**
     * 更新指定世界的 tick 配置。
     *
     * <p>如果多世界功能未启用，此方法不执行任何操作。
     *
     * @param world  目标世界，不能为 null
     * @param config 新的 tick 配置，不能为 null
     */
    public static void setWorldConfig(@NotNull World world, @NotNull WorldTickConfig config) {
        WorldSchedulerRegistry r = registry;
        if (r != null) {
            r.setConfig(world, config);
        }
    }

    /**
     * 获取所有已注册的世界列表。
     *
     * <p>如果多世界功能未启用，返回空列表。
     *
     * @return 已注册世界的不可变列表
     */
    @NotNull
    public static List<World> getRegisteredWorlds() {
        WorldSchedulerRegistry r = registry;
        if (r != null) {
            return r.getRegisteredWorlds();
        }
        return List.of();
    }

    /**
     * 检查多世界 API 是否可用。
     *
     * <p>当 LMili 运行时已初始化多世界注册表时返回 true。
     *
     * @return true 如果多世界功能已启用
     */
    public static boolean isSupported() {
        return registry != null;
    }

    /**
     * 获取多世界 API 版本。
     *
     * @return 版本字符串，"unknown" 如果未初始化
     */
    @NotNull
    public static String version() {
        WorldSchedulerRegistry r = registry;
        if (r != null) {
            return r.version();
        }
        return "unknown";
    }

    /**
     * 内部方法：注册多世界调度器注册表。
     * 由 LMili 运行时在初始化时调用。
     *
     * @param impl 注册表实现
     */
    public static void registerRegistry(@NotNull WorldSchedulerRegistry impl) {
        registry = impl;
    }

    /**
     * 内部方法：重置注册表。
     * 由 LMili 运行时在关闭时调用。
     */
    public static void resetRegistry() {
        registry = null;
    }
}
