package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.Mili;
import fun.bm.mili.api.internal.PublicSchedulerAdapter;
import fun.bm.mili.lmili.thread.scheduler.MiliSchedulerHolder;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import org.slf4j.Logger;

/**
 * RegionTickBootstrap —— 统一 scheduler 引导器（单 runtime 修复后）。
 *
 * <p><b>背景</b>：本类历史上会自行创建一个独立的 {@code region-scheduler}，与
 * {@code MiliTickRegionScheduler} 内部的 {@code tick-region-scheduler} 并存，造成
 * 双 runtime 问题（两套 Worker Pool、两套 WorkStealing、两套阻塞任务隔离）。</p>
 *
 * <p><b>当前行为</b>：不再独立创建 MiliScheduler；改为从
 * {@link MiliSchedulerHolder#get()} 取得共享实例（由 Folia 区域 tick 路径首先创建）。
 * 如果共享实例尚未初始化（例如非 Folia 环境直接调用），则懒构造一个 tickThreads=false 的实例。</p>
 *
 * <p>另外把 DAG 系统的节点路由接入按 regionId 路由的 {@code NodeScheduler}，
 * 确保跨 region 节点重新走 Folia 的 acquire 路径（不会绕过 region acquire 直接执行）。</p>
 *
 * <p>关闭顺序（由 {@code TickRegions.shutdown} 调用）：
 * <ol>
 *   <li>关闭公共 API：注销 {@code Mili.scheduler()} 引用</li>
 *   <li>关闭 RegionTickDispatcher（DAG + entity dispatcher）</li>
 *   <li>实际关闭 MiliScheduler 的工作由 {@code MiliTickRegionScheduler.halt()} 负责（唯一所有者）</li>
 * </ol>
 * </p>
 */
public final class RegionTickBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean initialized;
    private static volatile MiliScheduler scheduler;

    private RegionTickBootstrap() {}

    public static synchronized void init() {
        if (initialized) return;
        try {
            RegionTickDispatcher.init();

            // 不再独立创建 MiliScheduler —— 复用 Holder 单例。
            // 正常路径下，MiliTickRegionScheduler 已在 TickRegions.init() 中创建了共享 scheduler。
            // 如果还未创建（例如独立运行 RegionTickBootstrap），懒构造一个非 tick 的实例。
            MiliScheduler shared = MiliSchedulerHolder.get();
            if (shared == null) {
                LOGGER.warn("[RegionTickPool] Shared MiliScheduler not yet initialized; "
                        + "creating fallback instance lazily");
                shared = MiliSchedulerHolder.getOrCreate(
                        Runtime.getRuntime().availableProcessors(),
                        false,
                        "RegionTickBootstrap-fallback");
            }
            scheduler = shared;

            // 把 RegionTickDispatcher 的 DAG executor 节点执行接入共享 scheduler
            RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
            if (dispatcher != null) {
                dispatcher.attachScheduler(shared);
            }

            // 注册公共 API —— 让 Mili.scheduler() 可用
            Mili.registerScheduler(new PublicSchedulerAdapter(scheduler));

            initialized = true;
            LOGGER.info("[RegionTickPool] Initialization complete "
                    + "(shared scheduler={}, owner={}, DAG wired)",
                    scheduler.getClass().getSimpleName(),
                    String.valueOf(MiliSchedulerHolder.getOwner()));
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Initialization failed", throwable);
        }
    }

    /**
     * 关闭 RegionTick 子系统（不关闭共享 MiliScheduler —— 由唯一所有者负责）。
     *
     * <p>本方法在 {@code TickRegions.shutdown} 中先于
     * {@code MiliTickRegionScheduler.halt()} 调用，确保：
     * <ul>
     *   <li>公共 API 引用已注销（{@code Mili.scheduler()} 回到 Noop）</li>
     *   <li>RegionTickDispatcher 内部的 worker 池与 DAG 已停止</li>
     * </ul>
     * 之后唯一所有者调用 Holder.shutdown() 即可干净退出。</p>
     */
    public static synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;
        try {
            RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
            // 不关闭 scheduler —— 它由 MiliTickRegionScheduler.halt() 关闭
            scheduler = null;
            Mili.resetScheduler();
            LOGGER.info("[RegionTickPool] Shutdown complete (scheduler still alive, owned by MiliTickRegionScheduler)");
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Shutdown error", throwable);
        }
    }

    public static boolean isInitialized() { return initialized; }
}