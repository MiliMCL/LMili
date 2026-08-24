package fun.bm.mili.lmili.runtime.policy;

import java.util.Map;

/**
 * 审计条目（不可变）—— 每次 submitCommand（含被拒的）与插件 hint 裁决写入
 * （ARCHITECTURE_AdaptiveRuntime.md §6.1 规则 3：{ts, source, actor, action, params, result, version, before, after}）。
 */
public record PolicyAuditEntry(
        long tsNanos,
        CommandSource source,
        String actor,
        CommandAction action,
        Map<String, String> params,
        String result,
        long version,
        long beforeVersion,
        long afterVersion
) {
}
