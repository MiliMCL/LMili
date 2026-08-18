package fun.bm.mili.villager;

import fun.bm.mili.config.modules.optimizations.VillagerOptimizerConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

/**
 * 村民优化器 —— 协调 AI 控制、事件处理、补货逻辑。
 *
 * <p>内部委托给：
 * <ul>
 *   <li>{@link VillagerAIController} —— AI 状态控制</li>
 *   <li>{@link VillagerEventHandler} —— Bukkit 事件监听</li>
 *   <li>{@link VillagerRestockProcessor} —— 补货逻辑</li>
 * </ul>
 */
public final class VillagerOptimizer implements Listener {

    private static volatile VillagerOptimizer instance;
    private static org.bukkit.scheduler.BukkitTask processTask;

    private final VillagerAIController aiController;
    private final VillagerEventHandler eventHandler;
    private final VillagerRestockProcessor restockProcessor;

    private final NamespacedKey lobotomizedKey;
    private final NamespacedKey wakeByCommandKey;
    private final NamespacedKey forceLobotomizedKey;
    private final NamespacedKey lastRestockCheckDayTimeKey;
    private final NamespacedKey restocksTodayKey;

    private VillagerOptimizer(Plugin plugin) {
        Plugin activePlugin = plugin;
        this.lobotomizedKey = new NamespacedKey(activePlugin, "mili_lobotomized");
        this.wakeByCommandKey = new NamespacedKey(activePlugin, "mili_wake");
        this.forceLobotomizedKey = new NamespacedKey(activePlugin, "mili_force_lobotomy");
        this.lastRestockCheckDayTimeKey = new NamespacedKey(activePlugin, "mili_last_restock");
        this.restocksTodayKey = new NamespacedKey(activePlugin, "mili_restocks_today");

        this.aiController = new VillagerAIController(plugin, lobotomizedKey, wakeByCommandKey, forceLobotomizedKey);
        this.eventHandler = new VillagerEventHandler(aiController);
        this.restockProcessor = new VillagerRestockProcessor(plugin, lastRestockCheckDayTimeKey, restocksTodayKey);
    }

    public static synchronized void init(Plugin plugin) {
        if (!VillagerOptimizerConfig.enabled || instance != null) return;
        instance = new VillagerOptimizer(plugin);
        Plugin activePlugin = plugin;
        Bukkit.getPluginManager().registerEvents(instance.eventHandler, activePlugin);

        // Scan existing villagers
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Villager villager) {
                    instance.aiController.addVillager(villager);
                }
            }
        }

        // Start chunk processing task
        processTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (instance != null) {
                    instance.aiController.processChunks();
                    // Process restock for inactive villagers
                    for (Villager villager : instance.aiController.getInactiveVillagers()) {
                        if (villager.isValid() && !villager.isDead()) {
                            instance.restockProcessor.tryRestock(villager);
                        }
                    }
                }
            }
        }.runTaskTimer(activePlugin, 5L, 5L);

        activePlugin.getLogger().info("[Mili] VillagerOptimizer initialized");
    }

    public static VillagerOptimizer getInstance() {
        return instance;
    }

    public static void shutdown() {
        if (instance != null) {
            if (processTask != null) {
                processTask.cancel();
                processTask = null;
            }
            instance.aiController.shutdown();
            instance = null;
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public Set<Villager> getActiveVillagers() {
        return aiController.getActiveVillagers();
    }

    public Set<Villager> getInactiveVillagers() {
        return aiController.getInactiveVillagers();
    }

    public void toggleLobotomy(Villager villager) {
        var pdc = villager.getPersistentDataContainer();
        if (aiController.getInactiveVillagers().contains(villager)) {
            // Activate
            pdc.remove(lobotomizedKey);
            pdc.remove(forceLobotomizedKey);
            pdc.set(wakeByCommandKey, PersistentDataType.BYTE, (byte) 1);
            aiController.activate(villager);
        } else {
            // Lobotomize
            pdc.remove(wakeByCommandKey);
            pdc.set(forceLobotomizedKey, PersistentDataType.BYTE, (byte) 1);
            aiController.lobotomize(villager);
        }
    }
}
