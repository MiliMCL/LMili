package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.LMili;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度安全管理器 —— 负责插件调度权限的验证和 enforcement。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>权限验证</b>：检查插件是否有权提交调度任务</li>
 *   <li><b>配额 enforcement</b>：强制执行每个插件的资源配额</li>
 *   <li><b>线程创建拦截</b>：监控和限制插件自行创建线程</li>
 *   <li><b>审计日志</b>：记录安全相关事件</li>
 * </ul>
 *
 * <p>插件违反安全策略时，{@link SecurityException} 会被抛出，
 * 事件会被记录到 LMili 的 observability 系统中。
 *
 * @since 2.0.0
 */
public final class SchedulerSecurityManager {

    private final ConcurrentMap<PluginId, PluginSecurityState> pluginStates =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ThreadGroup> allowedThreadGroups =
            new ConcurrentHashMap<>();

    private final AtomicLong totalViolations = new AtomicLong();
    private final AtomicLong blockedCreations = new AtomicLong();

    private volatile boolean enforceMode = true; // true = 强制模式, false = 仅审计

    /**
     * 创建安全管理器。
     *
     * @param enforce true 表示强制拦截违规行为，false 表示仅记录审计日志
     */
    public SchedulerSecurityManager(boolean enforce) {
        this.enforceMode = enforce;
    }

    /**
     * 默认构造函数 - 启用强制模式。
     */
    public SchedulerSecurityManager() {
        this(true);
    }

    /**
     * 检查插件是否有权限提交调度任务。
     *
     * @param pluginId 插件 ID
     * @throws SecurityException 如果插件无权调度
     */
    public void checkPluginPermission(@NotNull PluginId pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");

        // 系统级插件始终允许
        if (pluginId.equals(LMili.SYSTEM_OWNER_ID)) return;

        PluginIdentityManager mgr = LMili.getPluginIdentityManager();
        PluginStatus status = mgr.getStatus(pluginId).orElse(null);

        if (status == null) {
            recordViolation(pluginId, "UNKNOWN_PLUGIN", "Plugin not registered");
            throw new SecurityException("Plugin " + pluginId.value() + " is not registered in LMili");
        }

        if (!status.canSchedule()) {
            recordViolation(pluginId, "SCHEDULE_DENIED",
                "Plugin status " + status + " does not allow scheduling");
            throw new SecurityException(
                "Plugin " + pluginId.value() + " (status=" + status + ") cannot submit scheduler tasks. "
                + "Plugin must be in ACTIVE or OBSERVE status to submit tasks.");
        }

        // 检查配额
        PluginRuntimeContext ctx = LMili.getRuntimeContext(pluginId);
        if (ctx != null) {
            ctx.requireScheduleSlot();
        }
    }

    /**
     * 检查插件是否可以创建线程。
     *
     * <p><b>核心安全规则</b>：插件不应自行创建线程。
     * 所有异步任务应通过 {@link UnifiedSchedulerAPI} 提交。
     * 仅 LMili 框架内部和受信任的系统代码可以创建线程。
     *
     * @param pluginId 请求创建线程的插件 ID
     * @param purpose 线程创建目的（用于审计日志）
     * @return true 如果允许创建
     */
    public boolean allowThreadCreation(@NotNull PluginId pluginId, @NotNull String purpose) {
        Objects.requireNonNull(pluginId, "pluginId");

        // 系统级始终允许
        if (pluginId.equals(LMili.SYSTEM_OWNER_ID)) return true;

        // 检查是否是 LMili 框架线程
        if (isFrameworkThread()) return true;

        // 插件自行创建线程 - 记录违规
        recordViolation(pluginId, "THREAD_CREATION",
            "Plugin attempted to create thread: " + purpose);

        if (enforceMode) {
            blockedCreations.incrementAndGet();
            return false;
        }
        return true; // 审计模式仍然允许
    }

