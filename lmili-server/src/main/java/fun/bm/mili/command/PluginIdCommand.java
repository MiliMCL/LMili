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
import fun.bm.mili.lmili.command.identity.PluginIdentityAutoDiscovery;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
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
 *
 * <p>All user-visible strings are routed through {@link I18nManager} so that
 * the operator experience follows the server's current locale (configured via
 * {@code function.language.lang} in {@code mili.properties}). When the locale
 * is {@code zh_cn} / {@code zh_tw} / etc., operators see translated text;
 * otherwise they fall back to {@code en_us}.</p>
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
        sender.sendMessage(Component.text(I18nManager.get("pluginid.help.title"), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /pluginid list", NamedTextColor.GRAY)
                .append(Component.text("                 " + I18nManager.get("pluginid.help.list"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid info <id>", NamedTextColor.GRAY)
                .append(Component.text("           " + I18nManager.get("pluginid.help.info"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid conflicts", NamedTextColor.GRAY)
                .append(Component.text("              " + I18nManager.get("pluginid.help.conflicts"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid observe <id>", NamedTextColor.GRAY)
                .append(Component.text("          " + I18nManager.get("pluginid.help.observe"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid enable <id>", NamedTextColor.GRAY)
                .append(Component.text("           " + I18nManager.get("pluginid.help.enable"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid disable <id>", NamedTextColor.GRAY)
                .append(Component.text("          " + I18nManager.get("pluginid.help.disable"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid tasks <id>", NamedTextColor.GRAY)
                .append(Component.text("           " + I18nManager.get("pluginid.help.tasks"), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  /pluginid status <id>", NamedTextColor.GRAY)
                .append(Component.text("          " + I18nManager.get("pluginid.help.status"), NamedTextColor.WHITE)));
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
            // 仅补全已注册 / DISCOVERED 的完整 PluginId（双段 publisher.plugin 形式）。
            // 单段 Bukkit plugin name 不再作为补全候选 —— V2 规范要求用户必须用完整 id。
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
         * Resolve the argument to a PluginId.
         *
         * <p><b>V2 规范硬要求</b>：必须是完整双段 id（{@code publisher.plugin}），
         * 单段输入（如 {@code spark}）会被拒绝。外部 plugin 应通过 {@code lmili.json}
         * 声明完整 id，运行时通过 {@code /pluginid list} 或 Tab 补全查完整 id。
         *
         * <p>策略（按顺序）：
         * <ol>
         *   <li>{@link PluginId#parseNullable} —— 严格双段格式（publisher.plugin）</li>
         *   <li>{@link PluginIdentityAutoDiscovery#resolveOrDiscover} —— 兜底查 Bukkit
         *       PluginManager（仅按完整双段 id 形态），若 LMili 尚未注册则自动以 DISCOVERED
         *       状态注册（修复外部 plugin 看不到的问题）</li>
         * </ol>
         *
         * @return PluginId（保证非 null），但可能不在 identity registry 中
         */
        private static PluginId resolve(@NotNull final CommandContext context) {
            final String raw = context.getArgument(PluginIdArgument.class);
            if (raw == null || raw.isBlank()) return null;

            // 1. 严格双段格式（V2 §23 硬要求；单段直接拒绝）
            PluginId pid = PluginId.parseNullable(raw);
            if (pid != null) return pid;

            // 2. 查 Bukkit / 自动注册（仅双段 id）
            try {
                return PluginIdentityAutoDiscovery.resolveOrDiscover(raw);
            } catch (Throwable t) {
                return null;
            }
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
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.list.title", all.size()),
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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_info"), NamedTextColor.RED));
                return true;
            }
            final PluginIdentity p = LMili.getPluginIdentityManager().find(pid).orElse(null);
            if (p == null) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.info.title", p.id().value()), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.name") + "      ", NamedTextColor.GRAY)
                    .append(Component.text(p.name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.version") + "   ", NamedTextColor.GRAY)
                    .append(Component.text(p.version(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.publisher") + " ", NamedTextColor.GRAY)
                    .append(Component.text(p.publisher(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.type") + "      ", NamedTextColor.GRAY)
                    .append(Component.text(p.type().name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.parent") + "    ", NamedTextColor.GRAY)
                    .append(Component.text(
                            p.parentId().map(PluginId::value).orElse("-"), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.status") + "    ", NamedTextColor.GRAY)
                    .append(Component.text(p.status().name(), statusColor(p.status()))));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.trust") + "     ", NamedTextColor.GRAY)
                    .append(Component.text(p.trustLevel().name(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.source") + "    ", NamedTextColor.GRAY)
                    .append(Component.text(p.source(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.info.field.registered") + ":", NamedTextColor.GRAY)
                    .append(Component.text(p.registeredAt().toString(), NamedTextColor.WHITE)));
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
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.conflicts.title", conflicts.size()),
                    NamedTextColor.GOLD));
            if (conflicts.isEmpty()) {
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.conflicts.none"), NamedTextColor.GRAY));
                return true;
            }
            for (final PluginIdentityConflict c : conflicts) {
                sender.sendMessage(
                        Component.text("  " + c.id().value(), NamedTextColor.RED)
                                .append(Component.text(" " + I18nManager.get("pluginid.conflicts.reason", c.reason()), NamedTextColor.YELLOW))
                                .append(Component.text(" " + I18nManager.get("pluginid.conflicts.existing",
                                        c.existing().name() + "@" + c.existing().version()), NamedTextColor.WHITE))
                                .append(Component.text(" " + I18nManager.get("pluginid.conflicts.incoming",
                                        c.incoming().name() + "@" + c.incoming().version()), NamedTextColor.GRAY))
                                .append(Component.text(" " + I18nManager.get("pluginid.conflicts.at", c.detectedAt()), NamedTextColor.DARK_GRAY)));
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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_observe"), NamedTextColor.RED));
                return true;
            }
            if (LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.OBSERVE).isEmpty()) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.observe.moved", pid.value()), NamedTextColor.YELLOW));
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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_enable"), NamedTextColor.RED));
                return true;
            }
            if (LMili.getPluginIdentityManager().setStatus(pid, PluginStatus.ACTIVE).isEmpty()) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.enable.restored", pid.value()), NamedTextColor.GREEN));
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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_disable"), NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            if (!mgr.contains(pid)) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }

            // 1. Update status to DISABLED (temporary — keeps context, identity)
            mgr.setStatus(pid, PluginStatus.DISABLED);
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.disable.disabled", pid.value()), NamedTextColor.GRAY));

            // 2. Cancel pending scheduler tasks via the bridge
            final int cancelled = PluginSchedulerBridge.disablePlugin(pid);
            if (cancelled > 0) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.disable.cancelled", cancelled), NamedTextColor.DARK_GRAY));
            }

            // 3. Runtime context is retained — /pluginid enable restores immediately
            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.disable.hint_enable", pid.value()), NamedTextColor.DARK_GRAY));

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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_tasks"), NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginIdentity identity = mgr.find(pid).orElse(null);
            if (identity == null) {
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }

            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pid);

            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.tasks.title", pid.value()), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.status") + " ", NamedTextColor.GRAY)
                    .append(Component.text(identity.status().name(), statusColor(identity.status()))));

            // Live tracked pending count
            final int pending = PluginSchedulerBridge.pendingTaskCount(pid);
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.pending_tracked") + " ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(pending), NamedTextColor.WHITE)));

            // Cumulative counters from ResourceQuota
            if (ctx != null) {
                final ResourceQuota q = ctx.resourceQuota();
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.submitted") + " ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksSubmitted()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.completed") + " ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksCompleted()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.failed") + " ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksFailed()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.cancelled") + " ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(q.tasksCancelled()), NamedTextColor.WHITE)));

                // Scheduler domain info
                final fun.bm.mili.lmili.api.identity.SchedulerDomain domain = ctx.schedulerDomain();
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.domain") + " ", NamedTextColor.GRAY)
                        .append(Component.text(
                                "priority=" + domain.priority()
                                        + " maxConcurrent=" + domain.maxConcurrentTasks()
                                        + " accepts=" + domain.acceptsSubmissions(),
                                NamedTextColor.WHITE)));
            } else {
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.tasks.no_runtime_ctx"), NamedTextColor.DARK_GRAY));
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
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.invalid_id"), NamedTextColor.RED));
                sender.sendMessage(Component.text(I18nManager.get("pluginid.error.usage_status"), NamedTextColor.RED));
                return true;
            }

            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginIdentity identity = mgr.find(pid).orElse(null);
            if (identity == null) {
                // PluginIdentityAutoDiscovery 已经处理了 Bukkit 自动注册；到这里说明 Bukkit 也没找到
                sender.sendMessage(Component.text(
                        I18nManager.get("pluginid.error.not_registered", pid.value()), NamedTextColor.RED));
                return true;
            }

            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(pid);

            sender.sendMessage(Component.text(
                    I18nManager.get("pluginid.status.title", pid.value()), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.identity") + " ", NamedTextColor.GRAY)
                    .append(Component.text(identity.name() + " v" + identity.version(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.status") + " ", NamedTextColor.GRAY)
                    .append(Component.text(identity.status().name(), statusColor(identity.status()))));
            sender.sendMessage(Component.text("  source: ", NamedTextColor.GRAY)
                    .append(Component.text(identity.source(), NamedTextColor.WHITE)));

            // Bukkit 实际状态（修复 spark 等外部 plugin 的可见性）
            final Plugin bukkitPlugin = PluginIdentityAutoDiscovery.lookupBukkitPlugin(pid);
            if (bukkitPlugin != null) {
                sender.sendMessage(Component.text("  bukkit: ", NamedTextColor.GRAY)
                        .append(Component.text(bukkitPlugin.getName() + " v" + safeBukkitVersion(bukkitPlugin)
                                + " [" + (bukkitPlugin.isEnabled() ? "ENABLED" : "DISABLED") + "]",
                                bukkitPlugin.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
                final int pendingTasks = PluginSchedulerBridge.pendingTaskCount(pid);
                sender.sendMessage(Component.text("  pending lmili tasks: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(pendingTasks), NamedTextColor.WHITE)));
                if (pendingTasks == 0 && PluginSchedulerBridge.hasBukkitSchedulerTasks(bukkitPlugin)) {
                    sender.sendMessage(Component.text("    (note: plugin submits via BukkitScheduler, not LMili; "
                            + "counters below only reflect LMili-tracked work)", NamedTextColor.YELLOW));
                }
            } else {
                sender.sendMessage(Component.text("  bukkit: ", NamedTextColor.GRAY)
                        .append(Component.text("(not loaded in Bukkit)", NamedTextColor.DARK_GRAY)));
            }

            if (ctx != null) {
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.lifecycle") + " ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.lifecycleState().name(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.permission") + " ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.permissionContext().level().name(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.scheduler_domain") + " ", NamedTextColor.GRAY)
                        .append(Component.text(ctx.schedulerDomain().toString(), NamedTextColor.WHITE)));

                // ResourceQuota（真实运行数字 —— 这是 plugin 实际"跑了多少次"的答案）
                final ResourceQuota quota = ctx.resourceQuota();
                sender.sendMessage(Component.text("  resource quota: ", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("    submitted=" + quota.tasksSubmitted()
                        + " running=" + quota.tasksRunning()
                        + " queued=" + quota.tasksQueued()
                        + " completed=" + quota.tasksCompleted()
                        + " failed=" + quota.tasksFailed()
                        + " cancelled=" + quota.tasksCancelled()
                        + " avgExecMs=" + String.format("%.2f", quota.averageExecutionNanos() / 1_000_000.0),
                        NamedTextColor.DARK_GRAY));

                // Observability snapshot（API 调用计数 —— plugin 调 LMili 调度的次数）
                final fun.bm.mili.lmili.api.identity.ObservabilityContext obs = ctx.observability();
                final fun.bm.mili.lmili.api.identity.ObservabilityContext.Snapshot snap = obs.snapshot();
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.observability"), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("    " + I18nManager.get("pluginid.status.api_requests", snap.apiRequests()), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    " + I18nManager.get("pluginid.status.scheduler_requests", snap.schedulerRequests()), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    " + I18nManager.get("pluginid.status.tasks_created", snap.tasksCreated()), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    " + I18nManager.get("pluginid.status.tasks_failed", snap.tasksFailed()), NamedTextColor.DARK_GRAY));
                sender.sendMessage(Component.text("    " + I18nManager.get("pluginid.status.permission_denials", snap.permissionDenials()), NamedTextColor.DARK_GRAY));
            } else {
                sender.sendMessage(Component.text("  " + I18nManager.get("pluginid.status.no_runtime_ctx"), NamedTextColor.DARK_GRAY));
            }

            return true;
        }

        private static String safeBukkitVersion(Plugin p) {
            try {
                String v = p.getDescription().getVersion();
                return v == null || v.isBlank() ? "unknown" : v;
            } catch (Throwable ignored) {
                return "unknown";
            }
        }
    }
}