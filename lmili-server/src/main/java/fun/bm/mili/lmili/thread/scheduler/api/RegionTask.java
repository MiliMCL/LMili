package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * 区域任务抽象 —— 表示一个需要在特定 region 上下文中执行的工作单元。
 *
 * <p>这是调度器的基本调度单位。每个 RegionTask 关联一个 regionId，
 * 调度器根据 regionId 将任务路由到对应的 RegionQueue。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li>轻量级：对象池友好，避免大字段</li>
 *   <li>可组合：支持任务链和依赖关系</li>
 *   <li>可取消：支持超时和手动取消</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RegionTask task = RegionTask.builder(regionId)
 *     .task(() -> { /* 执行逻辑 *\/ })
 *     .timeout(50, TimeUnit.MILLISECONDS)
 *     .build();
 *
 * TaskHandle handle = scheduler.submit(task);
 * }</pre>
 */
@FunctionalInterface
public interface RegionTask {

    /**
     * 执行此任务。
     *
     * <p>实现要求：
     * <ul>
     *   <li>不应长时间阻塞（>1ms），否则应使用 {@link #isBlocking()} 标记</li>
     *   <li>应处理 InterruptedException 并恢复中断状态</li>
     *   <li>异常会被调度器捕获并记录</li>
     * </ul>
     *
     * @throws Exception 执行过程中的异常
     */
    void execute() throws Exception;

    /**
     * 获取此任务关联的 region ID。
     *
     * <p>调度器使用此 ID 将任务路由到正确的 RegionQueue。
     * 返回 -1 表示任务不与特定 region 关联（全局任务）。
     */
    default long regionId() {
        return -1;
    }

    /**
     * 是否为阻塞型任务。
     *
     * <p>阻塞型任务会被路由到专用阻塞线程池，避免影响 virtual thread carrier。
     * 默认返回 false。
     */
    default boolean isBlocking() {
        return false;
    }

    /**
     * 获取任务超时时间。
     *
     * <p>返回 0 表示无超时限制。
     */
    default long timeoutMillis() {
        return 0;
    }

    /**
     * 获取任务名称（用于日志和诊断）。
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
     * 创建任务构建器。
     *
     * @param regionId 关联的 region ID
     */
    static @NotNull Builder builder(final long regionId) {
        return new Builder(regionId);
    }

    /**
     * RegionTask 构建器。
     */
    final class Builder {
        private final long regionId;
        private Runnable task;
        private boolean blocking;
        private long timeoutMillis = 0;
        private String name;

        Builder(final long regionId) {
            this.regionId = regionId;
        }

        public Builder task(@NotNull final Runnable task) {
            this.task = task;
            return this;
        }

        public Builder task(@NotNull final RegionTask task) {
            this.task = () -> {
                try {
                    task.execute();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            return this;
        }

        public Builder blocking(final boolean blocking) {
            this.blocking = blocking;
            return this;
        }

        public Builder timeout(final long timeout, @NotNull final TimeUnit unit) {
            this.timeoutMillis = unit.toMillis(timeout);
            return this;
        }

        public Builder name(@NotNull final String name) {
            this.name = name;
            return this;
        }

        public RegionTask build() {
            final Runnable effectiveTask = this.task;
            final long effectiveRegionId = this.regionId;
            final boolean effectiveBlocking = this.blocking;
            final long effectiveTimeout = this.timeoutMillis;
            final String effectiveName = this.name != null ? this.name : "RegionTask-" + effectiveRegionId;

            return new RegionTask() {
                @Override
                public void execute() throws Exception {
                    effectiveTask.run();
                }

                @Override
                public long regionId() {
                    return effectiveRegionId;
                }

                @Override
                public boolean isBlocking() {
                    return effectiveBlocking;
                }

                @Override
                public long timeoutMillis() {
                    return effectiveTimeout;
                }

                @Override
                public @NotNull String name() {
                    return effectiveName;
                }
            };
        }
    }
}
