package fun.bm.mili.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.identity.ResourceQuota;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;
import org.leavesmc.leaves.command.RootNode;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Operator command for the LMili Identity System.
 *
 * <p>V2 §23 requires at least:</p>
 * <ul>
 *   <li>{@code /pluginid}           — list all identities</li>
 *   <li>{@code /pluginid info <id>}</li>
 *   <li>{@code /pluginid conflicts} — REQUIRED minimum</li>
 *   <li>{@code /pluginid observe <id>}</li>
 *   <li>{@code /pluginid enable <id>}</li>
 *   <li>{@code /pluginid disable <id>}</li>
 * </ul>
 *
 * <p>Note: we deliberately use the root name {@code pluginid} instead of
 * {@code plugins} because Paper's {@code PaperPluginsCommand} already owns
 * that literal. Using it would cause Paper's later registration to overwrite
 * ours during server startup.</p>
 */
public final class PluginIdCommand extends RootNode {

    private static final String PERM_BASE = "mili.admin.identity";

    public PluginIdCommand() {
        super("pluginid", PERM_BASE);
        children(new ListCommand(),
                 new InfoCommand(),
                 new ConflictsCommand(),
                 new ObserveCommand(),
                 new EnableCommand(),
                 new DisableCommand(),
                 new TasksCommand(),
                 new StatusCommand());
    }

