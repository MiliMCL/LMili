package fun.bm.mili.lmili.runtime.io;

/**
 * 插件 flush 优先级申请（PluginId 接入点，D-22）（ARCHITECTURE_AdaptiveRuntime.md §3.11 / §4.4）。
 *
 * <p><strong>只是 hint，不代表生效</strong>：是否真的进入 HIGH 由 PolicyController 按
 * 权限/配额/当前压力态裁决（可接受、降级到 NORMAL、拒绝并审计）。插件不能决定最终等级。
 *
 * @param pluginId      插件标识（审计键）
 * @param regionId      目标 region
 * @param requested     申请等级（通常 HIGH）
 * @param reason        申请理由（审计用）
 * @param issuedAtNanos 申请时刻
 */
public record PluginHint(
        String pluginId,
        long regionId,
        FlushPriority requested,
        String reason,
        long issuedAtNanos
) {
}
