package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;

/**
 * 结构化并发（Structured Concurrency）—— 把多个子任务组合成"一个工作单元"。
 *
 * <p>用 {@code try-with-resources} 创建 scope，{@link #fork(Callable)} 启动子任务，
 * {@link #join()} 等待所有完成或第一个失败。
 *
 * <p>底层基于 Java 21+ {@code StructuredTaskScope}；fallback 用 CountDownLatch。
 */
public interface StructuredConcurrency extends AutoCloseable {

    /**
     * 启动子任务。返回子任务 handle。
     */
    @NotNull <T> SubTask<T> fork(@NotNull Callable<T> task);

    /**
     * 等待所有 fork 完成或第一个失败。
     *
     * @throws Exception 第一个失败的子任务抛出的异常
     */
    void join() throws Exception;

    /** 关闭（cancel 未完成的 fork） */
    @Override
    void close();

    /** 子任务句柄 */
    interface SubTask<T> {
        /** 阻塞拿结果 */
        T get() throws Exception;
        /** 限时拿结果 */
        T get(long timeout, java.util.concurrent.TimeUnit unit) throws Exception;
        /** 状态 */
        State state();

        enum State { UNAVAILABLE, RUNNING, SUCCESS, FAILED, CANCELLED }
    }

    /** no-op fallback（LMili 未初始化） */
    final class Noop implements StructuredConcurrency {
        public static final Noop INSTANCE = new Noop();
        private Noop() {}
        @Override public @NotNull <T> SubTask<T> fork(@NotNull Callable<T> task) {
            return new SubTask<>() {
                @Override public T get() throws Exception { return task.call(); }
                @Override public T get(long timeout, java.util.concurrent.TimeUnit unit) throws Exception { return task.call(); }
                @Override public State state() { return State.SUCCESS; }
            };
        }
        @Override public void join() {}
        @Override public void close() {}
    }
}
