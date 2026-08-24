package fun.bm.mili.lmili.runtime.io;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * O-Linear flusher 桥 —— 把现有 {@link OptimizedLinearRegionFileFlusher} 接进反馈环
 * （ARCHITECTURE_AdaptiveRuntime.md §3.16：反馈环的"O-Linear 侧"，最关键的新类）。
 *
 * <p>设计原则（D-08）：<strong>不改造 flusher 内部</strong>，通过可选观察者钩子
 * （{@link FlusherObserver}）在 flusher 的 checker 周期末尾回调本桥；本桥统计计数器并暴露
 * {@link IOStateProbe}。若 flusher 未注入钩子（兼容旧路径），本桥仍可工作（计数为零）。
 *
 * <p>优先级数据流（D-22）：regionId → FlushPriority 映射表（ConcurrentHashMap），由
 * {@link PersistencePriority#evaluate}（合成公式）+ PolicyController 裁决后经
 * IOController.setFlushPriority 写入；flusher 派发 sync 前经本桥查询：
 * IO_PRESSURE 下 LOW/DEFERRED 任务暂缓派发（记入 pending）。本桥只消费最终等级，<strong>不参与打分</strong>。
 */
public final class OLinearFlusherBridge implements IOStateProbe, FlusherObserver {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 计数器（IO worker 线程写，LongAdder 无锁）----
    private final LongAdder totalSyncs = new LongAdder();
    private final LongAdder totalSyncNanos = new LongAdder();
    private final LongAdder totalBytesWritten = new LongAdder();
    private final LongAdder activeSyncs = new LongAdder();   // 进 add / 出 subtract
    private final LongAdder deferredSyncs = new LongAdder();
    private final LongAdder cycleCount = new LongAdder();
    // p99：环形直方图（2048 槽，纳秒对数分桶），采样读
    private final P99Histogram latencyHistogram = new P99Histogram();

    // ---- 优先级映射（regionId → FlushPriority）----
    private final ConcurrentHashMap<Long, FlushPriority> priorities = new ConcurrentHashMap<>();

    // ---- 文件 ↔ regionId 映射（观察者回调只有 file 句柄）----
    private final ConcurrentHashMap<OptimizedLinearRegionFile, Long> fileRegions = new ConcurrentHashMap<>();

    // ---- 待同步区（排队中/延后中的 region 集合）----
    private final ConcurrentHashMap.KeySetView<Long, Boolean> pending = ConcurrentHashMap.newKeySet();

    // ---- 现有 flusher 引用（包装，不修改内部；可能晚绑定）----
    private volatile OptimizedLinearRegionFileFlusher flusher;

    // ---- 压力标记（由 IOController 经受控通道写入）----
    private volatile boolean ioPressure = false;
    private volatile GlobalFlushPolicy globalPolicy = GlobalFlushPolicy.DEFAULTS;

    // ---- 窗口统计（bytesPerSecond）----
    private final AtomicLong lastWindowNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastWindowBytes = new AtomicLong(0);
    private final AtomicLong lastWindowSyncs = new AtomicLong(0);

    public OLinearFlusherBridge(@Nullable OptimizedLinearRegionFileFlusher flusher) {
        this.flusher = flusher;
    }

    /** 晚绑定 flusher（RegionFormatConfig 装配期回调；幂等） */
    public void attachFlusher(@Nullable OptimizedLinearRegionFileFlusher f) {
        if (f != null) {
            this.flusher = f;
        }
    }

    @Nullable
    public OptimizedLinearRegionFileFlusher flusher() {
        return flusher;
    }

    // ================= FlusherObserver 实现（flusher checker/IO worker 回调） =================

    /** 每个 checker 周期调用一次（flusher 单线程，天然串行） */
    @Override
    public void onCycleFinished(long cycleNanos) {
        cycleCount.increment();
        // 周期边界：无状态维护（计数保持累计；窗口统计在 sample() 计算）
    }

    /**
     * sync 派发前回调：IO_PRESSURE 下 LOW/DEFERRED（经全局策略映射后）暂缓派发。
     * 返回 false 时 flusher 会 clearBeingSynced()，下周期可重估 —— 数据不丢失。
     */
    @Override
    public boolean onDispatch(OptimizedLinearRegionFile file) {
        if (!ioPressure) {
            return true;
        }
        final Long regionId = fileRegions.get(file);
        if (regionId == null) {
            return true; // 未注册文件（旧路径直接 addFile）：不干预，fail-open
        }
        final FlushPriority p = priorityOf(regionId);
        if (globalPolicy.shouldDefer(p, true)) {
            pending.add(regionId);
            deferredSyncs.increment();
            return false;
        }
        return true;
    }

    /** sync 完成回调（成功/失败都调用；IO worker 线程） */
    @Override
    public void onSyncCompleted(OptimizedLinearRegionFile file, long nanos, long bytes, boolean success) {
        totalSyncs.increment();
        totalSyncNanos.add(nanos);
        if (bytes > 0) {
            totalBytesWritten.add(bytes);
        }
        activeSyncs.decrement();
        latencyHistogram.record(nanos);
        final Long regionId = fileRegions.get(file);
        if (regionId != null) {
            pending.remove(regionId);
        }
        if (!success) {
            LOGGER.warn("[OLinearFlusherBridge] sync failed for {}", file);
        }
    }

    /** 派发记账入口（flusher 侧在 onDispatch=true 后调用本桥以跟踪 activeSyncs） */
    public void onSyncDispatched() {
        activeSyncs.increment();
    }

    // ================= IOStateProbe（MetricsController 每控制周期采样） =================

    /** 无锁拍快照（仅加法器与原子读；窗口统计在采样点结算） */
    @Override
    public IOState sample() {
        final long now = System.nanoTime();
        final long queueDepth = activeSyncs.sum() + pending.size();
        final long p99 = latencyHistogram.p99();
        final IoSaturationLevel level = computeLevel(queueDepth, p99);

        // bytesPerSecond / syncedRegions 窗口（相对上次采样）
        final long windowNanos = Math.max(1, now - lastWindowNanos.get());
        final long bytes = totalBytesWritten.sum();
        final long syncs = totalSyncs.sum();
        final long bytesPerSecond = (long) ((bytes - lastWindowBytes.get()) * 1_000_000_000.0 / windowNanos);
        final long syncedRegions = syncs - lastWindowSyncs.get();
        lastWindowNanos.set(now);
        lastWindowBytes.set(bytes);
        lastWindowSyncs.set(syncs);

        final long avg = totalSyncs.sum() > 0 ? totalSyncNanos.sum() / totalSyncs.sum() : 0;
        final int poolSize = flusher != null ? flusher.ioPoolSize() : 0;
        return new IOState(
                queueDepth,
                (int) activeSyncs.sum(),
                poolSize,
                avg,
                p99,
                Math.max(0, bytesPerSecond),
                pending.size(),
                level.atLeast(IoSaturationLevel.SATURATED),
                level,
                syncedRegions,
                now
        );
    }

    /** 确定性分级映射（§4.2 公式） */
    static IoSaturationLevel computeLevel(long queueDepth, long p99Nanos) {
        if (queueDepth >= 5000 || p99Nanos >= 200_000_000L) {
            return IoSaturationLevel.CRITICAL;
        }
        if (queueDepth >= 2000 || p99Nanos >= 80_000_000L) {
            return IoSaturationLevel.SATURATED;
        }
        if (queueDepth >= 500 || p99Nanos >= 20_000_000L) {
            return IoSaturationLevel.BUSY;
        }
        return IoSaturationLevel.NORMAL;
    }

    // ================= 控制器接口（仅 IOController/PolicyController 调用） =================

    public void setPriority(long regionId, FlushPriority p) {
        if (p == null) {
            return;
        }
        priorities.put(regionId, p);
    }

    public FlushPriority priorityOf(long regionId) {
        return priorities.getOrDefault(regionId, FlushPriority.NORMAL);
    }

    /** 是否应延期该 region 的 flush（IO_PRESSURE 且（映射后）优先级 ≤ LOW 时 true） */
    public boolean shouldDefer(long regionId) {
        if (!ioPressure) {
            return false;
        }
        return globalPolicy.shouldDefer(priorityOf(regionId), true);
    }

    /** 压力标记（由 IOController.setIoPressureMode 经受控通道写入） */
    public void setIoPressure(boolean pressure) {
        this.ioPressure = pressure;
    }

    public boolean isIoPressure() {
        return ioPressure;
    }

    /** 全局 flush 策略（IO_PRESSURE 状态动作写入） */
    public void setGlobalPolicy(GlobalFlushPolicy policy) {
        if (policy != null) {
            this.globalPolicy = policy;
        }
    }

    public GlobalFlushPolicy globalPolicy() {
        return globalPolicy;
    }

    /** 注册一个受管 region 文件（初始优先级按合成公式由 IOController 计算后调用本方法设置） */
    public void registerFile(OptimizedLinearRegionFile file, long regionId, FlushPriority initialPriority) {
        if (file == null) {
            return;
        }
        fileRegions.put(file, regionId);
        priorities.putIfAbsent(regionId, initialPriority != null ? initialPriority : FlushPriority.NORMAL);
        final OptimizedLinearRegionFileFlusher f = flusher;
        if (f != null) {
            f.addFile(file);
        }
    }

    public void unregisterFile(OptimizedLinearRegionFile file) {
        if (file == null) {
            return;
        }
        final Long regionId = fileRegions.remove(file);
        if (regionId != null) {
            priorities.remove(regionId);
            pending.remove(regionId);
        }
        final OptimizedLinearRegionFileFlusher f = flusher;
        if (f != null) {
            f.removeFile(file);
        }
    }

    /** 进入关闭：清空延期，全部标记紧急（由 IOController.drainForShutdown 驱动；幂等） */
    public void markAllCritical() {
        pending.clear();
        for (Long regionId : priorities.keySet()) {
            priorities.put(regionId, FlushPriority.CRITICAL);
        }
        this.ioPressure = false; // 关闭期间不再延期
    }

    /** 待处理 region 数（shutdown 排水观测） */
    public int pendingCount() {
        return pending.size();
    }

    public long activeSyncCount() {
        return activeSyncs.sum();
    }

    public long deferredCount() {
        return deferredSyncs.sum();
    }

    /** 动态 worker 数（Phase 4；委托 flusher.resize，默认 fixed 池 no-op 语义） */
    public void setWorkerCount(int n) {
        final OptimizedLinearRegionFileFlusher f = flusher;
        if (f != null) {
            f.resize(n);
        }
    }
}
