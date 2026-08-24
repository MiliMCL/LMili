package fun.bm.mili.lmili.runtime.control;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.function.RegionFormatConfig;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.runtime.io.DirtyAgeSnapshot;
import fun.bm.mili.lmili.runtime.io.FlushPriority;
import fun.bm.mili.lmili.runtime.io.GlobalFlushPolicy;
import fun.bm.mili.lmili.runtime.io.IOState;
import fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge;
import fun.bm.mili.lmili.runtime.io.PersistencePriority;
import fun.bm.mili.lmili.runtime.io.PluginHint;
import fun.bm.mili.lmili.runtime.io.PluginHintResult;
import fun.bm.mili.lmili.runtime.io.RegionLoadSnapshot;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import fun.bm.mili.utils.region.RegionLoadMonitor;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * IO 控制入口 —— Scheduler ↔ O-Linear 反馈环的"调度器侧"（ARCHITECTURE_AdaptiveRuntime.md §3.7）。
 *
 * <p>本控制器是唯一持有 {@link OLinearFlusherBridge} 的组件；所有 IO 相关策略
 * （worker 数、flush 优先级、饱和度判定）都从这里进出。
 *
 * <p><strong>D-23 铁律</strong>：本控制器对 Tick 只能是策略提示（isSaturated/shouldDefer 只读）；
 * 任何改变行为的调用（setWorkerCount/setFlushPriority）仅由 PolicyController 编排调用。
 */
