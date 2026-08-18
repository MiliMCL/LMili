package fun.bm.mili.villager;

import fun.bm.mili.config.modules.optimizations.VillagerOptimizerConfig;
import fun.bm.mili.utils.performance.TPSTracker;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民 AI 控制器 —— 管理村民 AI 状态和活跃度判定。
 */
public final class VillagerAIController {

    private final NamespacedKey lobotomizedKey;
    private final NamespacedKey wakeByCommandKey;
    private final NamespacedKey forceLobotomizedKey;

    private final Set<Villager> activeVillagers = ConcurrentHashMap.newKeySet();
    private final Set<Villager> inactiveVillagers = ConcurrentHashMap.newKeySet();
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();
    private final VillagerActivityPolicy activityPolicy;
    private final BlockClassifier blockClassifier;

    private volatile boolean shuttingDown = false;
    private long lastProcessTime = 0;

    public VillagerAIController(org.bukkit.plugin.Plugin plugin,
                                 NamespacedKey lobotomizedKey,
                                 NamespacedKey wakeByCommandKey,
                                 NamespacedKey forceLobotomizedKey) {
        this.lobotomizedKey = lobotomizedKey;
        this.wakeByCommandKey = wakeByCommandKey;
        this.forceLobotomizedKey = forceLobotomizedKey;
        this.blockClassifier = BlockClassifier.fromServerRegistry();

        Set<String> exemptNames = new HashSet<>();
        for (String name : VillagerOptimizerConfig.alwaysActiveNames) {
            exemptNames.add(name.toLowerCase());
        }

        this.activityPolicy = new VillagerActivityPolicy(
                VillagerOptimizerConfig.lobotomizePassengers,
                VillagerOptimizerConfig.onlyProfessions,
                VillagerOptimizerConfig.onlyWithExperience,
                VillagerOptimizerConfig.checkRoof,
                VillagerOptimizerConfig.ignoreStuckInDoors,
                VillagerOptimizerConfig.ignoreNonSolidBlocks,
                exemptNames,
                blockClassifier
        );
    }

    public void addVillager(Villager villager) {
        if (shuttingDown) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        boolean isForce = pdc.has(forceLobotomizedKey, PersistentDataType.BYTE);
        boolean isWake = pdc.has(wakeByCommandKey, PersistentDataType.BYTE);

        if (isForce) {
            lobotomize(villager);
        } else if (isWake) {
            activate(villager);
        } else {
            boolean wasLobotomized = VillagerOptimizerConfig.persistState &&
                    pdc.has(lobotomizedKey, PersistentDataType.BYTE);
            if (wasLobotomized) {
                lobotomize(villager);
            } else {
                activate(villager);
            }
        }
    }

    public void removeVillager(Villager villager) {
        activeVillagers.remove(villager);
        inactiveVillagers.remove(villager);
    }

    public void activate(Villager villager) {
        villager.setAware(true);
        villager.removePotionEffect(PotionEffectType.WATER_BREATHING);
        if (VillagerOptimizerConfig.silentLobotomized) {
            villager.setSilent(false);
        }
        activeVillagers.add(villager);
        inactiveVillagers.remove(villager);
    }

    public void lobotomize(Villager villager) {
        villager.setAware(false);
        villager.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, Integer.MAX_VALUE, 0, false, false, false));
        if (VillagerOptimizerConfig.silentLobotomized) {
            villager.setSilent(true);
        }
        activeVillagers.remove(villager);
        inactiveVillagers.add(villager);

        if (VillagerOptimizerConfig.persistState) {
            villager.getPersistentDataContainer().set(lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    public void onChunkChanged(Chunk chunk) {
        changedChunks.put(chunk, System.currentTimeMillis());
    }

    public boolean shouldBeActive(Villager villager) {
        String name = "";
        if (villager.customName() != null) {
            name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(villager.customName()).toLowerCase();
        }

        org.bukkit.Location loc = villager.getLocation();
        VillagerState state = new VillagerState(
                name,
                villager.isSwimming(),
                villager.isSleeping(),
                villager.getVehicle() != null,
                villager.getProfession() == Villager.Profession.NONE,
                villager.getVillagerExperience(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );

        BlockGrid grid = new BlockGrid(villager.getWorld(), state.blockX(), state.blockY(), state.blockZ(), 1);
        return activityPolicy.shouldBeActive(state, grid);
    }

    public void processChunks() {
        if (shuttingDown) return;

        // TPS-based interval scaling
        long checkInterval = VillagerOptimizerConfig.checkInterval;
        if (VillagerOptimizerConfig.tpsScaleEnabled && TPSTracker.getTPS() < VillagerOptimizerConfig.tpsScaleThreshold) {
            checkInterval = (long) (checkInterval * VillagerOptimizerConfig.tpsScaleFactor);
        }

        long now = System.currentTimeMillis();
        if (now - lastProcessTime < checkInterval * 50) {
            if (changedChunks.size() > 1000) {
                changedChunks.clear();
            }
            return;
        }
        lastProcessTime = now;

        // Process changed chunks
        Iterator<Map.Entry<Chunk, Long>> it = changedChunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Chunk, Long> entry = it.next();
            Chunk chunk = entry.getKey();
            if (!chunk.isLoaded()) {
                it.remove();
                continue;
            }

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Villager villager) {
                    if (inactiveVillagers.contains(villager)) {
                        if (shouldBeActive(villager)) {
                            activate(villager);
                        }
                    }
                }
            }
            it.remove();
        }

        // Process active villagers
        for (Villager villager : activeVillagers) {
            if (!villager.isValid() || villager.isDead()) {
                removeVillager(villager);
                continue;
            }
            if (!shouldBeActive(villager)) {
                lobotomize(villager);
            }
        }
    }

    public void shutdown() {
        shuttingDown = true;
        activeVillagers.clear();
        inactiveVillagers.clear();
        changedChunks.clear();
    }

    public Set<Villager> getActiveVillagers() {
        return Collections.unmodifiableSet(activeVillagers);
    }

    public Set<Villager> getInactiveVillagers() {
        return Collections.unmodifiableSet(inactiveVillagers);
    }

    public Set<Villager> getAllTrackedVillagers() {
        Set<Villager> all = new HashSet<>(activeVillagers);
        all.addAll(inactiveVillagers);
        return all;
    }

    public NamespacedKey getLobotomizedKey() {
        return lobotomizedKey;
    }

    public NamespacedKey getWakeByCommandKey() {
        return wakeByCommandKey;
    }

    public NamespacedKey getForceLobotomizedKey() {
        return forceLobotomizedKey;
    }
}
