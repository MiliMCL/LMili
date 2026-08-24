package fun.bm.mili.lmili.runtime.policy;

import org.jetbrains.annotations.Nullable;

/**
 * 命令结果（不可变）（ARCHITECTURE_AdaptiveRuntime.md §3.9）。
 */
public record CommandResult(
        boolean accepted,
        String message,
        @Nullable RuntimePolicySnapshot applied,
        @Nullable RuntimePolicySnapshot rolledBackTo
) {

    public static CommandResult accepted(String message, RuntimePolicySnapshot applied) {
        return new CommandResult(true, message, applied, null);
    }

    public static CommandResult rejected(String message) {
        return new CommandResult(false, message, null, null);
    }

    public static CommandResult rolledBack(String message, RuntimePolicySnapshot applied, RuntimePolicySnapshot rolledBackTo) {
        return new CommandResult(true, message, applied, rolledBackTo);
    }
}
