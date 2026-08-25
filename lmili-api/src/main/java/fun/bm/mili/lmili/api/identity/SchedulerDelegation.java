package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

/**
 * §C LMili Required 调度委托策略。
 *
 * <p>从 26.2 起，<b>仅保留 {@link #LMILI_REQUIRED}</b> 一个值：
 * LMili 是 server 上所有 plugin 的<b>唯一调度权威</b>。
 * plugin 不允许直接调 {@code BukkitScheduler.runTask(...)}，
 * 也不允许自己开 {@code ExecutorService} 跑工作负载。
 *
 * <h2>设计依据</h2>
 * <ul>
 *   <li>统一观测：所有 task 数字在 {@code /pluginid status <id>} 可见。</li>
 *   <li>Quota 生效：plugin 提交的 task 数受 ResourceQuota 限制。</li>
 *   <li>调度隔离：每 plugin 的虚拟线程池独立隔离。</li>
 *   <li>Capturability：所有 task 在 {@code CapturedLongTailEvents} 可追踪。</li>
 * </ul>
 *
 * <h2>历史</h2>
 * <p>早期版本还有 {@code LMILI_PREFERRED} 和 {@code BUKKIT_ONLY}（兼容层）；
 * 已删除——这些"逃生舱"导致 plugin 走原生 BukkitScheduler 后
 * 观测不到 / quota 失效 / 线程碎片，被 §18.9 "不破坏现有架构" 的过度保守原则
 * 长期误用。本次重构移除。
 */
public enum SchedulerDelegation {
    /** 唯一保留的值：plugin 必须把工作调度交给 LMili。 */
    LMILI_REQUIRED;

    /** 历史值映射（向后兼容旧的 lmili.json，但都映射到 LMILI_REQUIRED） */
    @NotNull
    public static SchedulerDelegation parse(@NotNull final String raw) {
        if (raw == null) return LMILI_REQUIRED;
        final String s = raw.trim();
        if (s.isEmpty()) return LMILI_REQUIRED;
        // 历史值：BUKKIT_ONLY / LMILI_PREFERRED / LMILI / 任意值
        // —— 都视为 LMILI_REQUIRED（统一管理）
        return LMILI_REQUIRED;
    }
}