package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 实体任务 —— 针对特定实体执行的操作。
 *
 * <p>与 {@link RegionTask} 不同，EntityTask 带有实体上下文，
 * 可以安全地访问和修改实体状态（前提是在正确的线程上执行）。
 */
@FunctionalInterface
public interface EntityTask {

    /**
     * 执行实体任务。
     *
     * @param context 实体任务上下文（提供安全的实体访问）
     * @throws Exception 执行过程中的异常
     */
    void execute(@NotNull EntityTaskContext context) throws Exception;

    /**
     * 任务名称（用于日志和诊断）。
     */
    default @NotNull String name() {
        return getClass().getSimpleName() + "@" + Long.toHexString(System.identityHashCode(this));
    }

    /**
     * 任务取消时的回调。
     *
     * <p>默认空实现。覆盖此方法以在任务被取消时执行清理逻辑。
     */
    default void onCancel() {
        // 默认无操作
    }

    /**
     * 创建 EntityTask 的便捷方法。
     */
    static @NotNull EntityTask of(@NotNull final EntityTask task) {
        return task;
    }

    /**
     * 创建 EntityTask 的便捷方法（Runnable 版本）。
     */
    static @NotNull EntityTask ofRunnable(@NotNull final String name, @NotNull final Runnable runnable) {
        return new EntityTask() {
            @Override
            public void execute(@NotNull final EntityTaskContext context) {
                runnable.run();
            }

            @Override
            public @NotNull String name() {
                return name;
            }
        };
    }

    /**
     * 实体任务上下文 —— 提供对关联实体的安全访问。
     */
    interface EntityTaskContext {
        /**
         * 获取实体 ID。
         */
        int entityId();

        /**
         * 获取实体所在级别。
         */
        @Nullable Object level();

        /**
         * 获取关联的 region ID。
         */
        long regionId();
    }
}
