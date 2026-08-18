package fun.bm.mili.lmili.functions.bars;

import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.ConcurrentHashMap;
import fun.bm.mili.lmili.utils.NullPlugin;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractGlobalServerBar {
    protected static final Logger logger = LogUtils.getLogger();
    protected static final NullPlugin NULL_PLUGIN = new NullPlugin();

    protected final Map<UUID, BossBar> uuid2Bossbars = Maps.newConcurrentMap();
    // Mili start - fix: use ConcurrentHashMap instead of non-thread-safe Object2ObjectLinkedOpenHashMap
    // The scheduler callback (update/cleanUp) runs on the global region thread, and cancelBarUpdateTask
    // can be called from any thread. ConcurrentHashMap is required for thread safety.
    protected final Map<UUID, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();
    // Mili end

    protected ScheduledTask scannerTask = null;
    protected boolean disabled = true;

    public abstract void init();

    public void init(int updateInterval) {
        synchronized (this) {
            disabled = false;
            cancelBarUpdateTask();

            this.scannerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(NULL_PLUGIN, unused -> {
                try {
                    update();
                    cleanUp();
                } catch (Exception e) {
                    logger.error(e.getLocalizedMessage());
                }
            }, 1, updateInterval);
        }
    }

    public void cancelBarUpdateTask() {
        synchronized (this) {
            if (this.scannerTask == null || this.scannerTask.isCancelled()) {
                return;
            }

            // we need wait until the task is really cancelled so that we are safe to modify scheduledTasks map
            ScheduledTask.CancelledState cancelledState;
            do {
                cancelledState = this.scannerTask.cancel();
            } while (cancelledState != ScheduledTask.CancelledState.CANCELLED_ALREADY && cancelledState != ScheduledTask.CancelledState.CANCELLED_BY_CALLER);

            for (ScheduledTask task : this.scheduledTasks.values()) {
                if (!task.isCancelled()) {
                    task.cancel();
                }
            }
        }
    }

    public void runUnloadTask() {
        this.disabled = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkAndRemove(player);
        }
    }

    public boolean checkAndRemove(Player player) {
        return checkAndRemove(player, player.getUniqueId());
    }

    public boolean checkAndRemove(Player player, UUID uuid) {
        if (!isPlayerVisible(player)) {
            final BossBar removed = uuid2Bossbars.remove(uuid);

            if (removed != null) {
                player.hideBossBar(removed);
            }
            return true;
        }
        return false;
    }

    public boolean isPlayerVisible(Player player) {
        return !disabled;
    }

    public abstract void setVisibilityForPlayer(Player target, boolean canSee);

    public abstract boolean enabled();

    private void update() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduledTasks.computeIfAbsent(player.getUniqueId(), unused -> createBossBarForPlayer(player));
        }
    }

    private void cleanUp() {
        final List<UUID> toCleanUp = new ArrayList<>();

        for (Map.Entry<UUID, ScheduledTask> toCheck : scheduledTasks.entrySet()) {
            if (toCheck.getValue().isCancelled()) {
                toCleanUp.add(toCheck.getKey());
            }
        }

        for (UUID uuid : toCleanUp) {
            scheduledTasks.remove(uuid);
        }
    }

    public abstract ScheduledTask createBossBarForPlayer(@NotNull Player apiPlayer);
}