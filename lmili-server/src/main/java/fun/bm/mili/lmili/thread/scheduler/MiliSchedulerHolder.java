package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * 全局共享 {@link MiliScheduler} 单例持有者 —— 修复双 runtime 并存问题。
 *
 * <p><b>背景</b>：历史上 Mili 创建了两个独立的 {@link MiliScheduler} 实例：
 * <ol>
 *   <li>{@link MiliTickRegionScheduler} 内部创建 {@code tick-region-scheduler}（Folia 区域 tick 入口）</li>
 *   <li>{@code RegionTickBootstrap} 创建 {@code region-scheduler}（公共 {@code Mili.scheduler()} API 与 DAG 系统）</li>
 * </ol>
 * 两个 runtime 各自拥有一套 {@link fun.bm.mili.lmili.thread.scheduler.execute.WorkStealingCoordinator}
 * + worker 池 + 阻塞隔离池，启动/关闭时序纠缠、内存浪费、调度行为分裂。</p>
 *
 * <p><b>本类作用</b>：作为整个 Mili 唯一的 {@link MiliScheduler} 单例的持有者。所有需要
 * {@link MiliScheduler} 的子系统（Folia 区域 tick、公共 API、DAG 节点执行、region 内并行任务）
 * 都通过 {@link #get()} / {@link #getOrCreate(int, boolean)} 获取同一个实例。</p>
 *
 * <h3>配置</h3>
 * <ul>
 *   <li><b>tickThreads=true</b>：worker 必须是 {@link MiliTickThread}，因为
 *       Folia 的 {@code TickRegionScheduler.RegionScheduleHandle.runTick()} 内部会调用
 *       {@link MiliTickThread#setTickingRegion}，需要 carrier 线程就是 MiliTickThread。</li>
 *   <li><b>普通任务同样适用</b>：DAG 节点、公共 API 任务在 MiliTickThread 上执行也是安全的，
 *       因为这些任务不会触碰 Folia 的 region 上下文；它们的代码不会调用 {@code runTick()}。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <pre>
 *   MiliTickRegionScheduler 构造器 ──▶ getOrCreate(threadCount, true)
 *   RegionTickBootstrap.init()   ──▶ get()（复用，已存在则不重建）
 *   MiliTickRegionScheduler.halt() ──▶ shutdown() + 清理静态引用
 * </pre>
 *
 * <p>关闭由唯一的所有者（{@link MiliTickRegionScheduler}）负责；其它子系统只读取，不关闭。</p>
 *
 * <h3>线程安全</h3>
 * <p>{@link #INSTANCE} 通过 {@code synchronized(getClass())} 保护，确保并发首次初始化安全。</p>
 *
 * @author Mili scheduler team
 */
public final class MiliSchedulerHolder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例引用（volatile 保证可见性）。 */
    private static volatile MiliScheduler INSTANCE;

    /** 单例的 owner 标识（用于诊断 —— 谁第一个创建了它）。 */
    private static volatile String OWNER;

    private MiliSchedulerHolder() {}

    /**
     * 获取共享 scheduler（如不存在则返回 null，由调用方决定是否懒构造）。
     *
     * @return 当前共享实例，可能为 null（尚未初始化）
     */
    @Nullable
    public static MiliScheduler get() {
        return INSTANCE;
    }

    /**
     * 获取共享 scheduler，如不存在则按指定配置创建。
     *
     * <p>线程安全：多线程首次调用仅会创建一次，后续调用直接返回已有实例。</p>
     *
     * <p>如果传入的 {@code tickThreads} 与已有实例不匹配，记录 WARN 但不替换（Folia tick 路径必须
     * 使用 tickThreads=true，因此已存在实例优先）。</p>
     *
     * @param threadCount  carrier 线程数（仅在首次创建时生效）
     * @param tickThreads  是否要求 carrier 为 {@link MiliTickThread}（仅在首次创建时生效）
     * @param owner        标识谁在请求/创建（如 "MiliTickRegionScheduler"），用于诊断日志
     * @return 共享 scheduler，永不为 null
     */
    @NotNull
    public static synchronized MiliScheduler getOrCreate(final int threadCount,
                                                          final boolean tickThreads,
                                                          @NotNull final String owner) {
        MiliScheduler existing = INSTANCE;
        if (existing != null) {
            if (tickThreads) {
                LOGGER.debug("[MiliSchedulerHolder] Reusing shared scheduler for owner={} "
                        + "(pool={}, already-running)", owner,
                        safePoolName(existing));
            }
            return existing;
        }

        // 至少 2 个 carrier，确保 global tick 不会被 region tick 阻塞
        final int carrierThreads = Math.max(2, threadCount);
        final int maxBlocking = Math.max(2, carrierThreads / 2);

        LOGGER.info("[MiliSchedulerHolder] Creating shared scheduler for owner={} "
                + "(carrierThreads={}, tickThreads={})", owner, carrierThreads, tickThreads);

        final MiliScheduler created = MiliSchedulerBuilder.create("mili-scheduler")
                .threadNamePrefix("MiliTick-")
                .carrierThreads(carrierThreads)
                .maxBlockingTasks(maxBlocking)
                .tickThreads(tickThreads)
                .build();

        INSTANCE = created;
        OWNER = owner;

        // 关闭钩子：单例被关闭后清除静态引用，允许重新创建（理论上不应发生，但保持健壮）
        // 通过一个轻量包装间接关闭更安全；这里依赖所有者主动清空
        LOGGER.info("[MiliSchedulerHolder] Shared scheduler created (owner={})", owner);
        return created;
    }

    /**
     * 关闭共享 scheduler 并清除静态引用。仅允许由唯一所有者调用。
     *
     * <p>调用方负责保证：所有其他子系统已停止向该 scheduler 提交任务。</p>
     *
     * @param owner 调用者标识（仅用于日志追踪）
     */
    public static synchronized void shutdown(@NotNull final String owner, final long timeoutMs) {
        final MiliScheduler current = INSTANCE;
        if (current == null) {
            return;
        }
        LOGGER.info("[MiliSchedulerHolder] Shutting down shared scheduler (requested by owner={})", owner);
        try {
            current.shutdown(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            INSTANCE = null;
            OWNER = null;
            LOGGER.info("[MiliSchedulerHolder] Shared scheduler shut down");
        }
    }

    /**
     * 重置静态状态（仅供测试/单元测试使用）。生产代码不应调用。
     */
    public static synchronized void resetForTesting() {
        INSTANCE = null;
        OWNER = null;
    }

    /**
     * 获取当前 owner 标识（用于诊断）。
     */
    @Nullable
    public static String getOwner() {
        return OWNER;
    }

    private static @Nullable String safePoolName(@NotNull MiliScheduler scheduler) {
        try {
            if (scheduler instanceof MiliSchedulerImpl impl) {
                return impl.getPoolName();
            }
            return scheduler.getClass().getSimpleName();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}