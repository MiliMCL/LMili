package fun.bm.mili.lmili.observability;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.ResourceQuota;
import fun.bm.mili.lmili.api.observability.PluginSchedulerCapture;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PluginSchedulerCapture} 的 server-side 实现 —— 把外部 plugin 报告的数字
 * 写回 {@link ResourceQuota} 与 {@link fun.bm.mili.lmili.api.identity.ObservabilityContext}。
 *
 * <p>为什么需要它（§11 P2-2 / 用户痛点）：
 * <ul>
 *   <li>spark 等外部 plugin 通过原生 {@code BukkitScheduler} 跑任务，LMili 调度路径完全看不到。</li>
 *   <li>{@code /pluginid status spark} 显示 submitted=0 / completed=0。</li>
 *   <li>本类允许 spark（或其他 plugin）<b>声明式</b>接入：调用
 *       {@link LMili#schedulerCapture()} 的 {@link PluginSchedulerCapture#recordSubmit}
 *       和 {@link PluginSchedulerCapture#recordComplete} 即可。</li>
 * </ul>
 */
public final class PluginSchedulerCaptureImpl implements PluginSchedulerCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentMap<PluginId, CaptureHook> hooks = new ConcurrentHashMap<>();
    private final ConcurrentMap<PluginId, LocalStats> localStats = new ConcurrentHashMap<>();

    public PluginSchedulerCaptureImpl() {
        // 自挂到 LMili
        LMili.installSchedulerCapture(this);
    }

    @Override
    public void registerCapture(@NotNull PluginId pluginId, @NotNull CaptureHook hook) {
        // 反注册旧 hook
        CaptureHook old = hooks.put(pluginId, hook);
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
        }
        localStats.computeIfAbsent(pluginId, k -> new LocalStats());
        LOGGER.info("[PluginSchedulerCapture] registered capture for {}", pluginId.value());
    }

    @Override
    public boolean unregisterCapture(@NotNull PluginId pluginId) {
        CaptureHook old = hooks.remove(pluginId);
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
            return true;
        }
        return false;
    }

    @Override
    public void recordSubmit(@NotNull PluginId pluginId) {
        PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pluginId);
        if (ctx == null) {
            // 仍未注册 → 仅本地计数（auto-discovery 可能稍后才发生）
            localStats.computeIfAbsent(pluginId, k -> new LocalStats()).submits.incrementAndGet();
            return;
        }
        ctx.observability().recordSchedulerRequest();
        ctx.resourceQuota().onTaskSubmit();
    }

    @Override
    public void recordComplete(@NotNull PluginId pluginId, long executionNanos, boolean success) {
        PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pluginId);
        if (ctx == null) {
            LocalStats s = localStats.computeIfAbsent(pluginId, k -> new LocalStats());
            s.completes.incrementAndGet();
            if (executionNanos > 0) s.execNanos.addAndGet(executionNanos);
            if (!success) s.failures.incrementAndGet();
            return;
        }
        ctx.resourceQuota().onTaskFinish(Math.max(0, executionNanos), success);
        if (!success) {
            ctx.observability().recordTaskFailed();
        }
    }

    @Override
    public @NotNull CaptureStats statsOf(@NotNull PluginId pluginId) {
        LocalStats s = localStats.get(pluginId);
        if (s == null) return new CaptureStats(0, 0, 0, 0);
        return new CaptureStats(
                s.submits.get(),
                s.completes.get(),
                s.execNanos.get(),
                s.failures.get());
    }

    private static final class LocalStats {
        final AtomicLong submits = new AtomicLong();
        final AtomicLong completes = new AtomicLong();
        final AtomicLong execNanos = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
    }
}
