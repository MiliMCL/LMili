package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.policy.CommandAction;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

import java.util.Map;

/**
 * /lmili control restore —— 手动恢复（§6.1；写权限 + op）。
 */
public class RestoreSubcommand extends ControlSubcommand {

    public RestoreSubcommand(ControlCommand parent) {
        super("restore", parent);
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
                "command:" + actorOf(sender), CommandAction.RESTORE, Map.of()));
    }
}
