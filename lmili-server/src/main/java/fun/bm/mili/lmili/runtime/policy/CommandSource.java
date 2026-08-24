package fun.bm.mili.lmili.runtime.policy;

/**
 * 命令来源（ARCHITECTURE_AdaptiveRuntime.md §3.9 / §6.1 规则 7）。
 * CONSOLE/RCON 必须显式标记来源，审计与限频按来源独立计数。
 */
public enum CommandSource {
    PLAYER,
    CONSOLE,
    RCON,
    /** 内部模块（同样走受控通道，§6.1 规则 6） */
    INTERNAL_MODULE,
    /** 状态机自动迁移（不占用命令限频额度，但同样审计） */
    STATE_MACHINE
}
