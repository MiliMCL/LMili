package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.MiliRuntimeHolder;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.RootNode;

/**
 * /lmili control 命令树（ARCHITECTURE_AdaptiveRuntime.md §2.6 / §3.9 / D-03）。
 *
 * <p><strong>D-03 铁律</strong>：命令类零 Controller 引用 —— 本包<strong>不 import
 * {@code runtime.control} 的任何类</strong>；命令只构造 {@code PolicyCommand} 提交给
 * {@link PolicyController}（唯一受控通道）。本类仅在命令树注册时取得一次
 * PolicyController 接口引用（§6.1 规则 1：仅一次，不重复 get()）。
 *
 * <p>权限模型（§6.1）：读 = {@code lmili.control.read}；写 = {@code lmili.control.write}；
 * degrade/restore/rollback 额外要求 {@code minecraft.command.op}（PolicyController 服务端
 * 侧二次校验，命令层钳制不被信任）。
 */
public class ControlCommand extends RootNode {

    public static final String PERM_READ = "lmili.control.read";
    public static final String PERM_WRITE = "lmili.control.write";
    public static final String PERM_OP = "minecraft.command.op";

    private final PolicyController policyController;

    public ControlCommand() {
        super("control", PERM_READ);
        // 命令树注册时取得一次接口引用（§6.1 规则 1）；runtime 未装配 → null（fail-closed 提示）
        this.policyController = MiliRuntimeHolder.get() != null ? MiliRuntimeHolder.get().policy() : null;
        children(
                new StatusSubcommand(this),
                new SchedulerModeSubcommand(this),
                new IoWorkersSubcommand(this),
                new ParallelSubcommand(this),
                new ThrottleSubcommand(this),
                new PolicyRollbackSubcommand(this),
                new DegradeSubcommand(this),
                new RestoreSubcommand(this),
                new AuditSubcommand(this)
        );
    }

    /** 受控通道引用（可为 null = 运行期未装配；命令层据此 fail-closed） */
    public PolicyController policy() {
        return policyController;
    }

    public boolean isRuntimeAvailable() {
        return policyController != null;
    }

    @Override
    public void register() {
        super.register();
    }

    @Override
    public void unregister() {
        super.unregister();
    }

    /** 子命令权限门：读/写/op 组合（本包集中定义，子命令复用） */
    static boolean hasPermission(@NotNull org.bukkit.command.CommandSender sender, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        try {
            return sender.hasPermission(permission);
        } catch (Throwable t) {
            return false; // fail-closed
        }
    }
}
