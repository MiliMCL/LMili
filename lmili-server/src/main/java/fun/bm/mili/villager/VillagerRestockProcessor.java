package fun.bm.mili.villager;

import fun.bm.mili.config.modules.optimizations.VillagerOptimizerConfig;
import org.bukkit.Sound;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 村民补货处理器 —— 管理村民交易补货逻辑。
 */
public final class VillagerRestockProcessor {

    private final NamespacedKey lastRestockCheckDayTimeKey;
    private final NamespacedKey restocksTodayKey;

    public VillagerRestockProcessor(Plugin plugin,
                                     NamespacedKey lastRestockCheckDayTimeKey,
                                     NamespacedKey restocksTodayKey) {
        this.lastRestockCheckDayTimeKey = lastRestockCheckDayTimeKey;
        this.restocksTodayKey = restocksTodayKey;
    }

    /**
     * 处理补货逻辑。
     */
    public void tryRestock(Villager villager) {
        if (!needsToRestock(villager)) return;
        if (!allowedToRestock(villager)) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestockCheck = pdc.getOrDefault(lastRestockCheckDayTimeKey, PersistentDataType.LONG, 0L);
        long fullTime = villager.getWorld().getFullTime();

        // Check for new day
        if (lastRestockCheck > 0L) {
            long lastDay = lastRestockCheck / 24000L;
            long currentDay = fullTime / 24000L;
            if (currentDay > lastDay) {
                pdc.set(restocksTodayKey, PersistentDataType.INTEGER, 0);
            }
        }

        if (!allowedToRestock(villager)) return;

        // Random restock check using game time consistently
        long interval = VillagerOptimizerConfig.restockInterval;
        long randomRange = VillagerOptimizerConfig.restockRandomRange;
        long elapsedGameTime = fullTime - lastRestockCheck;

        if (randomRange > 0 && randomRange < interval) {
            long adjustedInterval = interval - (long) (Math.random() * randomRange);
            if (elapsedGameTime >= adjustedInterval) {
                doRestock(villager, fullTime);
            }
        } else if (elapsedGameTime >= interval) {
            doRestock(villager, fullTime);
        }
    }

    private void doRestock(Villager villager, long fullTime) {
        villager.restock();
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(lastRestockCheckDayTimeKey, PersistentDataType.LONG, fullTime);
        int restocksToday = pdc.getOrDefault(restocksTodayKey, PersistentDataType.INTEGER, 0);
        pdc.set(restocksTodayKey, PersistentDataType.INTEGER, restocksToday + 1);

        // Play sound
        Sound sound = getProfessionSound(villager.getProfession());
        if (sound != null) {
            villager.getWorld().playSound(villager.getLocation(), sound, org.bukkit.SoundCategory.NEUTRAL, 1.0f, 1.0f);
        }
    }

    private boolean needsToRestock(Villager villager) {
        for (Villager.Recipe recipe : villager.getRecipes()) {
            if (recipe.getUses() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean allowedToRestock(Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        int restocksToday = pdc.getOrDefault(restocksTodayKey, PersistentDataType.INTEGER, 0);
        return restocksToday < 2;
    }

    private Sound getProfessionSound(Villager.Profession profession) {
        if (profession == null) return null;
        String name = profession.name();
        return switch (name) {
            case "ARMORER" -> Sound.ENTITY_VILLAGER_WORK_ARMORER;
            case "BUTCHER" -> Sound.ENTITY_VILLAGER_WORK_BUTCHER;
            case "CARTOGRAPHER" -> Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER;
            case "CLERIC" -> Sound.ENTITY_VILLAGER_WORK_CLERIC;
            case "FARMER" -> Sound.ENTITY_VILLAGER_WORK_FARMER;
            case "FISHERMAN" -> Sound.ENTITY_VILLAGER_WORK_FISHERMAN;
            case "FLETCHER" -> Sound.ENTITY_VILLAGER_WORK_FLETCHER;
            case "LEATHERWORKER" -> Sound.ENTITY_VILLAGER_WORK_LEATHERWORKER;
            case "LIBRARIAN" -> Sound.ENTITY_VILLAGER_WORK_LIBRARIAN;
            case "MASON" -> Sound.ENTITY_VILLAGER_WORK_MASON;
            case "SHEPHERD" -> Sound.ENTITY_VILLAGER_WORK_SHEPHERD;
            case "TOOLSMITH" -> Sound.ENTITY_VILLAGER_WORK_TOOLSMITH;
            case "WEAPONSMITH" -> Sound.ENTITY_VILLAGER_WORK_WEAPONSMITH;
            default -> null;
        };
    }
}
