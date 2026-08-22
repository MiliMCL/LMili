package fun.bm.mili.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;
import org.leavesmc.leaves.command.RootNode;

import java.util.Collection;

/**
 * Operator command for the LMili Identity System.
 *
 * <p>V2 §23 requires at least:</p>
 * <ul>
 *   <li>{@code /plugins}          — list all identities</li>
 *   <li>{@code /plugins info <id>}</li>
 *   <li>{@code /plugins conflicts} — REQUIRED minimum</li>
 *   <li>{@code /plugins observe <id>}</li>
 *   <li>{@code /plugins enable <id>}</li>
 *   <li>{@code /plugins disable <id>}</li>
 * </ul>
 */
public final class PluginsCommand extends RootNode {

    private static final String PERM_BASE = "mili.admin.identity";

    public PluginsCommand() {
        super("plugins", PERM_BASE);
        children(new ListCommand(),
                 new InfoCommand(),
                 new ConflictsCommand(),
                 new ObserveCommand(),
                 new EnableCommand(),
                 new DisableCommand());
    }

    @Override
    public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
        return source.getSender().hasPermission(PERM_BASE);
    }

    @Override
    protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
        sendHelp(context.getSender());
        return true;
    }

    private static void sendHelp(final CommandSender sender) {
        sender.sendMessage(Component.text("=== LMili Plugin Identity ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /plugins list", NamedTextColor.GRAY)
                .append(Component.text("                  List all registered plugin identities", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /plugins info <id>", NamedTextColor.GRAY)
                .append(Component.text("            Show one identity in detail", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /plugins conflicts", NamedTextColor.GRAY)
                .append(Component.text("               Show all recorded conflicts", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /plugins observe <id>", NamedTextColor.GRAY)
                .append(Component.text("           Move plugin into OBSERVE state", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /plugins enable <id>", NamedTextColor.GRAY)
                .append(Component.text("            Restore a plugin to ACTIVE", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /plugins disable <id>", NamedTextColor.GRAY)
                .append(Component.text("           Disable a plugin", NamedTextColor.WHITE)));
    }

    private static NamedTextColor statusColor(final PluginStatus status) {
        return switch (status) {
            case ACTIVE     -> NamedTextColor.GREEN;
            case OBSERVE    -> NamedTextColor.YELLOW;
            case CONFLICT   -> NamedTextColor.RED;
            case DISABLED   -> NamedTextColor.GRAY;
            case DISCOVERED -> NamedTextColor.AQUA;
            case LOADING    -> NamedTextColor.AQUA;
            case FAILED     -> NamedTextColor.DARK_RED;
            case UNLOADED   -> NamedTextColor.DARK_GRAY;
        };
    }

    private static final class ListCommand extends LiteralNode {
        ListCommand() { super("list"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final Collection<fun.bm.mili.lmili.api.identity.PluginIdentity> all =
                    LMili.getPluginIdentityManager().getAll();
            sender.sendMessage(Component.text("=== Plugin Identities (" + all.size() + ") ===",
                    NamedTextColor.GOLD));
            for (final fun.bm.mili.lmili.api.identity.PluginIdentity p : all) {
                sender.sendMessage(
                        Component.text("  " + p.id().value(), NamedTextColor.WHITE)
                                .append(Component.text(" [" + p.status() + "]", statusColor(p.status())))
                                .append(Component.text(" " + p.version(), NamedTextColor.GRAY))
                                .append(Component.text(" " + p.type(), NamedTextColor.DARK_AQUA))
                                .append(Component.text(" (" + p.source() + ")", NamedTextColor.DARK_GRAY)));
            }
            return true;
        }
    }

    private static final class InfoCommand extends LiteralNode {
        InfoCommand() { super("info"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final String idStr = context.getStringOrDefault("id", null);
            if (idStr == null) {
                sender.sendMessage(Component.text("Usage: /plugins info <id>", NamedTextColor.RED));
                return true;
            }
            final PluginId pid = PluginId.parseNullable(idStr);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid id: " + idStr, NamedTextColor.RED));
                return true;
            }
            final fun.bm.mili.lmili.api.identity.PluginIdentity p =
                    LMili.getPluginIdentityManager().find(pid).orElse(null);
            if (p == null) {
                sender.sendMessage(Component.text("No identity registered: " + idStr, NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("=== " + p.id().value() + " ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  name:      ", NamedTextColor.GRAY).append(Component.text(p.name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  version:   ", NamedTextColor.GRAY).append(Component.text(p.version(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  publisher: ", NamedTextColor.GRAY).append(Component.text(p.publisher(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  type:      ", NamedTextColor.GRAY).append(Component.text(p.type().name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  parent:    ", NamedTextColor.GRAY).append(Component.text(
                    p.parentId().map(PluginId::value).orElse("-"), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  status:    ", NamedTextColor.GRAY).append(
                    Component.text(p.status().name(), statusColor(p.status()))));
            sender.sendMessage(Component.text("  trust:     ", NamedTextColor.GRAY).append(Component.text(p.trustLevel().name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  source:    ", NamedTextColor.GRAY).append(Component.text(p.source(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  registered:", NamedTextColor.GRAY).append(Component.text(p.registeredAt().toString(), NamedTextColor.WHITE)));
            return true;
        }
    }

    private static final class ConflictsCommand extends LiteralNode {
        ConflictsCommand() { super("conflicts"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final Collection<PluginIdentityConflict> conflicts = mgr.getConflicts();
            sender.sendMessage(Component.text("=== Conflicts (" + conflicts.size() + ") ===",
                    NamedTextColor.GOLD));
            if (conflicts.isEmpty()) {
                sender.sendMessage(Component.text("  (none)", NamedTextColor.GRAY));
                return true;
            }
            for (final PluginIdentityConflict c : conflicts) {
                sender.sendMessage(
                        Component.text("  " + c.id().value(), NamedTextColor.RED)
                                .append(Component.text(" reason=" + c.reason(), NamedTextColor.YELLOW))
                                .append(Component.text(" existing=" + c.existing().name()
                                        + "@" + c.existing().version(), NamedTextColor.WHITE))
                                .append(Component.text(" incoming=" + c.incoming().name()
                                        + "@" + c.incoming().version(), NamedTextColor.GRAY))
                                .append(Component.text(" at=" + c.detectedAt(), NamedTextColor.DARK_GRAY)));
            }
            return true;
        }
    }

    private static final class ObserveCommand extends LiteralNode {
        ObserveCommand() { super("observe"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final String idStr = context.getStringOrDefault("id", null);
            if (idStr == null) {
                sender.sendMessage(Component.text("Usage: /plugins observe <id>", NamedTextColor.RED));
                return true;
            }
            final PluginId pid = PluginId.parseNullable(idStr);
            if (pid == null || LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.OBSERVE).isEmpty()) {
                sender.sendMessage(Component.text("Not registered: " + idStr, NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Moved " + pid.value() + " into OBSERVE.", NamedTextColor.YELLOW));
            return true;
        }
    }

    private static final class EnableCommand extends LiteralNode {
        EnableCommand() { super("enable"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final String idStr = context.getStringOrDefault("id", null);
            if (idStr == null) {
                sender.sendMessage(Component.text("Usage: /plugins enable <id>", NamedTextColor.RED));
                return true;
            }
            final PluginId pid = PluginId.parseNullable(idStr);
            if (pid == null || LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.ACTIVE).isEmpty()) {
                sender.sendMessage(Component.text("Not registered: " + idStr, NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Moved " + pid.value() + " back to ACTIVE.", NamedTextColor.GREEN));
            return true;
        }
    }

    private static final class DisableCommand extends LiteralNode {
        DisableCommand() { super("disable"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final String idStr = context.getStringOrDefault("id", null);
            if (idStr == null) {
                sender.sendMessage(Component.text("Usage: /plugins disable <id>", NamedTextColor.RED));
                return true;
            }
            final PluginId pid = PluginId.parseNullable(idStr);
            if (pid == null || LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.DISABLED).isEmpty()) {
                sender.sendMessage(Component.text("Not registered: " + idStr, NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Disabled " + pid.value() + ".", NamedTextColor.GRAY));
            return true;
        }
    }
}