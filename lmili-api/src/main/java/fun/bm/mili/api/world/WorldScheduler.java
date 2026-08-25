package fun.bm.mili.api.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 世界绑定调度器 —— 在特定世界上下文中执行任务。
 *
 * <p>通过 {@link MiliWorlds#forWorld(World)} 获取，所有提交的任务
 * 会在目标世界的 region 上下文中执行，确保线程安全和数据一致性。
 *
 * <p>与 {@link fun.bm.mili.api.PluginScheduler} 的区别：
 * <ul>
 *   <li>PluginScheduler 是插件级调度，任意位置执行</li>
 *   <li>WorldScheduler 是世界级调度，任务在目标世界 region 中执行</li>
 * </ul>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li>多世界插件需要按世界隔离任务</li>
 *   <li>跨世界实体/区块操作需要正确的世界上下文</li>
 *   <li>世界独立的 tick 配置和优先级</li>
 * </ul>
 *
 * @since 2.0.0
 */
public interface WorldScheduler {

    /**
     * 获取此调度器绑定的世界。
     *
     * @return 绑定的世界
     */
    @NotNull World world();

    /**
     * 提交一个在世界上下文中异步执行的任务。
     *
     * <p>任务会在目标世界的线程池中执行，不与任何特定位置关联。
     * 如果多世界功能未启用，任务会被转发到全局调度器。
     *
     * @param task 要执行的任务，不能为 null
     */
    void runAsync(@NotNull Runnable task);

    /**
     * 在世界中指定位置执行任务。
     *
     * <p>任务会被路由到该位置所在的 region，在该世界 tick 上下文中执行。
     *
     * @param location 目标位置（必须已加载）
     * @param task     要执行的任务，接收 {@link WorldTaskContext}
     */
    void runAt(@NotNull Location location, @NotNull Consumer<WorldTaskContext> task);

    /**
     * 提交一个延迟执行的任务。
     *
     * @param task  要执行的任务
     * @param delay 延迟时间
     * @param unit  时间单位
     */
    void runDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit);

    /**
     * 在世界上下文中同步执行任务并返回结果。
     *
     * <p>任务会在当前线程立即执行，但有安全约束：
     * <ul>
     *   <li>最大执行时间受世界 tick 预算限制</li>
     *   <li>禁止在同步任务中提交新的同步任务</li>
     * </ul>
     *
     * @param <T>  返回值类型
     * @param task 要执行的任务
     * @return 同步任务结果
     */
    @NotNull
    <T> WorldSyncTaskResult<T> runSync(@NotNull Supplier<T> task);

    /**
     * 在世界中指定位置同步执行任务。
     *
     * @param <T>      返回值类型
     * @param location 目标位置
     * @param task     要执行的任务
     * @return 同步任务结果，包含世界上下文信息
     */
    @NotNull
    <T> WorldSyncTaskResult<T> runAtSync(@NotNull Location location, @NotNull Supplier<T> task);

    /**
     * 获取绑定到世界中指定实体的调度器。
     *
     * <p>返回的调度器确保所有任务在该实体所在的区域中顺序执行，
     * 且该区域必须属于此调度器绑定的世界。
     *
     * @param entity 目标实体
     * @return 实体绑定调度器
     */
    @NotNull fun.bm.mili.api.EntityScheduler forEntity(@NotNull Entity entity);

    /**
     * 获取此世界调度器的指标快照。
     *
     * @return 调度指标
     */
    @NotNull WorldSchedulerMetrics metrics();
}
