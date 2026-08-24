package fun.bm.mili.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.mili.chunk.MiliChunkSystem;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.utils.region.RegionBalancer;
import fun.bm.mili.utils.region.SmartRegionManager;
import fun.bm.mili.utils.performance.MemoryOptimizer;
import fun.bm.mili.utils.misc.CrossRegionHelper;
import fun.bm.mili.utils.entity.EntityDirtyTracker;
import fun.bm.mili.utils.region.DynamicViewDistanceManager;
import fun.bm.mili.utils.portal.CrossDimensionTeleportQueue;
import fun.bm.mili.utils.entity.AsyncPathfinder;
import fun.bm.mili.utils.chunk.ChunkDeltaCompressor;
import fun.bm.mili.utils.misc.LightCallbackManager;
import fun.bm.mili.utils.entity.EntityDensityTracker;
import fun.bm.mili.utils.network.NetworkOptimizer;
import fun.bm.mili.utils.player.PlayerHeatmap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.RootNode;

import java.util.Map;

/**
 * Operator command for the Mili performance monitor.
 *
 * <p>All user-visible strings are routed through {@link I18nManager}.</p>
 */
public class MiliPerfCommand extends RootNode {
    private static final String PERM_BASE = "mili.admin.perf";

    public MiliPerfCommand() {
        super("miperf", PERM_BASE);
    }

    @Override
    public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
        return source.getSender().hasPermission(PERM_BASE);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        CommandSender sender = context.getSender();
        sender.sendMessage(Component.text(I18nManager.get("miperf.title"), NamedTextColor.GOLD));
        sender.sendMessage(Component.empty());

        sendRegionStats(sender);
        sender.sendMessage(Component.empty());
        sendChunkStats(sender);
        sender.sendMessage(Component.empty());
        sendMemoryStats(sender);
        sender.sendMessage(Component.empty());
        sendCrossRegionStats(sender);
        sender.sendMessage(Component.empty());
        sendOptimizationStats(sender);
        sender.sendMessage(Component.empty());
        sendFeatureStats(sender);
        return true;
    }

    private static void sendRegionStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.region.system"), NamedTextColor.YELLOW));
        printStats(sender, "", RegionBalancer.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.smart"), SmartRegionManager.getStats());
    }

    private static void sendChunkStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.chunk.system"), NamedTextColor.YELLOW));
        printStats(sender, "", MiliChunkSystem.getStats());
    }

    private static void sendMemoryStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.memory.system"), NamedTextColor.YELLOW));
        printStats(sender, "", MemoryOptimizer.getStats());
    }

    private static void sendCrossRegionStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.cross.region"), NamedTextColor.YELLOW));
        printStats(sender, "", CrossRegionHelper.getStats());
    }

    private static void sendOptimizationStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.optimizations"), NamedTextColor.YELLOW));
        printStats(sender, I18nManager.get("miperf.prefix.entity_dirty"), EntityDirtyTracker.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.dynamic_vd"), DynamicViewDistanceManager.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.cross_dim_teleport"), CrossDimensionTeleportQueue.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.async_pathfinder"), AsyncPathfinder.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.chunk_delta"), ChunkDeltaCompressor.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.light_callback"), LightCallbackManager.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.entity_density"), EntityDensityTracker.getStats());
        printStats(sender, I18nManager.get("miperf.prefix.network"), NetworkOptimizer.getStats());
    }

    private static void sendFeatureStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("miperf.features"), NamedTextColor.YELLOW));
        printStats(sender, I18nManager.get("miperf.prefix.player_heatmap"), PlayerHeatmap.getStats());
    }

    private static void printStats(CommandSender sender, String prefix, Map<String, Object> stats) {
        if (stats.isEmpty()) return;
        if (!prefix.isEmpty()) {
            sender.sendMessage(Component.text("  [" + prefix + "]", NamedTextColor.AQUA));
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                sender.sendMessage(Component.text("    " + entry.getKey() + ": ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(entry.getValue()), NamedTextColor.WHITE)));
            }
        } else {
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                sender.sendMessage(Component.text("  " + entry.getKey() + ": ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(entry.getValue()), NamedTextColor.WHITE)));
            }
        }
    }
}