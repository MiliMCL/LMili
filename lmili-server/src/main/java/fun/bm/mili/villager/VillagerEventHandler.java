package fun.bm.mili.villager;

import fun.bm.mili.config.modules.optimizations.VillagerOptimizerConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.MerchantInventory;

/**
 * 村民优化事件处理器 —— 监听 Bukkit 事件并委托给 AI 控制器。
 */
public final class VillagerEventHandler implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final VillagerAIController aiController;

    public VillagerEventHandler(VillagerAIController aiController) {
        this.aiController = aiController;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                aiController.addVillager(villager);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                aiController.removeVillager(villager);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        aiController.onChunkChanged(event.getBlock().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        aiController.onChunkChanged(event.getBlock().getChunk());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) return;
        if (!(merchantInventory.getMerchant() instanceof Villager villager)) return;
        if (!aiController.getActiveVillagers().contains(villager)) return;

        Player player = (Player) event.getPlayer();
        if (VillagerOptimizerConfig.preventTradingUnlobotomized) {
            event.setCancelled(true);
            player.sendMessage(MINI_MESSAGE.deserialize("<red>你不能与未优化的村民交易！</red> <yellow>此村民需要先被优化。</yellow>"));
        } else {
            player.sendMessage(MINI_MESSAGE.deserialize(
                    "<white>[<gold>村民优化</gold>] </white><green>这个村民未被优化，如果用于交易，建议将其困住以优化性能~</green>"
            ));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!VillagerOptimizerConfig.preventTradingUnlobotomized) return;
        if (!(event.getInventory() instanceof MerchantInventory)) return;
        if (!(event.getInventory().getHolder() instanceof Villager villager)) return;
        if (!aiController.getActiveVillagers().contains(villager)) return;

        if (event.getRawSlot() == 2) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(MINI_MESSAGE.deserialize("<red>未优化的村民无法完成交易！</red>"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!VillagerOptimizerConfig.preventTradingUnlobotomized) return;
        if (!(event.getInventory() instanceof MerchantInventory)) return;
        if (!(event.getInventory().getHolder() instanceof Villager villager)) return;
        if (!aiController.getActiveVillagers().contains(villager)) return;

        if (event.getRawSlots().contains(2)) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(MINI_MESSAGE.deserialize("<red>未优化的村民无法完成交易！</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();

        if (!player.isSneaking()) {
            sendVillagerStatus(villager, player);
        }
    }

    private void sendVillagerStatus(Villager villager, Player player) {
        var pdc = villager.getPersistentDataContainer();
        boolean isWake = pdc.has(aiController.getWakeByCommandKey(), org.bukkit.persistence.PersistentDataType.BYTE);
        boolean isForce = pdc.has(aiController.getForceLobotomizedKey(), org.bukkit.persistence.PersistentDataType.BYTE);

        String statusText;
        if (isWake) {
            statusText = "<green>村民已被唤醒，拥有AI，可以刷新职业。</green><red>再次执行/lobotomy toggle可重新优化</red>";
        } else if (isForce) {
            statusText = "<green>村民已被优化，失去AI，交易仍然可用。</green><red>再次执行/lobotomy toggle可解除优化</red>";
        } else {
            boolean isAware = villager.isAware();
            if (isAware) {
                statusText = "<green>村民未优化，拥有AI，可用于繁殖/刷铁/农业。</green><red>用于交易建议将其困住，节省性能</red>";
            } else {
                statusText = "<red>村民已优化，失去AI，但可以交易。</red><green>用于繁殖/刷铁/农业，建议命名为命/1。</green>";
            }
        }
        player.sendActionBar(MINI_MESSAGE.deserialize(statusText));
    }
}