    /**
     * 注册允许的线程组（LMili 框架内部使用）。
     *
     * @param groupName 线程组名称
     * @param group     线程组实例
     */
    public void registerAllowedThreadGroup(@NotNull String groupName, @NotNull ThreadGroup group) {
        allowedThreadGroups.put(groupName, group);
    }

    /**
     * 检查线程是否属于允许的线程组。
     *
     * @param thread 要检查的线程
     * @return true 如果线程属于允许的线程组
     */
    public boolean isAllowedThread(@NotNull Thread thread) {
        ThreadGroup group = thread.getThreadGroup();
        if (group == null) return false;
        return allowedThreadGroups.containsValue(group) || isFrameworkThread();
    }

    /**
     * 检查当前线程是否是 LMili 框架线程。
     *
     * @return true 如果是框架线程
     */
    public boolean isFrameworkThread() {
        String threadName = Thread.currentThread().getName();
        return threadName.startsWith("Mili-") ||
               threadName.startsWith("RegionTickPool-") ||
               threadName.startsWith("LMili-") ||
               threadName.contains("Scheduler");
    }

    /**
     * 获取指定插件的安全状态。
     *
     * @param pluginId 插件 ID
     * @return 安全状态，如果无记录则返回 null
     */
    public PluginSecurityState getSecurityState(@NotNull PluginId pluginId) {
        return pluginStates.get(pluginId);
    }

    /**
     * 获取总违规次数。
     *
     * @return 违规次数
     */
    public long getTotalViolations() {
        return totalViolations.get();
    }

    /**
     * 获取被阻止的线程创建次数。
     *
     * @return 阻止次数
     */
    public long getBlockedCreations() {
        return blockedCreations.get();
    }

    /**
     * 设置是否启用强制模式。
     *
     * @param enforce true 强制拦截, false 仅审计
     */
    public void setEnforceMode(boolean enforce) {
        this.enforceMode = enforce;
    }

    /**
     * 检查是否启用强制模式。
     *
     * @return true 如果启用强制模式
     */
    public boolean isEnforceMode() {
        return enforceMode;
    }

    /**
     * 重置指定插件的违规计数。
     *
     * @param pluginId 插件 ID
     */
    public void resetViolations(@NotNull PluginId pluginId) {
        pluginStates.remove(pluginId);
    }

    // ---- 内部方法 ----

    private void recordViolation(@NotNull PluginId pluginId,
                                  @NotNull String type,
                                  @NotNull String message) {
        totalViolations.incrementAndGet();

        PluginSecurityState state = pluginStates.computeIfAbsent(
            pluginId, PluginSecurityState::new);
        state.recordViolation(type, message);

        PluginRuntimeContext ctx = LMili.getRuntimeContext(pluginId);
        if (ctx != null) {
            ctx.observability().recordSecurityViolation(type, message);
        }
    }

    /**
     * 插件安全状态。
     */
    public static final class PluginSecurityState {
        private final PluginId pluginId;
        private final ConcurrentMap<String, AtomicLong> violationsByType =
                new ConcurrentHashMap<>();
        private volatile long lastViolationTime;

        PluginSecurityState(@NotNull PluginId pluginId) {
            this.pluginId = pluginId;
        }

        void recordViolation(@NotNull String type, @NotNull String message) {
            violationsByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
            lastViolationTime = System.currentTimeMillis();
        }

        @NotNull
        public PluginId pluginId() { return pluginId; }

        public long getViolationCount(@NotNull String type) {
            AtomicLong count = violationsByType.get(type);
            return count != null ? count.get() : 0;
        }

        public long getTotalViolations() {
            return violationsByType.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
        }

        public long getLastViolationTime() { return lastViolationTime; }

        @Override
        @NotNull
        public String toString() {
            return "PluginSecurityState{plugin=" + pluginId.value()
                + ", totalViolations=" + getTotalViolations()
                + ", lastViolation=" + lastViolationTime + "}";
        }
    }
}
