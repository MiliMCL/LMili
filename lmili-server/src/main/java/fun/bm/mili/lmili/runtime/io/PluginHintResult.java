package fun.bm.mili.lmili.runtime.io;

import org.jetbrains.annotations.NotNull;

/**
 * 插件 hint 裁决结果（不可变；获准 / 降级 / 拒绝 均回调插件并记审计）（ARCHITECTURE_AdaptiveRuntime.md §4.4）。
 */
public record PluginHintResult(
        @NotNull PluginHint hint,
        boolean accepted,
        @NotNull FlushPriority effectivePriority,
        @NotNull String reason,
        long adjudicatedAtNanos
) {

    public static PluginHintResult granted(PluginHint hint, FlushPriority effective) {
        return new PluginHintResult(hint, true, effective, "GRANTED", System.nanoTime());
    }

    public static PluginHintResult downgraded(PluginHint hint, FlushPriority effective, String reason) {
        return new PluginHintResult(hint, false, effective, reason, System.nanoTime());
    }

    public static PluginHintResult rejected(PluginHint hint, String reason) {
        return new PluginHintResult(hint, false, hint.requested(), reason, System.nanoTime());
    }
}
