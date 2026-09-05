package fun.bm.mili.lmili.thread.runtime.lifecycle;

import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * P1-1（计划 §6）：Region 生命周期统一入口。
 *
 * <p>所有持有 per-region 数据的组件把清理逻辑注册为一个 {@link Hook}；
 * region 销毁时由 {@link #destroyRegion(long)} 统一按注册顺序扇出，
 * 消除 "多个类各自维护 onRegionDestroyed / unregisterRegion / cleanupRegion"
 * 却没有统一生命周期的反模式。</p>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li><b>幂等</b> —— 对同一 regionId 重复 destroy 是无操作</li>
 *   <li><b>错误隔离</b> —— 单个 hook 抛错不影响其余 hook；错误计数进 metrics</li>
 *   <li><b>可观测</b> —— destroyed count / hook errors 可查询</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>{@link #destroyRegion} 任意线程可调用（Folia 在 region 销毁线程上调用）；
 * hooks 列表为 CopyOnWriteArrayList；per-region 幂等集合同步控制。</p>
 */
public final class RegionLifecycleManager {

    private static final Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** 单个 per-region 清理钩子。 */
    public interface Hook {
        /** 钩子名称（诊断用）。 */
        String name();

        /**
         * 执行清理。必须幂等、不得抛出 checked 异常；
         * 运行时异常由 manager 隔离并记录。
         *
         * @param regionId 被销毁的 region
         */
        void onRegionDestroyed(long regionId);
    }

    private static final RegionLifecycleManager INSTANCE = new RegionLifecycleManager();

    public static RegionLifecycleManager get() { return INSTANCE; }

    /**
     * 创建独立实例 —— 测试 / 隔离环境用。生产路径必须使用 {@link #get()} 单例，
     * 否则 dispatcher 注册的钩子不会进入你的实例。
     */
    public static RegionLifecycleManager newInstance() { return new RegionLifecycleManager(); }

    private final List<Hook> hooks = new CopyOnWriteArrayList<>();
    /**
     * 已销毁 regionId 墓碑 —— 同时提供并发互斥与历史幂等。
     * 有界（{@link #TOMBSTONE_CAP}）：达到容量时整体清空，
     * 最坏情况是海量销毁后允许一次重复 hook 执行；真实 hook 本身必须幂等
     * （对已删除条目重复 remove 是无害操作）。
     */
    private final java.util.Set<Long> destroyedTombstones = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int TOMBSTONE_CAP = 65_536;

    private final LongAdder totalDestroyed = new LongAdder();
    private final LongAdder totalDuplicateDestroyed = new LongAdder();
    private final LongAdder totalHookErrors = new LongAdder();
    /** 每个 hook 累计失败次数（诊断用，key = hook.name()）。 */
    private final ConcurrentHashMap<String, LongAdder> hookErrorCounts = new ConcurrentHashMap<>();

    private RegionLifecycleManager() {}

    /**
     * 注册一个清理钩子（region tick dispatcher 启动时调用）。
     * 同名钩子不会重复注册（幂等）。
     */
    public void registerHook(Hook hook) {
        if (hook == null) throw new IllegalArgumentException("hook");
        boolean exists = hooks.stream().anyMatch(h -> h.name().equals(hook.name()));
        if (!exists) {
            hooks.add(hook);
            LOGGER.info("[RegionLifecycle] Registered cleanup hook '{}'", hook.name());
        }
    }

    /** 已注册的钩子名列表（诊断 / 测试用）。 */
    public List<String> registeredHookNames() {
        return hooks.stream().map(Hook::name).toList();
    }

    /**
     * 销毁一个 region —— 按注册顺序执行全部钩子。
     *
     * <p>P0-5 的 exactly-once 语义同理适用于此：同一 regionId 并发/重复 destroy
     * 只有一次真正执行。</p>
     *
     * @return true 如果本次调用实际执行了销毁流程
     */
    public boolean destroyRegion(final long regionId) {
        // 原子声明销毁权：墓碑集合同时拦截并发与顺序重复调用
        if (destroyedTombstones.size() >= TOMBSTONE_CAP) {
            destroyedTombstones.clear();   // 有界保护 —— 见字段文档
        }
        if (!destroyedTombstones.add(regionId)) {
            totalDuplicateDestroyed.increment();
            return false;   // duplicate destroy —— idempotent no-op
        }
        LOGGER.info("[RegionLifecycle] Destroying region #{} ({} hooks)", regionId, hooks.size());
        for (Hook hook : hooks) {
            try {
                hook.onRegionDestroyed(regionId);
            } catch (Throwable t) {
                totalHookErrors.increment();
                hookErrorCounts
                        .computeIfAbsent(hook.name(), k -> new LongAdder())
                        .increment();
                LOGGER.error("[RegionLifecycle] Cleanup hook '{}' failed for region #{}",
                        hook.name(), regionId, t);
                // 错误隔离：继续执行下一个 hook
            }
        }
        totalDestroyed.increment();
        return true;
    }

    public long getTotalDestroyed() { return totalDestroyed.sum(); }
    public long getTotalHookErrors() { return totalHookErrors.sum(); }

    /** 获取指定 hook 的累计失败次数（诊断用）。 */
    public long getHookErrorCount(String hookName) {
        LongAdder adder = hookErrorCounts.get(hookName);
        return adder != null ? adder.sum() : 0L;
    }
}