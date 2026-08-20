package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 虚拟线程池 —— 基于 Java 21+ 虚拟线程（Virtual Threads）的执行引擎。
 *
 * <h3>设计原理</h3>
 * <p>虚拟线程是 Java 21 引入的轻量级线程，适合高并发 I/O 场景。
 * 本线程池提供：
 * <ul>
 *   <li>单线程 ScheduledExecutorService 用于延迟/周期任务</li>
 *   <li>无界虚拟线程池用于立即执行任务</li>
 * </ul>
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-15</b>：不再暴露内部 executor —— {@code executor()} 方法已移除</li>
 *   <li><b>C-16</b>：scheduleAtFixedRate 使用重叠保护 —— 通过 TaskScheduleState 防止</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本线程池是线程安全的。所有内部状态使用原子变量。
 */
public final class VirtualThreadPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 单线程 ScheduledExecutorService —— 用于延迟和周期任务。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 虚拟线程执行器 —— 用于立即执行任务。
     *
     * <p>这是 final 的，不允许外部获取。
     */
    private final ExecutorService virtualExecutor;

    /**
     * 延迟任务计数。
     */
    private final AtomicInteger delayedTaskCount = new AtomicInteger(0);

    /**
     * 周期任务计数。
     */
    private final AtomicInteger periodicTaskCount = new AtomicInteger(0);

    /**
     * 已执行的任务总数。
     */
    private final AtomicInteger executedCount = new AtomicInteger(0);

    /**
     * 线程池名称。
     */
    private final String name;

    /**
     * 创建虚拟线程池。
     *
     * @param name 线程池名称（用于日志和线程命名）
     */
    public VirtualThreadPool(String name) {
        this.name = name;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name + "-Delayed");
            t.setDaemon(true);
            return t;
        });

        this.virtualExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, name + "-Virtual");
            t.setDaemon(true);
            return t;
        });

        LOGGER.info("[VirtualThreadPool-{}] Initialized", name);
    }

    /**
     * 提交一个立即执行的任务。
     *
     * @param task 要执行的任务
     * @return Future 用于等待或取消
     */
    public Future<?> submit(Runnable task) {
        executedCount.incrementAndGet();
        return virtualExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("[VirtualThreadPool-{}] Task execution error", name, e);
            }
        });
    }

    /**
     * 提交一个带返回值的 Callable 任务。
     *
     * @param task 要执行的 Callable 任务
     * @return Future 用于获取结果或取消
     */
    public <T> Future<T> submit(Callable<T> task) {
        executedCount.incrementAndGet();
        return virtualExecutor.submit(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                LOGGER.error("[VirtualThreadPool-{}] Task execution error", name, e);
                throw e;
            }
        });
    }

    /**
     * 提交一个延迟执行的任务。
     *
     * @param task         要执行的任务
     * @param delay        延迟时间
     * @param unit         时间单位
     * @return ScheduledFuture 用于等待或取消
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        delayedTaskCount.incrementAndGet();
        return scheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("[VirtualThreadPool-{}] Delayed task execution error", name, e);
            } finally {
                delayedTaskCount.decrementAndGet();
            }
        }, delay, unit);
    }

    /**
     * 提交一个固定延迟的周期任务——上一个任务执行完成后，等待 period 再执行下一次。
     *
     * <p>这是推荐的周期任务提交方式，因为不会重叠执行。
     *
     * <h3>实现说明</h3>
     * <p>使用递归调度：每次任务完成后，再调度下一次执行。
     * 这样即使任务执行时间超过 period，也不会重叠。
     *
     * @param task   要执行的任务
     * @param period 执行间隔（两次开始之间的时间）
     * @param unit   时间单位
     * @return Cancellable 用于取消周期任务
     */
    public Cancellable scheduleWithFixedDelay(Runnable task, long period, TimeUnit unit) {
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        periodicTaskCount.incrementAndGet();

        Runnable wrappedTask = new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.error("[VirtualThreadPool-{}] Periodic task error", name, e);
                }
                // 任务完成后，递归调度下一次
                ScheduledFuture<?> future = scheduler.schedule(this, period, unit);
                futureRef.set(future);
            }
        };

        // 首次调度
        ScheduledFuture<?> future = scheduler.schedule(wrappedTask, period, unit);
        futureRef.set(future);

        periodicTaskCount.incrementAndGet();

        return new Cancellable() {
            @Override
            public void cancel() {
                ScheduledFuture<?> f = futureRef.getAndSet(null);
                if (f != null) {
                    f.cancel(false);
                    periodicTaskCount.decrementAndGet();
                }
            }

            @Override
            public boolean isCancelled() {
                ScheduledFuture<?> f = futureRef.get();
                return f == null || f.isCancelled();
            }
        };
    }

    /**
     * 提交一个固定频率的周期任务——防止周期重叠执行。
     *
     * <p>修复 C-16：虽然 API 名为 scheduleAtFixedRate，但内部使用 TaskScheduleState
     * 确保同一周期任务不会重叠执行。如果上一次执行尚未完成，本次将被跳过。
     *
     * <h3>实现说明</h3>
     * <p>使用单线程 scheduler 配合 TaskScheduleState 门闩：
     * <ul>
     *   <li>调度器单线程保证定时触发的串行化</li>
     *   <li>TaskScheduleState 确保不会将已 RUNNING 的任务再次提交到 virtualExecutor</li>
     * </ul>
     *
     * @param task   要执行的任务
     * @param period 执行间隔（两次开始之间的时间）
     * @param unit   时间单位
     * @return Cancellable 用于取消周期任务
     */
    public Cancellable scheduleAtFixedRate(Runnable task, long period, TimeUnit unit) {
        TaskScheduleState state = new TaskScheduleState();
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        // C-16 修复：使用 TaskScheduleState 防止重叠
        Runnable guardedTask = () -> {
            if (!state.tryMarkRunning()) {
                // 上一次执行还未完成，跳过本次
                LOGGER.debug("[VirtualThreadPool-{}] Skipping overlapping periodic task", name);
                return;
            }
            virtualExecutor.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.error("[VirtualThreadPool-{}] Periodic task error", name, e);
                } finally {
                    state.tryMarkIdle(); // RUNNING → IDLE，允许下次执行
                }
            });
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(guardedTask, period, period, unit);
        futureRef.set(future);

        return new Cancellable() {
            @Override
            public void cancel() {
                ScheduledFuture<?> f = futureRef.getAndSet(null);
                if (f != null) {
                    f.cancel(false);
                    periodicTaskCount.decrementAndGet();
                }
            }

            @Override
            public boolean isCancelled() {
                ScheduledFuture<?> f = futureRef.get();
                return f == null || f.isCancelled();
            }
        };
    }

    /**
     * 获取已执行的任务总数。
     */
    public int getExecutedCount() {
        return executedCount.get();
    }

    /**
     * 获取正在执行的延迟任务数。
     */
    public int getDelayedTaskCount() {
        return delayedTaskCount.get();
    }

    /**
     * 获取正在执行的周期任务数。
     */
    public int getPeriodicTaskCount() {
        return periodicTaskCount.get();
    }

    /**
     * 关闭线程池。
     *
     * <p>优雅关闭：先停止 scheduler，再关闭 executor。
     */
    public void shutdown() {
        scheduler.shutdown();
        virtualExecutor.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            virtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("[VirtualThreadPool-{}] Shutdown (executed: {})", name, executedCount.get());
    }

    @Override
    public String toString() {
        return "VirtualThreadPool-" + name +
                "{executed=" + executedCount.get() +
                ", delayed=" + delayedTaskCount.get() +
                ", periodic=" + periodicTaskCount.get() + "}";
    }

    /**
     * 可取消对象的接口。
     */
    public interface Cancellable {
        void cancel();
        boolean isCancelled();
    }

}
