package fun.bm.mili.lmili.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.runtime.io.FlusherObserver;
import fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge;
import fun.bm.mili.lmili.thread.scheduler.api.MiliSchedulerHolder;
import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
import org.slf4j.Logger;

/**
 * 装配期引导（RegionFormatConfig 回调入口，§5.6 / D-08）。
 *
 * <p><strong>延迟装配语义</strong>：RegionFormatConfig.onLoaded 构造 flusher 时
 * （可能早于调度器就绪）只做两件事：
 * <ol>
 *   <li>{@link #createFlusherObserver()} —— 返回延迟转发观察者（runtime 未就绪时 no-op）；</li>
 *   <li>{@link #onFlusherCreated(OptimizedLinearRegionFileFlusher)} —— flusher 创建后
 *       晚绑定（attachFlusher + scheduler.bind + Bukkit 权限检查器）。</li>
 * </ol>
 * 不在构造期直接 MiliRuntimeHolder.getOrCreate()（避免装配顺序竞态）。
 */
public final class RuntimeBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean bukkitPermissionCheckerInstalled = false;

    private RuntimeBootstrap() {
    }

    /** flusher 构造期回调（RegionFormatConfig.onLoaded 调用；幂等） */
    public static void onFlusherCreated(OptimizedLinearRegionFileFlusher flusher) {
        if (flusher == null) {
            return;
        }
        try {
            final MiliRuntime rt = MiliRuntimeHolder.getOrCreate();
            rt.io().attachFlusher(flusher);
            rt.bindScheduler(MiliSchedulerHolder.get());
            installBukkitPermissionChecker(rt);
            LOGGER.info("[RuntimeBootstrap] runtime attached to flusher ({} io threads)", flusher.getIoThreadCount());
        } catch (Throwable t) {
            // 装配失败不阻断主服务器启动（控制面降级为不可用，数据面不受影响）
            LOGGER.warn("[RuntimeBootstrap] runtime attach failed (control plane degraded)", t);
        }
    }

    /**
     * 构造 flusher 时传入的观察者（D-08）：延迟解析到 runtime 的桥。
     * runtime 未就绪时全部 no-op/fail-open（onDispatch 返回 true = 放行）。
     */
    public static FlusherObserver createFlusherObserver() {
        return new FlusherObserver() {
            @Override
            public void onCycleFinished(long cycleNanos) {
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onCycleFinished(cycleNanos);
                }
            }

            @Override
            public boolean onDispatch(OptimizedLinearRegionFile file) {
                final OLinearFlusherBridge b = bridgeOrNull();
                return b == null || b.onDispatch(file);
            }

            @Override
            public void onSyncCompleted(OptimizedLinearRegionFile file, long nanos, long bytes, boolean success) {
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onSyncCompleted(file, nanos, bytes, success);
                }
            }

            @Override
            public void onSyncDispatched() {
                // D-08 附加钩子：必须转发到桥的 activeSyncs 记账，否则 drainForShutdown
                // 永远看到 activeWorkers=0（有界排水失去意义）。
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onSyncDispatched();
                }
            }
        };
    }

    private static OLinearFlusherBridge bridgeOrNull() {
        final MiliRuntime rt = MiliRuntimeHolder.get();
        return rt != null ? rt.io().bridge() : null;
    }

    /**
     * Bukkit 权限检查器（命令路径二道闸）：actor 解析为在线玩家；
     * CONSOLE/RCON 视为已授权。Headless/未启动时一律拒绝（fail-closed）。
     */
    public static void installBukkitPermissionChecker(MiliRuntime rt) {
        if (rt == null || bukkitPermissionCheckerInstalled) {
            return;
        }
        bukkitPermissionCheckerInstalled = true;
        rt.setPermissionChecker((actor, permission) -> {
            try {
                if (actor == null || actor.isBlank()) {
                    return false;
                }
                if ("CONSOLE".equals(actor) || "RCON".equals(actor)) {
                    return true;
                }
                final org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(actor);
                return player != null && player.hasPermission(permission);
            } catch (Throwable t) {
                return false; // fail-closed
            }
        });
    }
}
