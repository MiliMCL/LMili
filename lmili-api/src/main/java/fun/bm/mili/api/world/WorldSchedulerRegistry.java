package fun.bm.mili.api.world;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 多世界调度器注册表 —— 管理所有世界的调度器和配置。
 *
 * <p>此接口由 LMili 运行时实现，插件代码通常不需要直接使用。
 * 通过 {@link MiliWorlds} 入口点访问多世界功能。
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li>所有方法必须是线程安全的</li>
 *   <li>未注册的世界应返回默认配置</li>
 *   <li>注册表生命周期与 LMili 运行时一致</li>
 * </ul>
 *
 * @since 2.0.0
 * @see MiliWorlds
 */
public interface WorldSchedulerRegistry {

    /**
     * 获取指定世界的调度器。
     *
     * <p>如果世界未注册，返回 no-op 调度器。
     *
     * @param world 目标世界，不能为 null
     * @return 世界绑定调度器，never null
     */
    @NotNull WorldScheduler forWorld(@NotNull World world);

    /**
     * 获取指定世界的 tick 配置。
     *
     * <p>如果世界未注册，返回默认配置。
     *
     * @param world 目标世界，不能为 null
     * @return 世界 tick 配置，never null
     */
    @NotNull WorldTickConfig getConfig(@NotNull World world);

    /**
     * 更新指定世界的 tick 配置。
     *
     * <p>如果世界未注册，此方法不执行任何操作。
     *
     * @param world  目标世界，不能为 null
     * @param config 新的 tick 配置，不能为 null
     */
    void setConfig(@NotNull World world, @NotNull WorldTickConfig config);

    /**
     * 注册一个世界到多世界调度系统。
     *
     * <p>注册后，该世界将拥有独立的调度器和 tick 配置。
     * 重复注册幂等。
     *
     * @param world 要注册的世界，不能为 null
     */
    void registerWorld(@NotNull World world);

    /**
     * 从多世界调度系统注销一个世界。
     *
     * <p>注销后，该世界的调度器将返回 no-op，配置恢复默认。
     *
     * @param world 要注销的世界，不能为 null
     */
    void unregisterWorld(@NotNull World world);

    /**
     * 检查世界是否已注册。
     *
     * @param world 目标世界，不能为 null
     * @return true 如果世界已注册
     */
    boolean isRegistered(@NotNull World world);

    /**
     * 获取所有已注册的世界列表。
     *
     * @return 已注册世界的不可变列表
     */
    @NotNull List<World> getRegisteredWorlds();

    /**
     * 获取注册表版本。
     *
     * @return 版本字符串
     */
    @NotNull String version();
}
