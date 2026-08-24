package fun.bm.mili.lmili.observability;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.observability.PluginSchedulerCapture;
import fun.bm.mili.lmili.command.identity.PluginIdentityAutoDiscovery;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMili 自动 scheduler 捕获 —— 解决「spark 等外部 plugin 的任务 LMili 看不到」问题。
 *
 * <h3>策略</h3>
 * <p>LMili 监听 Bukkit {@link PluginEnableEvent}：当一个 plugin 被启用时，LMili 自动
 * 尝试获取它的 {@code BukkitScheduler} 路径（Bukkit 是接口；具体实现是
 * {@code CraftScheduler}）。LMili 通过 Paper 暴露的 {@code ServerScheduler} API
 * 拿"per-plugin 提交的任务数"作为<b>间接指标</b>。
 *
 * <p><b>不</b>hook CraftScheduler 内部（§18.9 "不重构已经稳定工作的模块"）。
 * 改用<b>轻量采样</b>：每 5 秒一次记录 plugin 的活跃 task 数差值，作为
 * "captured submits / completes"。
 *
 * <p>这个实现解决了三个层次的可见性：
 * <ol>
 *   <li>plugin 存在性：Bukkit PluginManager → {@link PluginIdentityAutoDiscovery}</li>
 *   <li>plugin 实际 enabled/disabled：{@link PluginEnableEvent} / {@link PluginDisableEvent}</li>
 *   <li>plugin 提交了多少 task：用 Bukkit Scheduler 的全局 pending 数 + plugin 是否 enabled 启发式判断</li>
 * </ol>
 *
 * <h3>使用</h3>
 * <pre>{@code
 * Bukkit.getPluginManager().registerEvents(new AutoSchedulerCapture(), plugin);
 * }</pre>
 *
 * <p>应该注册成<b>MONITOR</b> 优先级，避免影响其它 listener。
 */
public final class AutoSchedulerCapture implements Listener, Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final PluginSchedulerCapture capture;
    private final ConcurrentMap<String, Long> lastPendingByName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> enabledPlugins = new ConcurrentHashMap<>();
    private final AtomicLong sampleTicks = new AtomicLong(0);
    private volatile int taskId = -1;

    public AutoSchedulerCapture() {
        this(null);
    }

    public AutoSchedulerCapture(PluginSchedulerCapture capture) {
        this.capture = capture != null
                ? capture
                : (LMili.schedulerCapture() != null ? LMili.schedulerCapture() : new PluginSchedulerCaptureImpl());
    }

    /** 注册到 Bukkit（启动后调用一次） */
    public void registerSelf(org.bukkit.plugin.Plugin owner) {
        try {
            Bukkit.getPluginManager().registerEvents(this, owner);
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(owner, this, 100L, 100L);
            LOGGER.info("[AutoSchedulerCapture] listening (sampling every 5s)");
        } catch (Throwable t) {
            LOGGER.warn("[AutoSchedulerCapture] register failed", t);
        }
    }

    public void unregister() {
        try {
            if (taskId >= 0) Bukkit.getScheduler().cancelTask(taskId);
        } catch (Throwable ignored) {}
        try {
            PluginEnableEvent.getHandlerList().unregister(this);
            PluginDisableEvent.getHandlerList().unregister(this);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin p = event.getPlugin();
        if (p == null) return;
        enabledPlugins.put(p.getName(), Boolean.TRUE);
        // 通过 AutoDiscovery 让 LMili 认识该 plugin（如果尚未注册）
        try {
            PluginIdentityAutoDiscovery.resolveOrDiscover(p.getName());
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin p = event.getPlugin();
        if (p == null) return;
        enabledPlugins.put(p.getName(), Boolean.FALSE);
    }

    @Override
    public void run() {
        long tick = sampleTicks.incrementAndGet();
        if (tick % 5 != 0) return; // 5 秒一次（5 tick ≈ 5*50ms）
        try {
            samplePending();
        } catch (Throwable t) {
            LOGGER.debug("[AutoSchedulerCapture] sample failed", t);
        }
    }

    private void samplePending() {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!p.isEnabled()) continue;
            String name = p.getName();
            // 通过 PluginSchedulerBridge.pendingTaskCount(PluginId) 拿到 LMili 任务数
            PluginId pid = PluginIdentityAutoDiscovery.resolveOrDiscover(name);
            if (pid == null) continue;
            long lmiliPending = fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge.pendingTaskCount(pid);

            // 启发式：通过 plugin 名字在 BukkitScheduler.getPendingTasks() 中匹配
            // Bukkit 1.13+ 提供 getPendingTasks() 返回 List<BukkitTask>，task.owner 字段是 plugin 名
            long bukkitOwnedPending = 0;
            try {
                for (org.bukkit.scheduler.BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
                    if (task.getOwner() != null && name.equals(task.getOwner().getName())) {
                        bukkitOwnedPending++;
                    }
                }
            } catch (Throwable ignored) {
                // 旧 Bukkit API 没有 getPendingTasks()；fallback = 0
            }

            // 只在 plugin 走 BukkitScheduler 时上报 capture
            if (bukkitOwnedPending > 0) {
                long delta = bukkitOwnedPending - lastPendingByName.getOrDefault(name, 0L);
                lastPendingByName.put(name, bukkitOwnedPending);
                if (delta > 0) {
                    capture.recordSubmit(pid);
                    LOGGER.debug("[AutoSchedulerCapture] {} captured {} new submits (total pending={})",
                            name, delta, bukkitOwnedPending);
                }
            } else {
                lastPendingByName.remove(name);
            }
        }
    }
}
