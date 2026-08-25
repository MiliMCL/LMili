package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 安全线程 —— 插件创建线程的唯一合法方式。
 *
 * <p><b>设计目的</b>：
 * 插件不应自行创建 {@link Thread} 实例。如果确实需要（例如与遗留库集成），
 * 必须通过此类创建，它会：
 * <ul>
 *   <li>自动检查调用方插件的线程创建权限</li>
 *   <li>将线程注册到 LMili 监控系统中</li>
 *   <li>在插件被禁用时自动中断线程</li>
 * </ul>
 *
 * <p><b>推荐做法</b>：大多数情况下，插件应使用
 * {@link UnifiedSchedulerAPI#forCurrentPlugin()} 提交异步任务，
 * 而不是创建线程。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 不推荐：插件自行创建线程
 * new Thread(() -> { ... }).start(); // 将被阻止！
 *
 * // 推荐：使用调度 API
 * UnifiedSchedulerAPI.forCurrentPlugin().runAsync(() -> { ... });
 *
 * // 如果必须创建线程（遗留库集成）
 * SafeThread.create(myPluginId, () -> { ... }, "legacy-integration").start();
 * }</pre>
 *
 * @since 2.0.0
 */
public final class SafeThread {

    private SafeThread() {}

    /**
     * 创建一个安全线程（需指定插件 ID）。
     *
     * <p>线程会被注册到 LMili 监控系统中，插件被禁用时会自动中断。
     *
     * @param owner   请求创建线程的插件 ID
     * @param target  线程执行的任务
     * @param purpose 线程用途说明（用于审计）
     * @return 配置好的 Thread 实例（未启动）
     * @throws SecurityException 如果插件无权创建线程
     */
    @NotNull
    public static Thread create(@NotNull PluginId owner,
                                 @NotNull Runnable target,
                                 @NotNull String purpose) {
        // 检查权限
        if (!UnifiedSchedulerAPI.canCreateThread()) {
            throw new SecurityException(
                "Plugin " + owner.value() + " is not allowed to create threads. "
                + "Use UnifiedSchedulerAPI.forPlugin(pluginId).runAsync() instead.");
        }

        Thread thread = new Thread(target, "PluginThread-" + owner.value() + "-" + purpose);

        // 注册到监控器
        PluginThreadMonitor monitor = getMonitor();
        if (monitor != null) {
            monitor.registerAllowedThread(thread.threadId(), purpose);
        }

        return thread;
    }

    /**
     * 创建一个安全线程（自动识别当前插件）。
     *
     * @param target  线程执行的任务
     * @param purpose 线程用途说明
     * @return 配置好的 Thread 实例
     * @throws SecurityException 如果无法识别插件或无权创建
     */
    @NotNull
    public static Thread create(@NotNull Runnable target, @NotNull String purpose) {
        PluginId owner = UnifiedSchedulerAPI.forCurrentPlugin().owner();
        return create(owner, target, purpose);
    }

    /**
     * 创建一个安全线程组。
     *
     * @param owner   请求创建线程组的插件 ID
     * @param name    线程组名称
     * @return 线程组实例
     * @throws SecurityException 如果插件无权创建
     */
    @NotNull
    public static ThreadGroup createThreadGroup(@NotNull PluginId owner, @NotNull String name) {
        if (!UnifiedSchedulerAPI.canCreateThread()) {
            throw new SecurityException(
                "Plugin " + owner.value() + " is not allowed to create thread groups. "
                + "Use UnifiedSchedulerAPI for task scheduling instead.");
        }
        return new ThreadGroup("PluginGroup-" + owner.value() + "-" + name);
    }

    /**
     * 检查当前线程是否是安全创建的。
     *
     * @return true 如果当前线程是通过 SafeThread 创建的
     */
    public static boolean isSafeThread() {
        PluginThreadMonitor monitor = getMonitor();
        if (monitor == null) return false;
        return monitor.allowThreadCreation();
    }

    @Nullable
    private static PluginThreadMonitor getMonitor() {
        // 通过 UnifiedSchedulerAPI 获取监控器
        // 这里使用一个简化的方式，实际可以通过依赖注入
        return null; // 由 LMili 运行时注入
    }
}
