package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.policy.CommandAction;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

import java.util.Map;

/**
 * /lmili control rollback —— 回滚到上一版本策略快照（§3.9 / D-04；写权限 + op）。
 *
 * <p>二次回滚（无上一版本）由服务端拒绝（NO_PREVIOUS_SNAPSHOT）；版本单调递增由
 * 受控通道保证（§6.4）。
 */
public class PolicyRollbackSubcommand extends ControlSubcommand {

    public PolicyRollbackSubcommand(ControlCommand parent) {
        super("rollback", parent);
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_WRITE;
    }

    @Override
    protected String additionalPermission() {
        return ControlCommand.PERM_OP;
    }

    @Override
    public boolean execute(@NotNull CommandContext context) {
        final CommandSender sender = context.getSender();
        return submitAndReply(sender, PolicyCommand.fromModule(
                "command:" + actorOf(sender), CommandAction.ROLLBACK, Map.of()));
    }
}
