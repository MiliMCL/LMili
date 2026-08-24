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
 * /lmili control degrade [reason] —— 手动降级（§6.1；写权限 + op）。
 * reason 可选：MANUAL / METRICS_FAILURE / STATE_MACHINE_FAILURE / DIVERGENCE / POLICY_INVALID / SHUTDOWN。
 */
public class DegradeSubcommand extends ControlSubcommand {

    public DegradeSubcommand(ControlCommand parent) {
        super("degrade", parent);
        children(new ReasonArgument());
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    @Override
    protected String additionalPermission() {
        return ControlCommand.PERM_OP;
    }

    // 非静态内部类：需访问外层 submitAndReply（instance 方法）；与 SetCommand.ValueArgument 同构
    class ReasonArgument extends ArgumentNode<String> {
        ReasonArgument() {
            super("reason", StringArgumentType.word());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (String v : new String[]{"MANUAL", "METRICS_FAILURE", "STATE_MACHINE_FAILURE", "DIVERGENCE", "POLICY_INVALID", "SHUTDOWN"}) {
                if (builder.getRemaining().isEmpty() || v.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            final String reason = context.getArgumentOrDefault(ReasonArgument.class, "MANUAL");
            final CommandSender sender = context.getSender();
            return submitAndReply(sender, PolicyCommand.fromModule(
                    "command:" + actorOf(sender), CommandAction.DEGRADE,
                    Map.of("reason", reason.trim().toUpperCase())));
        }
    }
}