public final class IOController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final OLinearFlusherBridge bridge;

    /** 插件 hint 裁决通道（MiliRuntime 装配期接线；null = 未装配，申请被拒绝） */
    private volatile PolicyController policyController;

    /** 自动扩缩开关（Phase 5 §4.6 闭环；默认关闭，经测试/装配开启） */
    private volatile boolean autoScalingEnabled = false;

    // ---- 测试/故障注入缝隙（默认 null → 使用点直接走 bridge；circuit breaker 测试用）----
    private volatile IntConsumer workerCountApplier = null; // null = 走 bridge.setWorkerCount(n)
    private volatile Consumer<Long> flushPriorityApplier = null; // null = 直接走 bridge

    public IOController(OLinearFlusherBridge bridge) {
        this.bridge = bridge;
    }

    // ================= 反馈环：采样侧（MetricsController 每控制周期调用） =================

    /** 采样当前 IO 状态（非阻塞；委托 bridge.sample()；桥未接线返回中性值） */
    public IOState latestState() {
        try {
            final IOState st = bridge.sample();
            return st != null ? st : IOState.neutral();
        } catch (Throwable t) {
            LOGGER.warn("[IOController] IOState sample failed (fail-safe neutral)", t);
            return IOState.neutral();
        }
    }

    // ================= 反馈环：策略侧（仅 PolicyController 调用） =================

    /** 动态调整 IO worker 数（钳制 [1, RegionFormatConfig.olinearIoThreadCount × 4]；Phase 4 启用） */
    public void setWorkerCount(int n) {
        final int maxWorkers = maxIoWorkers();
        final int clamped = Math.max(1, Math.min(maxWorkers, n));
        final IntConsumer applier = workerCountApplier;
        try {
            if (applier != null) {
                applier.accept(clamped);
            } else {
                bridge.setWorkerCount(clamped); // 默认路径：直接走桥
            }
            LOGGER.debug("[IOController] worker count -> {}", clamped);
        } catch (Throwable t) {
            throw new IllegalStateException("IO worker resize failed: " + t.getMessage(), t);
        }
    }

    /** 最大 IO worker（RegionFormatConfig.olinearIoThreadCount × 4，兜底 24） */
    public int maxIoWorkers() {
        try {
            final int configured = RegionFormatConfig.olinearIoThreadCount;
            if (configured > 0) {
                return configured * 4;
            }
        } catch (Throwable ignored) {
            // RegionFormatConfig 未加载（测试环境）
        }
        return 24;
    }

    /** 设置某 region 的 flush 优先级（需求 #5：Region Priority → IO Priority；仅 PolicyController 调用） */
    public void setFlushPriority(long regionId, FlushPriority p) {
        if (p == null) {
            return;
        }
        final Consumer<Long> applier = flushPriorityApplier;
        if (applier != null) {
            applier.accept(regionId);
        } else {
            bridge.setPriority(regionId, p);
        }
    }

    /**
     * 插件 flush 优先级申请（PluginId 接入点，D-22）。
     *
     * <p><strong>只是 hint，不代表生效</strong>：内部生成 {@code PluginHint} 送
     * PolicyController 裁决队列（权限/配额/压力态），裁决结果（获准/降级/拒绝）
     * 经回调返回并记审计。插件不能决定最终等级。
     */
    public CompletableFuture<PluginHintResult> requestFlushPriority(
            long regionId, FlushPriority requested, String pluginId, String reason) {
        final PolicyController policy = policyController;
        if (policy == null || requested == null) {
            return CompletableFuture.completedFuture(
                    PluginHintResult.rejected(new PluginHint(
                            pluginId == null ? "unknown" : pluginId, regionId,
                            requested != null ? requested : FlushPriority.NORMAL,
                            reason == null ? "" : reason, System.nanoTime()),
                            "POLICY_CHANNEL_NOT_WIRED"));
        }
        return policy.adjudicatePluginHint(regionId, requested, pluginId, reason);
    }

    /** 全局 flush 优先级策略（如 CPU/IO_PRESSURE 时全局降级非关键 flush） */
    public void setGlobalFlushPolicy(GlobalFlushPolicy p) {
        bridge.setGlobalPolicy(p != null ? p : GlobalFlushPolicy.DEFAULTS);
    }

    public GlobalFlushPolicy globalFlushPolicy() {
        return bridge.globalPolicy();
    }

    /** 压力模式标记（IO_PRESSURE 状态动作经 PolicyController 调用） */
    public void setIoPressureMode(boolean pressure) {
        bridge.setIoPressure(pressure);
    }

    // ================= 供其他模块只读查询（非阻塞、无锁读） =================

    /** 是否饱和（IOState.level 高于 BUSY） */
    public boolean isSaturated() {
        return latestState().saturated();
    }

    /** 背压查询：某 region 的 flush 是否应延期（ChunkController.saveAll 使用；minPriority 为最低放行等级） */
    public boolean shouldDefer(long regionId, FlushPriority minPriority) {
        if (!bridge.shouldDefer(regionId)) {
            return false;
        }
        if (minPriority == null) {
            return true;
        }
        return bridge.priorityOf(regionId).rank() < minPriority.rank();
    }

    // ================= shutdown 编排（由 MiliRuntime.shutdown 直接调用；不经受控通道） =================

    /**
     * 进入关闭：提升所有 flush 至 CRITICAL，排水（有界等待），再放行 flusher.shutdown()。
     *
     * <p>调用前提（v2 关闭顺序）：PolicyController 已 freeze()，本方法是 shutdown 编排的
     * 专用原子路径 —— 此时不存在任何运行期策略写入，排水期间 IO 状态不会再被策略改动。
     */
    public void drainForShutdown(Duration timeout) {
        bridge.markAllCritical();
        final long deadline = System.nanoTime() + timeout.toNanos();
        // 有界排水：pending 清空且 activeSyncs 归零
        while (System.nanoTime() < deadline) {
            final IOState st = bridge.sample();
            if (st.pendingRegions() == 0 && st.activeWorkers() == 0 && st.queueDepth() == 0) {
                break;
            }
            LockSupport.parkNanos(100_000_000L); // 100ms
        }
        final IOState after = bridge.sample();
        if (after.pendingRegions() > 0 || after.activeWorkers() > 0) {
            // 不静默告警：数据风险必须显式暴露（§6.3 R2）
            LOGGER.error("[IOController] drainForShutdown timed out after {} with {} pending regions, {} active syncs — DATA RISK",
                    timeout, after.pendingRegions(), after.activeWorkers());
        } else {
            LOGGER.info("[IOController] IO drain complete (queue={}, pending={})", after.queueDepth(), after.pendingRegions());
        }
        // 放行 flusher 按现有顺序 shutdown（幂等 CAS 内部守卫）
        final fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher flusher = bridge.flusher();
        if (flusher != null) {
            flusher.shutdown();
        }
    }

    /** 提升所有待处理 flush 至 CRITICAL（shutdown 编排 step 4 专用；幂等） */
    public void raiseAllFlushToCritical() {
        bridge.markAllCritical();
    }

    // ================= 受管文件注册（D-22：初始优先级由 PersistencePriority 合成） =================

    /** 注册一个新受管 region 文件（委托 bridge.registerFile）；初始优先级由 PersistencePriority 合成（D-22） */
    public void registerFile(OptimizedLinearRegionFile file, long regionId) {
        if (file == null) {
            return;
        }
        final FlushPriority initial = initialPriorityOf(file, regionId);
        bridge.registerFile(file, regionId, initial);
        LOGGER.debug("[IOController] registered region file regionId={} priority={}", regionId, initial);
    }

    public void unregisterFile(OptimizedLinearRegionFile file) {
        bridge.unregisterFile(file);
    }

    /** 初始优先级合成：RegionLoad（RegionLoadMonitor 折算）+ DirtyAge（上次 sync 年龄） */
    private FlushPriority initialPriorityOf(OptimizedLinearRegionFile file, long regionId) {
        double maxLoad = 0.0;
        try {
            for (RegionLoadMonitor.RegionLoadSnapshot snap : RegionLoadMonitor.getAllSnapshots()) {
                maxLoad = Math.max(maxLoad, snap.loadFactor());
            }
        } catch (Throwable ignored) {
            // RegionLoadMonitor 未启用（测试环境）：中性负载
        }
        final long ageNanos = Math.max(0, System.nanoTime() - file.getLastSynced());
        return PersistencePriority.evaluate(
                regionId,
                RegionLoadSnapshot.fromLoadFactor(maxLoad),
                new DirtyAgeSnapshot(0, ageNanos, 0),
                null,
                false,
                PersistencePriority.Weights.DEFAULTS
        );
    }

    // ================= 装配（仅 MiliRuntime 调用） =================

    /** 接线 PolicyController（hint 裁决通道） */
    public void wirePolicy(PolicyController policy) {
        this.policyController = policy;
    }

    /** 晚绑定 flusher（RuntimeBootstrap 装配期） */
    public void attachFlusher(fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher flusher) {
        bridge.attachFlusher(flusher);
    }

    /** 桥引用（仅 RuntimeBootstrap 内部使用） */
    public OLinearFlusherBridge bridge() {
        return bridge;
    }

    /** §4.6 闭环自动扩缩开关（默认关闭；Phase 5 装配开启） */
    public void setAutoScalingEnabled(boolean on) {
        this.autoScalingEnabled = on;
    }

    public boolean isAutoScalingEnabled() {
        return autoScalingEnabled;
    }

    /** 故障注入缝隙（circuit breaker 测试用；仅测试调用） */
    public void setWorkerCountApplierForTest(IntConsumer applier) {
        this.workerCountApplier = applier != null ? applier : n -> bridge.setWorkerCount(n);
    }

    /** 故障注入缝隙（circuit breaker 测试用；仅测试调用） */
    public void setFlushPriorityApplierForTest(Consumer<Long> applier) {
        this.flushPriorityApplier = applier;
    }
}
