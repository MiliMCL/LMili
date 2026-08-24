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
 * /lmili control throttle &lt;entity&gt; &lt;on|off&gt; —— 实体 tick 节流开关（§3.5；写权限）。
 *
 * <p>目标范围（entity|all）只影响同一 SET_THROTTLE 动作（all = entity 一并生效）；
 * 参数语义由服务端统一校验。
 */
public class ThrottleSubcommand extends ControlSubcommand {

    public ThrottleSubcommand(ControlCommand parent) {
        super("throttle", parent);
        children(new TargetArgument());
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    // 非静态内部类：需访问外层 submitAndReply（instance 方法）；与 SetCommand.ValueArgument 同构
    class TargetArgument extends ArgumentNode<String> {
        TargetArgument() {
            super("entity", StringArgumentType.word());
            children(new ToggleArgument());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            for (String v : new String[]{"entity", "all"}) {
                if (builder.getRemaining().isEmpty() || v.startsWith(builder.getRemaining())) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            // 只输入目标（缺开关）：回显用法，不提交
            return true;
        }
    }

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
            final String toggle = context.getArgumentOrDefault(ToggleArgument.class, "");
            final CommandSender sender = context.getSender();
            // entity / all 均映射到实体 tick 节流（当前受控面仅实体节流存在）
            return submitAndReply(sender, PolicyCommand.fromModule(
                    "command:" + actorOf(sender), CommandAction.SET_THROTTLE,
                    Map.of("enabled", toggle.trim().toLowerCase())));
        }
    }
}
