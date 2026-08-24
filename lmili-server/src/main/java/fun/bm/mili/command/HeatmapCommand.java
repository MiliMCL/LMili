package fun.bm.mili.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.utils.player.PlayerHeatmap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.RootNode;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Operator command for the player activity heatmap.
 *
 * <p>All user-visible strings are routed through {@link I18nManager}.</p>
 */
public class HeatmapCommand extends RootNode {
    private static final String PERM_BASE = "mili.admin.heatmap";

    public HeatmapCommand() {
        super("heatmap", PERM_BASE);
        children(
                new HeatmapResetCommand(),
                new HeatmapExportCommand()
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        sendStats(context.getSender());
        return true;
    }

    private static void sendStats(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("heatmap.title"), NamedTextColor.GOLD));
        sender.sendMessage(Component.empty());
        Map<String, Object> stats = PlayerHeatmap.getStats();
        if (stats.isEmpty()) {
            sender.sendMessage(Component.text("  " + I18nManager.get("heatmap.no_data"), NamedTextColor.GRAY));
            return;
        }
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(entry.getValue()), NamedTextColor.WHITE)));
        }
    }

    private static class HeatmapResetCommand extends org.leavesmc.leaves.command.LiteralNode {
        HeatmapResetCommand() {
            super("reset");
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            PlayerHeatmap.reset();
            context.getSender().sendMessage(Component.text(
                    I18nManager.get("heatmap.reset.success"), NamedTextColor.GREEN));
            return true;
        }
    }

    private static class HeatmapExportCommand extends org.leavesmc.leaves.command.LiteralNode {
        HeatmapExportCommand() {
            super("export");
            children(WorldArg::new);
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            // 无参数时回显用法
            context.getSender().sendMessage(Component.text(
                    I18nManager.get("heatmap.error.usage_export"), NamedTextColor.RED));
            return true;
        }

        // Mili - terminal argument node that captures <world>; bind executes on the argument node
        // itself so /heatmap export myworld dispatches correctly (fixes "command forces extra arg"
        // UI artifact and makes the arg actually reachable).
        private class WorldArg extends org.leavesmc.leaves.command.ArgumentNode<String> {
            WorldArg() {
                super("world", com.mojang.brigadier.arguments.StringArgumentType.word());
            }

            @Override
            protected CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                    @NotNull CommandContext context,
                    @NotNull com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
                for (final World world : Bukkit.getServer().getWorlds()) {
                    builder.suggest(world.getName());
                }
                return builder.buildFuture();
            }

            @Override
            protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
                CommandSender sender = context.getSender();
                String worldName = context.getArgument(WorldArg.class);
                try {
                    PlayerHeatmap.exportToFile(worldName);
                    sender.sendMessage(Component.text(
                            I18nManager.get("heatmap.export.success", worldName), NamedTextColor.GREEN));
                } catch (IOException e) {
                    sender.sendMessage(Component.text(
                            I18nManager.get("heatmap.export.failed", e.getMessage()), NamedTextColor.RED));
                }
                return true;
            }
        }
    }
}