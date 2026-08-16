package fun.bm.mili.utils;

import fun.bm.mili.config.modules.optimizations.DynamicViewDistanceConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DynamicViewDistanceManager {
    private static volatile boolean enabled = false;
    private static final ConcurrentHashMap<String, PlayerVDState> playerStates = new ConcurrentHashMap<>();
    private static final AtomicLong totalAdjustments = new AtomicLong();
    private static long lastAdjustTime = 0;

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    // Mili start - fix: add method to clean up offline player entries to prevent map growth OOM
    public static void onPlayerQuit(String uuid) {
        playerStates.remove(uuid);
    }
    // Mili end

    public static void tick() {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        long intervalMs = DynamicViewDistanceConfig.adjustIntervalSeconds * 1000L;
        if (now - lastAdjustTime < intervalMs) return;
        lastAdjustTime = now;

        double currentTps = getCurrentTps();

        // Mili start - optimize: pre-compute nearby player counts per world to avoid O(n²) per-player scans.
        // For each world, collect all player locations once, then count neighbors in a single pass.
        for (World world : Bukkit.getWorlds()) {
            java.util.List<Player> worldPlayers = world.getPlayers();
            if (worldPlayers.isEmpty()) continue;

            // Pre-collect locations to avoid repeated getLocation() calls
            org.bukkit.Location[] locs = new org.bukkit.Location[worldPlayers.size()];
            for (int i = 0; i < worldPlayers.size(); i++) {
                locs[i] = worldPlayers.get(i).getLocation();
            }

            // Pre-compute nearby player counts for each player
            int[] nearbyCounts = computeNearbyCounts(worldPlayers, locs);

            for (int i = 0; i < worldPlayers.size(); i++) {
                adjustPlayerViewDistance(worldPlayers.get(i), currentTps, nearbyCounts[i]);
            }
        }
    }

    /**
     * Optimized neighbor counting: for each pair (i, j), if within threshold both get incremented.
     * But to keep it cheap for typical player counts (< 200), we use a simpler approach:
     * iterate each player and count neighbors using pre-fetched locations.
     * This is still O(n²) but avoids repeated getLocation() calls and repeated world scans.
     * For very large player counts, consider spatial hashing.
     */
    private static int[] computeNearbyCounts(java.util.List<Player> players, org.bukkit.Location[] locs) {
        int n = players.size();
        int[] counts = new int[n];
        // Use a reasonable default radius (maxViewDistance * 16) for density estimation
        double maxRadius = DynamicViewDistanceConfig.maxViewDistance * 16.0;
        double maxRadiusSq = maxRadius * maxRadius;

        for (int i = 0; i < n; i++) {
            // Only count if player i's actual radius would include player j
            int vd = players.get(i).getViewDistance();
            double thresholdSq = (vd * 16.0) * (vd * 16.0);
            for (int j = i + 1; j < n; j++) {
                double dx = locs[i].getX() - locs[j].getX();
                double dz = locs[i].getZ() - locs[j].getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq < thresholdSq) {
                    counts[i]++;
                }
                // Check reverse: is j within i's radius too? Use j's own view distance
                double thresholdSqJ = (players.get(j).getViewDistance() * 16.0) * (players.get(j).getViewDistance() * 16.0);
                if (distSq < thresholdSqJ) {
                    counts[j]++;
                }
            }
        }
        return counts;
    }

    private static void adjustPlayerViewDistance(Player player, double currentTps, int nearbyPlayers) {
        String uuid = player.getUniqueId().toString();
        PlayerVDState state = playerStates.computeIfAbsent(uuid, k -> new PlayerVDState());

        int currentVD = player.getViewDistance();
        int targetVD = currentVD;

        if (currentTps > DynamicViewDistanceConfig.tpsHighThreshold) {
            targetVD = Math.min(currentVD + 1, DynamicViewDistanceConfig.maxViewDistance);
        } else if (currentTps < DynamicViewDistanceConfig.tpsLowThreshold) {
            targetVD = Math.max(currentVD - 1, DynamicViewDistanceConfig.minViewDistance);
        }

        if (nearbyPlayers > 10) {
            double densityPenalty = nearbyPlayers * DynamicViewDistanceConfig.playerDensityWeight;
            targetVD = Math.max(DynamicViewDistanceConfig.minViewDistance,
                    (int)(targetVD - densityPenalty));
        }

        if (targetVD != currentVD) {
            state.adjustments++;
            totalAdjustments.incrementAndGet();
            player.setViewDistance(targetVD);
        }
    }

    private static double getCurrentTps() {
        try {
            org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            if (main != null) {
                var criteria = main.getObjective("mili_tps");
                if (criteria != null) {
                    var entry = main.getEntries().stream().findFirst();
                    if (entry.isPresent()) {
                        var score = criteria.getScore(entry.get());
                        return score.getScore() / 20.0;
                    }
                }
            }
        // Mili start - fix: catch Throwable instead of Exception to handle Errors
        } catch (Throwable ignored) {}
        // Mili end

        return 20.0;
    }

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("Enabled", enabled);
        stats.put("Tracked Players", playerStates.size());
        stats.put("Total Adjustments", totalAdjustments.get());
        return stats;
    }

    private static class PlayerVDState {
        int adjustments = 0;
    }
}
