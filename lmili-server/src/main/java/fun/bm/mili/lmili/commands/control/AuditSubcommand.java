package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.policy.PolicyAuditEntry;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

import java.util.List;

/**
 * /lmili control audit [limit] —— 最近审计条目（§6.1 审计；只读权限）。
 * 上限 50 条；条目不可变（D-04 派生记录）。
 */
public class AuditSubcommand extends ControlSubcommand {

    public AuditSubcommand(ControlCommand parent) {
        super("audit", parent);
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_READ;
    }

    @Override
    public boolean execute(@NotNull CommandContext context) {
        final CommandSender sender = context.getSender();
        final PolicyController policy = parent.policy();
        if (policy == null) {
            reply(sender, "[Control] Adaptive runtime not available", 255, 85, 0);
            return true;
        }
        try {
            final List<PolicyAuditEntry> entries = policy.auditEntries(50);
            if (entries.isEmpty()) {
                reply(sender, "[Control] No audit entries", 255, 255, 255);
                return true;
            }
            reply(sender, "=== Recent control audit (" + entries.size() + ") ===", 85, 255, 255);
            for (int i = entries.size() - 1; i >= 0; i--) {
                final PolicyAuditEntry e = entries.get(i);
                reply(sender, String.format("[v%d->%d] %s/%s %s %s -> %s",
                        e.beforeVersion(), e.afterVersion(), e.source(), e.actor(),
                        e.action() != null ? e.action().name() : "HINT",
                        e.params().isEmpty() ? "-" : e.params().toString(),
                        e.result()), 255, 255, 255);
            }
        } catch (Throwable t) {
            reply(sender, "[Control] audit error: " + t, 255, 0, 0);
        }
        return true;
    }
}
