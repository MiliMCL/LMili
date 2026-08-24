package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.policy.CommandResult;
import fun.bm.mili.lmili.runtime.policy.CommandSource;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.command.LiteralNode;

/**
 * /lmili control 子命令基类（D-03：零 Controller 引用 —— 只构造 PolicyCommand 走受控通道）。
 */
public abstract class ControlSubcommand extends LiteralNode {

    protected final ControlCommand parent;

    protected ControlSubcommand(String name, ControlCommand parent) {
        super(name);
        this.parent = parent;
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        // 运行期未装配 → 拒绝（fail-closed）；权限由子命令 requiredPermission() 定制
        if (!parent.isRuntimeAvailable()) {
            return false;
        }
        return hasPermission(source.getSender());
    }

    /** 子命令要求的权限节点（默认读权限；写子命令覆盖为 PERM_WRITE，degrade/restore/rollback 另加 op） */
    protected boolean hasPermission(CommandSender sender) {
        if (!ControlCommand.hasPermission(sender, requiredPermission())) {
            return false;
        }
        final String additional = additionalPermission();
        return additional == null || ControlCommand.hasPermission(sender, additional);
    }

    protected String requiredPermission() {
        return ControlCommand.PERM_READ;
    }

    /** 附加权限（如 op）；null = 无附加要求 */
    @Nullable
    protected String additionalPermission() {
        return null;
    }

    // ================= 受控通道工具（命令层只构造 PolicyCommand） =================

    /** 提交并回显结果（同步；policyExecutor 单线程串行化，命令线程短暂等待） */
    protected boolean submitAndReply(CommandSender sender, PolicyCommand command) {
        final PolicyController policy = parent.policy();
        if (policy == null) {
            reply(sender, "[Control] Adaptive runtime not available (control plane disabled)", 255, 85, 0);
            return true;
        }
        try {
            final CommandResult result = policy.submitCommand(command);
            if (result.accepted()) {
                reply(sender, "[Control] " + result.message(), 0, 255, 0);
            } else {
                reply(sender, "[Control] REJECTED: " + result.message(), 255, 0, 0);
            }
        } catch (Throwable t) {
            reply(sender, "[Control] ERROR: " + t.getMessage(), 255, 0, 0);
        }
        return true;
    }

    protected void reply(CommandSender sender, String message, int r, int g, int b) {
        try {
            sender.sendMessage(Component.text(message).color(TextColor.color(r, g, b)));
        } catch (Throwable ignored) {
            // 发送失败静默（命令路径不允许反向破坏）
        }
    }

    // ================= 来源解析（审计） =================

    protected static CommandSource sourceOf(CommandSender sender) {
        if (sender instanceof Player) {
            return CommandSource.PLAYER;
        }
        if (sender instanceof RemoteConsoleCommandSender) {
            return CommandSource.RCON;
        }
        return CommandSource.CONSOLE;
    }

    protected static String actorOf(CommandSender sender) {
        try {
            final String name = sender.getName();
            return name == null || name.isBlank() ? "CONSOLE" : name;
        } catch (Throwable t) {
            return "CONSOLE";
        }
    }
}
