package fun.bm.mili.lmili.thread.regiontick;

import java.util.concurrent.atomic.AtomicBoolean;
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
 */
public final class AsyncCatcherManager {

    private final AtomicInteger refCount = new AtomicInteger(0);
    private final AtomicBoolean baseline = new AtomicBoolean(false);

    /**
     * 获取 async catcher 引用 —— 进入 virtual dispatch 时调用。
     */
    public synchronized void acquire() {
        if (refCount.incrementAndGet() == 1) {
            baseline.set(fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled);
            fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled = true;
        }
    }

    /**
     * 释放 async catcher 引用 —— virtual dispatch 完成时调用。
     */
    public synchronized void release() {
        if (refCount.decrementAndGet() == 0) {
            fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled =
                    baseline.get();
        }
    }

    /**
     * 返回当前引用计数。
     */
    public int getRefCount() {
        return refCount.get();
    }
}
