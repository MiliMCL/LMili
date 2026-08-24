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
 * /lmili control schedulermode &lt;workers&gt; —— 调度器 worker 模式（§3.9；写权限）。
 *
 * <p>参数原始字符串透传受控通道；值域/语法由 PolicyController 服务端校验（§6.1 规则 2：
 * 命令层钳制不被信任）。
 */
public class SchedulerModeSubcommand extends ControlSubcommand {

    public SchedulerModeSubcommand(ControlCommand parent) {
        super("schedulermode", parent);
        children(new WorkersArgument());
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    // 非静态内部类：需访问外层 submitAndReply（instance 方法）；与 SetCommand.ValueArgument 同构
    class WorkersArgument extends ArgumentNode<String> {
        WorkersArgument() {
            super("workers", StringArgumentType.word());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (String v : new String[]{"1", "2", "4", "8", "16"}) {
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
                    "command:" + actorOf(sender), CommandAction.SET_SCHEDULER_MODE,
                    Map.of("schedulerWorkers", raw.trim().isEmpty() ? "0" : raw.trim())));
        }
    }
}
