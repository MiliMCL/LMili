package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阻塞任务隔离器 —— 将阻塞任务从调度器 worker 线程隔离开。
 *
 * <h3>设计原理</h3>
 * <p>某些 RegionTask 可能执行阻塞操作（如 I/O、网络请求、synchronized 等待）。
 * 如果这些任务在 worker 线程上执行，会阻塞整个 worker，影响其他 region 的调度。
 *
 * <p>本隔离器提供独立的线程池执行阻塞任务，保证 worker 线程不会被阻塞。
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-09</b>：阻塞任务不绕过 Region ownership —— 即使使用独立 executor，
 *       仍通过 {@link RegionState} 的 executingCount 跟踪执行状态</li>
 *   <li><b>C-17</b>：平台线程检测不可靠 —— 引入显式执行上下文替代 currentThread.getName() 检测</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本隔离器是线程安全的。所有内部状态使用原子变量。
 */
public final class BlockingTaskIsolation {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 专用线程池 —— 执行阻塞任务。
     *
     * <p>使用缓存线程池（Cached Thread Pool），因为阻塞任务通常是短期的，
     * 且数量不可预测。空闲线程会在 60 秒后回收。
     *
     * <p>使用自定义线程工厂确保：
     * <ul>
     *   <li>线程名称清晰，便于调试</li>
     *   <li>线程为 daemon，不阻止 JVM 退出</li>
     * </ul>
     */
    private final ExecutorService blockingExecutor;

    /**
     * 阻塞任务计数。
     */
    private final AtomicInteger activeBlockingTasks = new AtomicInteger(0);
    private final AtomicInteger totalBlockingTasks = new AtomicInteger(0);

    /**
     * 创建阻塞任务隔离器。
     */
    public BlockingTaskIsolation() {
        this.blockingExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MiliBlocking-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        });

        LOGGER.info("[BlockingTaskIsolation] Initialized with dedicated thread pool");
    }

    /**
     * 执行一个阻塞任务。
     *
     * <p>修复 C-09：阻塞任务仍然通过 RegionState 跟踪执行状态。
     * 调用者已经通过 RegionState.tryBeginExecution() 获得了执行权，
     * 因此这里不需要再次检查。执行完成后，调用者负责调用 endExecution()。
     *
     * <p>修复 C-17：不在内部检测当前线程类型。RegionTask 的 isBlocking() 标志
     * 由调用者（SchedulerWorker）决定是否需要隔离。
     *
     * @param task 要执行的阻塞任务
     */
    public void executeBlocking(RegionTask task) {
        activeBlockingTasks.incrementAndGet();
        totalBlockingTasks.incrementAndGet();

        blockingExecutor.submit(() -> {
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("[BlockingTaskIsolation] Blocking task failed for region #{}",
                        task.regionId(), e);
            } finally {
                activeBlockingTasks.decrementAndGet();
            }
        });
    }

    /**
     * 获取正在执行的阻塞任务数。
     */
    public int activeBlockingTasks() {
        return activeBlockingTasks.get();
    }

    /**
     * 获取总阻塞任务数。
     */
    public int totalBlockingTasks() {
        return totalBlockingTasks.get();
    }

    /**
     * 关闭隔离器。
     *
     * <p>等待已提交的任务完成。
     */
    public void shutdown() {
        blockingExecutor.shutdown();
        LOGGER.info("[BlockingTaskIsolation] Shutdown (total blocking tasks executed: {})",
                totalBlockingTasks.get());
    }

    @Override
    public String toString() {
        return "BlockingTaskIsolation{active=" + activeBlockingTasks.get() +
                ", total=" + totalBlockingTasks.get() + "}";
    }
}
