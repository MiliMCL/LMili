package fun.bm.mili.lmili.runtime.policy;

import java.util.Map;

/**
 * 策略命令 DTO —— 命令/模块提交的"意图"，不是直接操作（ARCHITECTURE_AdaptiveRuntime.md §3.9）。
 * 由 PolicyController 校验后翻译为对子 Controller 的调用（D-03：唯一受控通道）。
 *
 * @param source      来源（审计与限频按来源独立计数）
 * @param action      动作
 * @param params      键值参数（值域由 PolicyController 服务端侧校验，不信任命令层钳制，§6.1 规则 2）
 * @param actor       发起者标识（玩家名 / CONSOLE / 模块名 / STATE_MACHINE）
 * @param issuedAtNanos 发起时刻（nanoTime）
 */
public record PolicyCommand(
        CommandSource source,
        CommandAction action,
        Map<String, String> params,
        String actor,
        long issuedAtNanos
) {

    /** 状态机来源命令的便捷构造（STATE_MACHINE 不占命令限频额度，但同样审计，§6.1 规则 6） */
    public static PolicyCommand fromStateMachine(CommandAction action, Map<String, String> params) {
        return new PolicyCommand(CommandSource.STATE_MACHINE, action, params, "STATE_MACHINE", System.nanoTime());
    }

    /** 内部模块来源命令的便捷构造 */
    public static PolicyCommand fromModule(String moduleName, CommandAction action, Map<String, String> params) {
        return new PolicyCommand(CommandSource.INTERNAL_MODULE, action, params, moduleName, System.nanoTime());
    }
}
