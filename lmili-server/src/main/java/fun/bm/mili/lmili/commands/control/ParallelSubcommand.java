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
 * /lmili control parallel &lt;on|off&gt; —— 并行 region tick 开关（§3.5；写权限）。
 * 布尔语义（on/off/true/false/1/0）由服务端 parseBool 校验。
 */
public class ParallelSubcommand extends ControlSubcommand {

    public ParallelSubcommand(ControlCommand parent) {
        super("parallel", parent);
        children(new ToggleArgument());
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    // 非静态内部类：需访问外层 submitAndReply（instance 方法）；与 SetCommand.ValueArgument 同构
    class ToggleArgument extends ArgumentNode<String> {
        ToggleArgument() {
            super("on|off", StringArgumentType.word());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (String v : new String[]{"on", "off"}) {
                if (builder.getRemaining().isEmpty() || v.startsWith(builder.getRemaining())) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            final String raw = context.getArgumentOrDefault(ToggleArgument.class, "");
            final CommandSender sender = context.getSender();
            return submitAndReply(sender, PolicyCommand.fromModule(
                    "command:" + actorOf(sender), CommandAction.SET_PARALLEL,
                    Map.of("enabled", raw.trim().toLowerCase())));
        }
    }
}
