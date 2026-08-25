package fun.bm.mili.api.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 世界任务上下文 —— 在世界调度任务中提供执行环境信息。
 *
 * <p>通过 {@link WorldScheduler#runAt(Location, java.util.function.Consumer)} 的回调参数传递，
 * 包含任务执行时的世界、位置和调度信息。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * scheduler.runAt(location, ctx -> {
 *     World world = ctx.world();
 *     Location loc = ctx.location();
 *     long elapsedNanos = ctx.elapsedNanos();
 *     // 执行任务逻辑
 * });
 * }</pre>
 *
 * @since 2.0.0
 */
public interface WorldTaskContext {

    /**
     * 获取任务执行所在的世界。
     *
     * @return 世界实例
     */
    @NotNull World world();

    /**
     * 获取任务绑定的位置。
     *
     * @return 位置实例
     */
    @NotNull Location location();

    /**
     * 获取当前 tick 已消耗的纳秒数。
     *
     * <p>用于任务自我监控，避免超出 tick 预算。
     *
     * @return 已消耗的纳秒数
     */
    long elapsedNanos();

    /**
     * 获取当前 tick 的剩余预算（纳秒）。
     *
     * @return 剩余预算纳秒数
     */
    long remainingBudgetNanos();

    /**
     * 检查是否还有足够的预算继续执行。
     *
     * @param requiredNanos 需要的纳秒数
     * @return true 如果剩余预算充足
     */
    boolean hasBudget(long requiredNanos);

    /**
     * 获取任务提交时的自定义数据。
     *
     * @param key 数据键
     * @return 数据值，如果不存在返回 null
     */
    @Nullable Object getUserData(@NotNull String key);

    /**
     * 设置任务执行过程中的自定义数据。
     *
     * @param key   数据键
     * @param value 数据值
     */
    void setUserData(@NotNull String key, @Nullable Object value);
}
