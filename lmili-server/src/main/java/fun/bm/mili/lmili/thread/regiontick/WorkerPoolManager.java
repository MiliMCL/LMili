package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.suspend.MiliThreadFactory;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Worker Pool 生命周期管理 —— 管理 virtual/platform 线程池。
 *
 * <p>支持两种 worker 模式：
 * <ul>
 *   <li><b>Virtual Thread 模式</b>（推荐，JDK 24+）：使用虚拟线程作为 worker，
 *       适合高并发场景，不受 OS 线程数限制。</li>
 *   <li><b>Platform Thread 模式</b>：传统固定大小线程池，适合保守部署或兼容性需求。</li>
 * </ul>
 */
public final class WorkerPoolManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService workerPool;
    private final boolean useVirtualThreads;
    private final RegionTickWorker[] workers;

    public WorkerPoolManager(int workerCount, boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;

        if (useVirtualThreads) {
            this.workers = null;
            this.workerPool = Executors.newThreadPerTaskExecutor(
                    MiliThreadFactory.virtual("RegionTickPool-Virtual-"));
            LOGGER.info("[RegionTickPool] Worker pool: virtual thread per task");
        } else {
            this.workers = new RegionTickWorker[workerCount];
            for (int i = 0; i < workerCount; i++) {
                this.workers[i] = new RegionTickWorker("RegionTickPool-Worker-" + i);
            }
            this.workerPool = Executors.newFixedThreadPool(workerCount,
                    MiliThreadFactory.platform("RegionTickPool-Worker-"));
            LOGGER.info("[RegionTickPool] Worker pool: {} platform threads", workerCount);

            for (RegionTickWorker worker : this.workers) {
                this.workerPool.submit(worker);
            }
        }
    }

    /**
     * 获取执行器服务。
     */
    public ExecutorService getExecutor() {
        return workerPool;
    }

    /**
     * 是否为 virtual thread 模式。
     */
    public boolean isVirtualThreadMode() {
        return useVirtualThreads;
    }

    /**
     * 获取 platform worker 数组（仅 platform 模式）。
     */
    public RegionTickWorker[] getWorkers() {
        return workers;
    }

    /**
     * 获取 worker 数量。
     */
    public int getWorkerCount() {
        return useVirtualThreads ? -1 : (workers != null ? workers.length : 0);
    }

    /**
     * 关闭线程池。
     */
    public void shutdown() {
        // 修复：添加空值检查，避免 NPE
        if (workers != null) {
            for (RegionTickWorker worker : workers) {
                if (worker != null) {
                    worker.shutdown();
                }
            }
        }
        // 修复：添加空值检查
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
