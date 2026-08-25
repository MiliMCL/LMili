package fun.bm.mili.lmili.observability.threading;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.threading.LmiliRunnable;
import org.slf4j.Logger;

/**
 * §C LMili Required 严格模式 —— BukkitScheduler hook。
 *
 * <h2>26.2+ 收紧策略</h2>
 * <p>历史（25.x）：plugin 可直接调 {@code BukkitScheduler.runTask}，LMili 只 log
 * 警告，不拦截。但导致观测失效、quota 失效、线程碎片。
 *
 * <p>26.2+：LMili 是<b>唯一</b>调度权威。本类提供三层防御：
 * <ol>
 *   <li><b>入口守卫</b>：plugin 调 {@code LMili.scheduler()} / {@code Threading.executor()}
 *       时，LMili 在调度前自动把 Runnable 包成 {@link LmiliRunnable}（强制携带
 *       PluginId）—— plugin 不主动 wrap 也行。</li>
 *   <li><b>native 调用检测</b>：plugin 直接调 {@code Bukkit.getScheduler().runTask} 时，
 *       LMili 通过 plugin runtime context 记入 {@code directBukkitCalls}，
 *       并通过 {@link #reportDirectBukkitCall(PluginId, Runnable)} 抛出
 *       {@link IllegalStateException}（严格模式）。</li>
 *   <li><b>plugin 加载校验</b>：plugin 必须声明 {@code lmili.json} +
 *       {@code "schedulerDelegation": "LMILI_REQUIRED"}；否则在
 *       {@code PluginIdentityBootstrap.handlePluginEnable} 被强制禁用。</li>
 * </ol>
 *
 * <h2>使用模式</h2>
 * <pre>{@code
 * // plugin 唯一合规入口：
 * LMili.scheduler().runTask(plugin, LmiliRunnable.wrap(myId, runnable));
 * // 或（自动 wrap）：
 * LMili.scheduler().runTask(plugin, runnable);
 * }</pre>
 *
 * @see LmiliRunnable
 * @see fun.bm.mili.lmili.api.LMili#scheduler()
 */
public final class BukkitSchedulerHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 全局严格模式开关（§C）。默认 {@code true}：
     * plugin 直接调 BukkitScheduler 被 hook 检测并抛
     * {@link IllegalStateException}。
     *
     * <p>历史值由配置项 {@code lmili.strictScheduler} 控制；admin 设置为
     * {@code false} 时降级为"仅警告，不抛"（兼容过渡期）。
     */
    private static volatile boolean strictMode = true;

    /** 当前调用栈是否来自 LMili 路径（线程局部）。 */
    private static final ThreadLocal<Boolean> INSIDE_LMILI_PATH = ThreadLocal.withInitial(() -> false);

    /** 已经过 LMili 注册的 task 计数（监控用） */
    private static final java.util.concurrent.atomic.AtomicLong lmiliSchedules = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong nativeBukkitCalls = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong nativeBukkitRejected = new java.util.concurrent.atomic.AtomicLong();

    private BukkitSchedulerHook() {}

    /** 入口函数：LMili 内部调用，标记当前线程为"正在走 LMili 路径" */
    public static void enterLmiliPath() {
        INSIDE_LMILI_PATH.set(true);
        lmiliSchedules.incrementAndGet();
    }

    /** 出口函数：LMili 内部调用，清除标记 */
    public static void exitLmiliPath() {
        INSIDE_LMILI_PATH.set(false);
    }

    /**
     * 报告 plugin 直接调 BukkitScheduler（绕过 LMili）。
     *
     * <p>strictMode = true 时抛 {@link IllegalStateException}；
     * strictMode = false 时仅 log + 计数（兼容过渡期）。
     *
     * <p>无论是否抛，{@code directBukkitCalls} 都 +1（plugin runtime ctx 里）。
     */
    public static void reportDirectBukkitCall(PluginId pluginId, Runnable task) {
        nativeBukkitCalls.incrementAndGet();

        // plugin runtime ctx 计数（让 /pluginid status 可见）
        try {
            fun.bm.mili.lmili.api.identity.PluginRuntimeContext ctx =
                    fun.bm.mili.lmili.api.identity.PluginRuntimeContext.forPluginId(pluginId);
            if (ctx != null) {
                ctx.observability().recordDirectBukkitCall();
            }
        } catch (Throwable ignored) {
            // §6.2 fail-safe
        }

        if (task instanceof LmiliRunnable) {
            // 来自 LMili 内部路径的 task 透传（合规）
            return;
        }
        if (!strictMode) {
            LOGGER.warn("[LMili][§C] plugin {} called BukkitScheduler directly (task={}); non-fatal (strict off).",
                    pluginId.value(), task.getClass().getName());
            return;
        }
        nativeBukkitRejected.incrementAndGet();
        LOGGER.error("[LMili][§C] plugin {} called BukkitScheduler.runTask directly (task={}). "
                + "Per §C LMili Required (strict mode), this is REJECTED. "
                + "Plugin MUST route through LMili.scheduler().runTask() with LmiliRunnable.wrap(myId, runnable).",
                pluginId.value(), task.getClass().getName());
        throw new IllegalStateException(
                "§C LMili Required: plugin " + pluginId.value() + " must route scheduling through "
                + "LMili.scheduler() instead of Bukkit.getScheduler(). See API_GUIDE §C.");
    }

    /** 全局开关（§C config "lmili.strictScheduler"） */
    public static void setStrictMode(boolean strict) { strictMode = strict; }
    public static boolean isStrictMode() { return strictMode; }

    /** 调试统计：当前已注册 LMili task 数 */
    public static long lmiliScheduleCount() { return lmiliSchedules.get(); }
    /** 调试统计：当前已被记录的 native BukkitScheduler 调用数 */
    public static long nativeBukkitCallCount() { return nativeBukkitCalls.get(); }
    /** 调试统计：当前被拒绝的 native BukkitScheduler 调用数（strict mode 下） */
    public static long nativeBukkitRejectedCount() { return nativeBukkitRejected.get(); }
}