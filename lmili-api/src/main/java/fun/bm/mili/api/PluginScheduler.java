package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 插件调度器 —— 绑定到单个插件的任务调度接口。
 *
 * <p>每个插件通过 {@link UnifiedSchedulerAPI#forPlugin(PluginId)} 获取
 * 属于自己的调度器实例。该调度器：
 * <ul>
 *   <li>自动追踪任务提交数、执行数、失败数</li>
 *   <li>强制执行资源配额（.concurrent tasks / queue depth）</li>
 *   <li>在插件被禁用时自动取消所有 pending 任务</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本接口的所有实现必须是线程安全的。多个线程可以同时提交任务。
 *
 * @since 2.0.0
 */
public interface PluginScheduler {

    /**
     * 提交一个异步任务执行。
     *
     * <p>任务会在 LMili 管理的线程池中执行，不与任何特定位置或实体关联。
     * 适用于 IO 密集或阻塞操作（数据库查询、HTTP 请求等）。
     *
     * @param task 要执行的任务，不能为 null
     * @throws SecurityException 如果插件无权提交任务（已禁用/未注册）
     * @throws IllegalStateException 如果调度器已关闭
     */
    void runAsync(@NotNull Runnable task);

    /**
     * 在指定位置执行任务。
     *
     * <p>任务会被路由到该位置所在的 region，在 region tick 上下文中执行。
     * 适用于需要访问该位置区块数据的场景。
     *
     * @param location 目标位置（必须已加载）
     * @param task     要执行的任务
     * @throws SecurityException 如果插件无权提交任务
     */
    void runAt(@NotNull Location location, @NotNull Consumer<EntityTaskContext> task);

    /**
     * 提交一个延迟执行的任务。
     *
     * <p>任务会在指定的延迟后被调度执行。延迟精度取决于调度器负载。
     *
     * @param task  要执行的任务
     * @param delay 延迟时间
     * @param unit  时间单位
     * @throws SecurityException 如果插件无权提交任务
     */
    void runDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit);

    /**
     * 同步执行任务并返回结果。
     *
     * <p><b>安全约束</b>：
     * <ul>
     *   <li>任务会在当前线程立即执行</li>
     *   <li>最大执行时间受 {@link SyncTaskConstraints#defaultTimeoutMs()} 限制</li>
     *   <li>超时或死锁风险时会返回失败结果而非阻塞</li>
     *   <li>禁止在同步任务中提交新的同步任务（防止死锁）</li>
     * </ul>
     *
     * <p><b>适用场景</b>：快速查询、缓存读取、不可变数据访问。
     * <b>不适用</b>：IO 操作、长时间计算、跨 region 数据访问。
     *
     * @param <T>  返回值类型
     * @param task 要执行的任务
     * @return 同步任务结果
     * @throws SecurityException 如果插件无权提交任务
     */
    @NotNull
    <T> SyncTaskResult<T> runSync(@NotNull Supplier<T> task);

    /**
     * 同步执行任务并返回结果（带自定义约束）。
     *
     * @param <T>        返回值类型
     * @param task       要执行的任务
     * @param constraints 同步任务约束
     * @return 同步任务结果
     * @throws SecurityException 如果插件无权提交任务
     */
    @NotNull
    <T> SyncTaskResult<T> runSync(@NotNull Supplier<T> task, @NotNull SyncTaskConstraints constraints);

    /**
     * 在指定位置同步执行任务。
     *
     * <p>任务会在该位置所在的 region 上下文中同步执行。
     *
     * @param <T>      返回值类型
     * @param location 目标位置
     * @param task     要执行的任务
     * @return 同步任务结果
     */
    @NotNull
    <T> SyncTaskResult<T> runAtSync(@NotNull Location location, @NotNull Supplier<T> task);

    /**
     * 获取绑定到指定实体的同步调度器。
     *
     * @param entity 目标实体
     * @return 实体绑定同步调度器
     */
    @NotNull
    SyncEntityScheduler forEntitySync(@NotNull Entity entity);

    /**
     * 获取绑定到指定实体的调度器。
     *
     * <p>返回的调度器确保所有任务在该实体所在的 region 中顺序执行。
     *
     * @param entity 目标实体
     * @return 实体绑定调度器
     */
    @NotNull EntityScheduler forEntity(@NotNull Entity entity);

    /**
     * 获取此调度器绑定的插件 ID。
     *
     * @return 插件 ID
     */
    @NotNull PluginId owner();

    /**
     * 获取此插件的调度指标快照。
     *
     * @return 当前调度指标
     */
    @NotNull UnifiedSchedulerAPI.SchedulerMetrics metrics();
}
