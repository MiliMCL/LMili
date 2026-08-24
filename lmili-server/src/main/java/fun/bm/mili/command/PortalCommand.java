package fun.bm.mili.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.portal.PortalLinkManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.RootNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Operator command for portal link management.
 *
 * <p>All user-visible strings are routed through {@link I18nManager} so that the
 * operator experience follows the server's current locale (configured via
 * {@code function.language.lang} in {@code mili.properties}).</p>
 */
public class PortalCommand extends RootNode {
    private static final String PERM_BASE = "mili.admin.portal";

    public PortalCommand() {
        super("portal", PERM_BASE);
        children(
                new ListCommand(),
                new RemoveCommand(),
                new ClearCommand(),
                new InfoCommand(),
                new ReloadCommand()
        );
    }

    @Override
    public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
        return source.getSender().hasPermission(PERM_BASE);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        sendHelp(context.getSender());
        return true;
    }

    private static void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text(I18nManager.get("portal.help.title"), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /portal list ", NamedTextColor.GRAY).append(
                Component.text("- " + I18nManager.get("portal.help.list"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /portal remove <key> ", NamedTextColor.GRAY).append(
                Component.text("- " + I18nManager.get("portal.help.remove"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /portal clear ", NamedTextColor.GRAY).append(
                Component.text("- " + I18nManager.get("portal.help.clear"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /portal info ", NamedTextColor.GRAY).append(
                Component.text("- " + I18nManager.get("portal.help.info"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /portal reload ", NamedTextColor.GRAY).append(
                Component.text("- " + I18nManager.get("portal.help.reload"), NamedTextColor.WHITE)));
    }

    private static class ListCommand extends org.leavesmc.leaves.command.LiteralNode {
        ListCommand() {
            super("list");
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            CommandSender sender = context.getSender();
            Map<String, PortalLinkManager.PortalPair> pairs = PortalLinkManager.getAllPairs();
            sender.sendMessage(Component.text(
                    I18nManager.get("portal.list.title", pairs.size()), NamedTextColor.GOLD));
            for (Map.Entry<String, PortalLinkManager.PortalPair> entry : pairs.entrySet()) {
                PortalLinkManager.PortalPair p = entry.getValue();
                sender.sendMessage(Component.text("  " + entry.getKey(), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("    " + I18nManager.get("portal.list.from") + " ", NamedTextColor.GRAY)
                        .append(Component.text(p.getSourceWorld() + " (" + p.getSourceX() + ", " + p.getSourceY() + ", " + p.getSourceZ() + ")", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("    " + I18nManager.get("portal.list.to") + "   ", NamedTextColor.GRAY)
                        .append(Component.text(p.getDestWorld() + " (" + p.getDestX() + ", " + p.getDestY() + ", " + p.getDestZ() + ")", NamedTextColor.GREEN)));
            }
            return true;
        }
    }

    private static class RemoveCommand extends org.leavesmc.leaves.command.LiteralNode {
        RemoveCommand() {
            super("remove");
            children(KeyArg::new);
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            // 无参数时回显用法
            context.getSender().sendMessage(Component.text(
                    I18nManager.get("portal.error.usage_remove"), NamedTextColor.RED));
            context.getSender().sendMessage(Component.text(
                    I18nManager.get("portal.error.hint_list"), NamedTextColor.GRAY));
            return true;
        }

        // Mili - terminal argument node that captures <key>; bind executes on the argument node
        // itself so /portal remove somekey dispatches correctly (fixes "command forces extra arg"
        // UI artifact and the prior getStringOrDefault("key", null) which never resolved).
        private class KeyArg extends org.leavesmc.leaves.command.ArgumentNode<String> {
            KeyArg() {
                super("key", com.mojang.brigadier.arguments.StringArgumentType.word());
            }

            @Override
            protected CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
                    @NotNull CommandContext context,
                    @NotNull com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
                for (final String key : PortalLinkManager.getAllPairs().keySet()) {
                    if (key.startsWith(builder.getRemaining())) {
                        builder.suggest(key);
                    }
                }
                return builder.buildFuture();
            }

            @Override
            protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
                CommandSender sender = context.getSender();
                String key = context.getArgument(KeyArg.class);
                if (PortalLinkManager.removePair(key)) {
                    sender.sendMessage(Component.text(
                            I18nManager.get("portal.remove.success", key), NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text(
                            I18nManager.get("portal.remove.not_found", key), NamedTextColor.RED));
                }
                return true;
            }
        }
    }

    private static class ClearCommand extends org.leavesmc.leaves.command.LiteralNode {
        ClearCommand() {
            super("clear");
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            CommandSender sender = context.getSender();
            int count = PortalLinkManager.getAllPairs().size();
            for (String key : PortalLinkManager.getAllPairs().keySet()) {
                PortalLinkManager.removePair(key);
            }
            sender.sendMessage(Component.text(
                    I18nManager.get("portal.clear.success", count), NamedTextColor.GREEN));
            return true;
        }
    }

    private static class InfoCommand extends org.leavesmc.leaves.command.LiteralNode {
        InfoCommand() {
            super("info");
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            CommandSender sender = context.getSender();
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text(
                        I18nManager.get("portal.error.player_only"), NamedTextColor.RED));
                return true;
            }
            Location loc = player.getLocation();
            String key = PortalLinkManager.locationKey(loc);
            PortalLinkManager.PortalPair pair = PortalLinkManager.findPair(loc);
            sender.sendMessage(Component.text(
                    I18nManager.get("portal.info.title"), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  " + I18nManager.get("portal.info.position") + " ", NamedTextColor.GRAY)
                    .append(Component.text(key, NamedTextColor.WHITE)));
            if (pair != null) {
                sender.sendMessage(Component.text("  " + I18nManager.get("portal.info.linked_to") + " ", NamedTextColor.GRAY)
                        .append(Component.text(pair.getDestWorld() + " (" + pair.getDestX() + ", " + pair.getDestY() + ", " + pair.getDestZ() + ")", NamedTextColor.GREEN)));
            } else {
                sender.sendMessage(Component.text("  " + I18nManager.get("portal.info.no_link"), NamedTextColor.GRAY));
            }
            sender.sendMessage(Component.text("  " + I18nManager.get("portal.info.search_radius") + " ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(PortalLinkManager.getSearchRadius()), NamedTextColor.WHITE)));
            return true;
        }
    }

    private static class ReloadCommand extends org.leavesmc.leaves.command.LiteralNode {
        ReloadCommand() {
            super("reload");
        }

        @Override
        public boolean requires(@NotNull io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            PortalLinkManager.load();
            context.getSender().sendMessage(Component.text(
                    I18nManager.get("portal.reload.success"), NamedTextColor.GREEN));
            return true;
        }
    }
}