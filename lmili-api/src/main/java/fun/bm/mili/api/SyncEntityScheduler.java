package fun.bm.mili.api;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 同步实体调度器 —— 在实体上下文中同步执行任务。
 *
 * <p>与普通 {@link EntityScheduler} 不同，此调度器的任务会立即在当前线程执行，
 * 但会绑定到实体所在的 region 上下文，确保：
 * <ul>
 *   <li>任务在实体所在的 region tick 上下文中执行</li>
 *   <li>同一实体的同步任务串行执行（避免并发修改）</li>
 *   <li>如果实体已移除，任务会失败而非执行</li>
 * </ul>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>任务执行时间受 {@link SyncTaskConstraints} 限制</li>
 *   <li>禁止在同步任务中等待异步任务完成（防止死锁）</li>
 *   <li>任务不应执行 IO 操作</li>
 * </ul>
 *
 * @since 2.0.0
 */
public interface SyncEntityScheduler {

    /**
     * 同步执行任务并返回结果。
     *
     * <p>任务会在当前线程立即执行，但会检查：
     * <ol>
     *   <li>实体是否仍然有效</li>
     *   <li>是否有其他同步任务正在执行（排队等待）</li>
     *   <li>是否在超时时间内完成</li>
     * </ol>
     *
     * @param <T>  返回值类型
     * @param task 要执行的任务
     * @return 同步任务结果
     */
    @NotNull
    <T> SyncTaskResult<T> run(@NotNull Supplier<T> task);

    /**
     * 同步执行任务（带自定义约束）。
     *
     * @param <T>        返回值类型
     * @param task       要执行的任务
     * @param constraints 约束
     * @return 同步任务结果
     */
    @NotNull
    <T> SyncTaskResult<T> run(@NotNull Supplier<T> task, @NotNull SyncTaskConstraints constraints);

    /**
     * 检查当前是否可以在该实体上执行同步任务。
     *
     * <p>如果实体已移除或正在执行其他同步任务，返回 false。
     *
     * @return true 如果可以执行
     */
    boolean canExecute();

    /**
     * 获取绑定的实体。
     *
     * @return 实体
     */
    @NotNull
    Entity entity();

    /**
     * 获取绑定到此实体的同步任务数量。
     *
     * @return 正在执行的同步任务数
     */
    int activeTaskCount();
}
