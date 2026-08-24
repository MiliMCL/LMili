package fun.bm.mili.lmili.commands;

import fun.bm.mili.lmili.commands.bar.BarCommand;
import fun.bm.mili.lmili.commands.control.ControlCommand;

public class CommandRegister {
    /**
     * Register commands after config loading
     * This method is called after system configuration is fully loaded,
     * used to register commands that depend on complete configuration
     */
    public static void register() {
        new BarCommand().register();
        // AdaptiveRuntime Phase 6：/lmili control 命令树（§3.9 / §6.1）。
        // 命令树注册时取一次 PolicyController 接口引用（D-03：命令层零 Controller 引用）；
        // runtime 未装配时子命令 requires() 返回 false（fail-closed）。
        try {
            new ControlCommand().register();
        } catch (Throwable t) {
            // 命令树注册失败不允许阻断服务器启动（§6.2 规则 10）
            com.mojang.logging.LogUtils.getLogger().warn("[CommandRegister] Failed to register /lmili control tree", t);
        }
    }
}
