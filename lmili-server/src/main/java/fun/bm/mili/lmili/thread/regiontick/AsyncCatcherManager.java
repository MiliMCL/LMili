package fun.bm.mili.lmili.thread.regiontick;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async Catcher 引用计数器 —— 管理跨 region 的 async catcher 绕过状态。
 *
 * <p>当任意 region 正在进行 virtual dispatch 时，所有参与计数的 region 都无条件计入。
 * 首个 dispatch（计数 0→1）记录用户配置基线并强制启用绕过；
 * 最后一个完成的 dispatch（计数 1→0）恢复基线。
 *
 * <p>使用同步块确保计数检查和状态修改的原子性，防止
 * "Thread failed main thread check" 竞态崩溃。
 *
 * <p><b>修复说明</b>：使用运行时状态标志 {@link #runtimeOverrideActive} 替代直接修改全局配置，
 * 避免多实例并发操作时互相覆盖 baseline 的问题。配置检查逻辑应同时考虑原始配置和运行时状态。
 */
public final class AsyncCatcherManager {

    private final AtomicInteger refCount = new AtomicInteger(0);

    /**
     * 运行时状态标志：是否有任何 virtual dispatch 正在进行。
     * 当此标志为 true 时，应绕过 async catcher 检查。
     * 使用 volatile 保证跨线程可见性。
     */
    private static volatile boolean runtimeOverrideActive = false;

    /**
     * 获取 async catcher 引用 —— 进入 virtual dispatch 时调用。
     */
    public synchronized void acquire() {
        refCount.incrementAndGet();
        // 设置运行时标志，绕过 async catcher
        runtimeOverrideActive = true;
    }

    /**
     * 释放 async catcher 引用 —— virtual dispatch 完成时调用。
     */
    public synchronized void release() {
        if (refCount.decrementAndGet() == 0) {
            // 所有 dispatch 完成，清除运行时标志
            runtimeOverrideActive = false;
        }
    }

    /**
     * 返回当前引用计数。
     */
    public int getRefCount() {
        return refCount.get();
    }

    /**
     * 检查当前是否应绕过 async catcher。
     * 此方法供配置检查逻辑调用，应同时考虑用户配置和运行时状态。
     *
     * @return true 如果应绕过 async catcher
     */
    public static boolean shouldBypassAsyncCatcher() {
        // 用户配置启用 OR 运行时 virtual dispatch 进行中
        return fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled
                || runtimeOverrideActive;
    }

    /**
     * 清除运行时状态（仅在服务器关闭或紧急情况下调用）。
     */
    public static void clearRuntimeState() {
        runtimeOverrideActive = false;
    }
}
