package fun.bm.mili.config.modules.experiment;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.HotReloadUnsupported;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

/**
 * RegionTickPool 配置 —— Mili 统一调度器的并行 chunk tick 子系统。
 *
 * <p>RegionTickPool 已永久启用，无需配置开关。以下参数仅用于微调性能。
 */
@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "region_tick_pool")
public class RegionTickPoolConfig implements IConfigModule {

    @HotReloadUnsupported
    @ConfigInfo(name = "worker-count", comments = """
            RegionTickPool 的 worker 线程总数。
            默认为 (CPU 核心数 - 1)，最小为 2。""")
    public static int workerCount = 0;

    @ConfigInfo(name = "max-workers-per-region", comments = """
            单个 region 最多可分配的 worker 数。
            默认为 worker-count 的一半，最小为 1。""")
    public static int maxWorkersPerRegion = 0;

    @ConfigInfo(name = "parallelism-threshold", comments = """
            region 拥有的 chunk 数量达到此阈值时才启用并行 tick。
            低于此值的 region 由单线程 tick，避免调度开销。""")
    public static int parallelismThreshold = 4;

    @ConfigInfo(name = "slice-size", comments = """
            每个 tick 切片包含的 chunk 数量。
            较小值 → 更好的负载均衡但更多调度开销
            较大值 → 更少调度开销但可能负载不均""")
    public static int sliceSize = 16;

    @ConfigInfo(name = "min-workers-per-region", comments = """
            每个 region 最少保留的 worker 线程数。
            确保多 region 并发时，每个 region 都能获得足够的并行度，
            不会因为贪心分配导致某些 region 被"饿死"。
            默认为 2，最小为 1。""")
    public static int minWorkersPerRegion = 2;

    @ConfigInfo(name = "use-virtual-threads", comments = """
            是否使用 virtual thread 作为 worker (JDK 24+)。
            启用后 worker-count 变为软上限，实际线程按需创建。""")
    public static boolean useVirtualThreads = true;

    @ConfigInfo(name = "per-entity-warn-ms", comments = """
            单个实体 tick 耗时超过此阈值（毫秒）时输出警告诊断信息。
            用于定位导致性能问题的慢实体。默认 100ms。""")
    public static long perEntityWarnMs = 100;

    @ConfigInfo(name = "virtual-thread-timeout-ms", comments = """
            virtual thread 并行 tick 的完成超时（毫秒）。
            超时后下一 tick 将跳过执行（避免堆积）。
            必须小于 Folia watchdog 超时（默认 5000ms），建议预留至少 1s 余量。
            默认 4000ms。""")
    public static long virtualThreadTimeoutMs = 4000;

    public static int getWorkerCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (workerCount > 0) return Math.max(2, workerCount);
        return Math.max(2, cores - 1);
    }

    public static int getMaxWorkersPerRegion() {
        if (maxWorkersPerRegion > 0) return Math.max(1, maxWorkersPerRegion);
        return Math.max(1, getWorkerCount() / 2);
    }

    public static int getMinWorkersPerRegion() {
        return Math.max(1, minWorkersPerRegion);
    }
}