    @Override
    protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
        sendHelp(context.getSender());
        return true;
    }

    private static void sendHelp(final CommandSender sender) {
        sender.sendMessage(Component.text("=== LMili Plugin Identity ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /pluginid list", NamedTextColor.GRAY)
                .append(Component.text("                 List all registered plugin identities", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid info <id>", NamedTextColor.GRAY)
                .append(Component.text("           Show one identity in detail", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid conflicts", NamedTextColor.GRAY)
                .append(Component.text("              Show all recorded conflicts", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid observe <id>", NamedTextColor.GRAY)
                .append(Component.text("          Move plugin into OBSERVE state", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid enable <id>", NamedTextColor.GRAY)
                .append(Component.text("           Restore a plugin to ACTIVE", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid disable <id>", NamedTextColor.GRAY)
                .append(Component.text("          Disable a plugin (cancels tasks)", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid tasks <id>", NamedTextColor.GRAY)
                .append(Component.text("           Show task summary for a plugin", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid status <id>", NamedTextColor.GRAY)
                .append(Component.text("          Show runtime status for a plugin", NamedTextColor.WHITE)));
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

    /**
     * Argument node that provides tab-completion suggestions from all
     * registered plugin identities.
     *
     * <p>关键设计：本节点自身绑定 {@code executes}，使 {@code /pluginid <sub> <id>} 在 argument 节点
     * 终止时即可找到执行入口（避免客户端显示"command incomplete"占位符）。
     *
     * <p>handler 类型用 {@code ThrowingFunction}（自定义 SAM）以允许 {@code CommandSyntaxException} 透传 —
     * {@code java.util.function.Function} 的 {@code apply} 不能抛 checked 异常，所以 {@code this::execute}
     * 不能直接传给 {@code Function}。
     */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws CommandSyntaxException;
    }

    private static final class PluginIdArgument extends ArgumentNode<String> {
        private final ThrowingFunction<CommandContext, Boolean> executeHandler;

        PluginIdArgument(final ThrowingFunction<CommandContext, Boolean> executeHandler) {
            super("id", StringArgumentType.word());
            this.executeHandler = executeHandler;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(
                @NotNull final CommandContext context,
                @NotNull final SuggestionsBuilder builder) {
            for (final PluginIdentity identity : LMili.getPluginIdentityManager().getAll()) {
                final String idValue = identity.id().value();
                if (idValue.startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(idValue);
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean canExecute() {
            return executeHandler != null;
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            return executeHandler != null && executeHandler.apply(context);
        }

        /**
         * Resolve the argument to a PluginId, or null if invalid / not found.
         */
        private static PluginId resolve(@NotNull final CommandContext context) {
            final String raw = context.getArgument(PluginIdArgument.class);
            return PluginId.parseNullable(raw);
        }
    }

    // ========================================================================
    // Subcommands
    // ========================================================================

    private static final class ListCommand extends LiteralNode {
        ListCommand() { super("list"); }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final Collection<PluginIdentity> all = LMili.getPluginIdentityManager().getAll();
            sender.sendMessage(Component.text("=== Plugin Identities (" + all.size() + ") ===",
                    NamedTextColor.GOLD));
            for (final PluginIdentity p : all) {
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
        InfoCommand() {
            super("info");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid info <id>", NamedTextColor.RED));
                return true;
            }
            final PluginIdentity p = LMili.getPluginIdentityManager().find(pid).orElse(null);
            if (p == null) {
                sender.sendMessage(Component.text("No identity registered: " + pid.value(), NamedTextColor.RED));
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
        ObserveCommand() {
            super("observe");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid observe <id>", NamedTextColor.RED));
                return true;
            }
            if (LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.OBSERVE).isEmpty()) {
                sender.sendMessage(Component.text("Not registered: " + pid.value(), NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Moved " + pid.value() + " into OBSERVE.", NamedTextColor.YELLOW));
            return true;
        }
    }

    private static final class EnableCommand extends LiteralNode {
        EnableCommand() {
            super("enable");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid enable <id>", NamedTextColor.RED));
                return true;
            }
            if (LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.ACTIVE).isEmpty()) {
                sender.sendMessage(Component.text("Not registered: " + pid.value(), NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Moved " + pid.value() + " back to ACTIVE.", NamedTextColor.GREEN));
            return true;
        }
    }

    private static final class DisableCommand extends LiteralNode {
        DisableCommand() {
            super("disable");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid disable <id>", NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            if (!mgr.contains(pid)) {
                sender.sendMessage(Component.text("Not registered: " + pid.value(), NamedTextColor.RED));
                return true;
            }

            // 1. Update status to DISABLED (temporary — keeps context, identity)
            mgr.setStatus(pid, PluginStatus.DISABLED);
            sender.sendMessage(Component.text("Disabled " + pid.value() + ".", NamedTextColor.GRAY));

            // 2. Cancel pending scheduler tasks via the bridge
            final int cancelled = PluginSchedulerBridge.disablePlugin(pid);
            if (cancelled > 0) {
                sender.sendMessage(Component.text("  → cancelled " + cancelled + " pending task(s)", NamedTextColor.DARK_GRAY));
            }

            // 3. Runtime context is retained — /pluginid enable restores immediately
            sender.sendMessage(Component.text("  → use /pluginid enable " + pid.value() + " to restore", NamedTextColor.DARK_GRAY));

            return true;
        }
    }

    private static final class TasksCommand extends LiteralNode {
        TasksCommand() {
            super("tasks");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid tasks <id>", NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginIdentity identity = mgr.find(pid).orElse(null);
            if (identity == null) {
                sender.sendMessage(Component.text("Not registered: " + pid.value(), NamedTextColor.RED));
                return true;
            }

            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pid);

            sender.sendMessage(Component.text("=== Tasks: " + pid.value() + " ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  Status: ", NamedTextColor.GRAY)
                    .append(Component.text(identity.status().name(), statusColor(identity.status()))));

            // Live tracked pending count
            final int pending = PluginSchedulerBridge.pendingTaskCount(pid);
            sender.sendMessage(Component.text("  Pending (tracked): ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(pending), NamedTextColor.WHITE)));

            // Cumulative counters from ResourceQuota
            if (ctx != null) {
                final ResourceQuota q = ctx.resourceQuota();
                sender.sendMessage(Component.text("  Submitted: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksSubmitted()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  Completed: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksCompleted()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  Failed: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksFailed()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  Cancelled: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksCancelled()), NamedTextColor.WHITE)));

                // Scheduler domain info
                final fun.bm.mili.lmili.api.identity.SchedulerDomain domain = ctx.schedulerDomain();
                sender.sendMessage(Component.text("  Domain: ", NamedTextColor.GRAY)
                        .append(Component.text(
                                "priority=" + domain.priority()
                                        + " maxConcurrent=" + domain.maxConcurrentTasks()
                                        + " accepts=" + domain.acceptsSubmissions(),
                                NamedTextColor.WHITE)));
            } else {
                sender.sendMessage(Component.text("  (no runtime context)", NamedTextColor.DARK_GRAY));
            }

            return true;
        }
    }

    private static final class StatusCommand extends LiteralNode {
        StatusCommand() {
            super("status");
            children(new PluginIdArgument(this::execute));
        }

        @Override
        public boolean requires(@NotNull final io.papermc.paper.command.brigadier.CommandSourceStack source) {
            return source.getSender().hasPermission(PERM_BASE);
        }

        @Override
        protected boolean execute(@NotNull final CommandContext context) throws CommandSyntaxException {
            final CommandSender sender = context.getSender();
            final PluginId pid = PluginIdArgument.resolve(context);
            if (pid == null) {
                sender.sendMessage(Component.text("Invalid or missing id.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Usage: /pluginid status <id>", NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginIdentity identity = mgr.find(pid).orElse(null);
            if (identity == null) {
                sender.sendMessage(Component.text("Not registered: " + pid.value(), NamedTextColor.RED));
                return true;
            }

            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pid);

            sender.sendMessage(Component.text("=== Runtime Status: " + pid.value() + " ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  Identity: ", NamedTextColor.GRAY)
                    .append(Component.text(identity.name() + " v" + identity.version(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Status: ", NamedTextColor.GRAY)
                    .append(Component.text(identity.status().name(), statusColor(identity.status()))));

            if (ctx != null) {
                sender.sendMessage(Component.text("  Lifecycle: ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.lifecycleState().name(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  Permission: ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.permissionContext().level().name(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  Scheduler Domain: ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.schedulerDomain().toString(), NamedTextColor.WHITE)));

                // Observability snapshot
                final fun.bm.mili.lmili.api.identity.ObservabilityContext obs = ctx.observability();
                final fun.bm.mili.lmili.api.identity.ObservabilityContext.Snapshot snap = obs.snapshot();
                sender.sendMessage(Component.text("  Observability:", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("    API requests: " + snap.apiRequests(), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    Scheduler requests: " + snap.schedulerRequests(), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    Tasks created: " + snap.tasksCreated(), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    Tasks failed: " + snap.tasksFailed(), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    Permission denials: " + snap.permissionDenials(), NamedTextColor.DARK_GRAY));
            } else {
                sender.sendMessage(Component.text("  (no runtime context — not registered by bootstrap)", NamedTextColor.DARK_GRAY));
            }

            return true;
        }
    }
}