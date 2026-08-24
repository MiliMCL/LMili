package fun.bm.mili.lmili.commands.control;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.mili.lmili.runtime.policy.CommandAction;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * /lmili control ioworkers &lt;n&gt; —— IO worker 动态调整（Phase 4，§5.4；写权限）。
 * 值域钳制 [1, maxIoWorkers] 由服务端完成（命令层透传原始串）。
 */
public class IoWorkersSubcommand extends ControlSubcommand {

    public IoWorkersSubcommand(ControlCommand parent) {
        super("ioworkers", parent);
        children(new WorkersArgument());
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    // 非静态内部类：需访问外层 submitAndReply（instance 方法）；与 SetCommand.ValueArgument 同构
    class WorkersArgument extends ArgumentNode<String> {
        WorkersArgument() {
            super("n", StringArgumentType.word());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (String v : new String[]{"1", "2", "4", "6", "8", "12", "24"}) {
                if (builder.getRemaining().isEmpty() || v.startsWith(builder.getRemaining())) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            final String raw = context.getArgumentOrDefault(WorkersArgument.class, "");
            final CommandSender sender = context.getSender();
            return submitAndReply(sender, PolicyCommand.fromModule(
                    "command:" + actorOf(sender), CommandAction.SET_IO_WORKERS,
                    Map.of("ioWorkers", raw.trim().isEmpty() ? "0" : raw.trim())));
        }
    }
}
