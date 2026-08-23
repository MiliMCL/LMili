package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * 玩家离线 listener —— 在玩家下线时清理 {@link PlayerChunkPreloader} 和
 * {@link CrossRegionChunkPreloader} 的 per-player 缓存，避免内存泄漏。
 *
 * <p><b>为什么需要</b>：两个 preloader 用 {@code ConcurrentHashMap<playerEntityId, ...>}
 * 缓存"上次预加载的 chunk 位置"以去重。如果玩家下线不清理，缓存条目永久残留
 * （每个玩家 ~32 bytes，1 万玩家约 320 KB —— 不大但累积，且 JVM hashmap 链表式退化风险）。
 *
 * <p><b>为什么用 Bukkit listener</b>：Folia 下玩家下线事件 {@link PlayerQuitEvent} 在
 * 该玩家的 region tick 线程上同步触发（Folia 官方保证）—— 我们只需要从 event 拿到
 * {@link net.minecraft.server.level.ServerPlayer#getId()}（通过 {@code CraftPlayer.getHandle()}），
 * 然后清 map，无需切线程。
 *
 * <p><b>性能</b>：每个玩家下线触发一次，{@code map.remove(key)} O(1)，零分配。
 */
public final class PlayerChunkPreloadListener implements Listener {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Plugin plugin;

    /**
     * 注册 listener 到 Bukkit plugin manager。
     * 必须在 server 启动后调用（{@code onLoaded} 中）。
     */
    public void register(final Plugin miliPlugin) {
        if (miliPlugin == null) {
            LOGGER.warn("[PlayerChunkPreloadListener] Mili plugin instance is null, listener not registered");
            return;
        }
        if (!miliPlugin.isEnabled()) {
            LOGGER.warn("[PlayerChunkPreloadListener] Mili plugin not enabled, listener not registered");
            return;
        }
        if (this.plugin != null) {
            return; // 已注册
        }
        this.plugin = miliPlugin;
        Bukkit.getPluginManager().registerEvents(this, miliPlugin);
    }

    /**
     * 注销 listener（config 卸载时调用）。
     */
    public void unregister() {
        HandlerList.unregisterAll(this);
        this.plugin = null;
    }

    /**
     * 检查 listener 是否已注册。
     */
    public boolean isRegistered() {
        return this.plugin != null;
    }

    /**
     * 处理玩家下线 —— 清理两个 preloader 的 per-player 缓存。
     *
     * <p>{@link EventPriority#MONITOR} 优先级：不影响其他 plugin 的 PlayerQuit 处理，
     * 只在最后清理自己的缓存。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        if (!(player instanceof CraftPlayer craft)) {
            return;
        }
        final int entityId = craft.getHandle().getId();
        // 委托给 PlayerChunkPreloader.clearCacheForPlayer（它内部已转调 CrossRegionChunkPreloader.clearCacheForPlayer）
        PlayerChunkPreloader.clearCacheForPlayer(entityId);
    }
}