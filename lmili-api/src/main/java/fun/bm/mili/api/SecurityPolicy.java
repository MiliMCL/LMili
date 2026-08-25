package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;

/**
 * LMili 调度安全策略配置。
 *
 * <p>定义插件调度的安全约束和行为：
 * <ul>
 *   <li><b>线程创建</b>：是否允许插件自行创建线程</li>
 *   <li><b>调度器创建</b>：是否允许插件创建 ExecutorService</li>
 *   <li><b>强制模式</b>：违规时抛出异常还是仅记录日志</li>
 *   <li><b>配额限制</b>：每个插件的最大并发任务数</li>
 * </ul>
 *
 * <h3>默认策略</h3>
 * <p>默认配置为最严格模式：
 * <ul>
 *   <li>禁止插件创建线程</li>
 *   <li>禁止插件创建调度器</li>
 *   <li>强制模式启用（违规抛出 SecurityException）</li>
 *   <li>每个插件最大并发任务数：16</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class SecurityPolicy {

    private final boolean allowPluginThreadCreation;
    private final boolean allowPluginExecutorCreation;
    private final boolean enforceMode;
    private final int maxConcurrentTasksPerPlugin;
    private final int maxQueuedTasksPerPlugin;
    private final long defaultTaskTimeoutMs;
    private final boolean auditLogging;

    private SecurityPolicy(Builder builder) {
        this.allowPluginThreadCreation = builder.allowPluginThreadCreation;
        this.allowPluginExecutorCreation = builder.allowPluginExecutorCreation;
        this.enforceMode = builder.enforceMode;
        this.maxConcurrentTasksPerPlugin = builder.maxConcurrentTasksPerPlugin;
        this.maxQueuedTasksPerPlugin = builder.maxQueuedTasksPerPlugin;
        this.defaultTaskTimeoutMs = builder.defaultTaskTimeoutMs;
        this.auditLogging = builder.auditLogging;
    }

    /**
     * 创建默认的严格安全策略。
     *
     * @return 默认安全策略
     */
    @NotNull
    public static SecurityPolicy strict() {
        return new Builder().build();
    }

    /**
     * 创建宽松的安全策略（仅审计模式）。
     *
     * <p>此模式不阻止违规行为，但会记录所有安全事件。
     * 适用于调试或向后兼容场景。
     *
     * @return 宽松安全策略
     */
    @NotNull
    public static SecurityPolicy permissive() {
        return new Builder()
            .allowPluginThreadCreation(true)
            .allowPluginExecutorCreation(true)
            .enforceMode(false)
            .maxConcurrentTasksPerPlugin(64)
            .maxQueuedTasksPerPlugin(256)
            .build();
    }

    /**
     * 创建构建器。
     *
     * @return 新的构建器实例
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    // ---- accessors ----

    public boolean isAllowPluginThreadCreation() { return allowPluginThreadCreation; }
    public boolean isAllowPluginExecutorCreation() { return allowPluginExecutorCreation; }
    public boolean isEnforceMode() { return enforceMode; }
    public int getMaxConcurrentTasksPerPlugin() { return maxConcurrentTasksPerPlugin; }
    public int getMaxQueuedTasksPerPlugin() { return maxQueuedTasksPerPlugin; }
    public long getDefaultTaskTimeoutMs() { return defaultTaskTimeoutMs; }
    public boolean isAuditLogging() { return auditLogging; }

    @Override
    @NotNull
    public String toString() {
        return "SecurityPolicy{"
            + "allowThreadCreation=" + allowPluginThreadCreation
            + ", allowExecutorCreation=" + allowPluginExecutorCreation
            + ", enforceMode=" + enforceMode
            + ", maxConcurrent=" + maxConcurrentTasksPerPlugin
            + ", maxQueued=" + maxQueuedTasksPerPlugin
            + ", timeoutMs=" + defaultTaskTimeoutMs
            + ", auditLogging=" + auditLogging
            + "}";
    }

    /**
     * 安全策略构建器。
     */
    public static final class Builder {
        private boolean allowPluginThreadCreation = false;
        private boolean allowPluginExecutorCreation = false;
        private boolean enforceMode = true;
        private int maxConcurrentTasksPerPlugin = 16;
        private int maxQueuedTasksPerPlugin = 64;
        private long defaultTaskTimeoutMs = 30000; // 30 秒
        private boolean auditLogging = true;

        Builder() {}

        /**
         * 设置是否允许插件创建线程。
         *
         * <p><b>警告</b>：启用此选项会降低安全性。
         * 插件创建的线程不受 LMili 调度管理，可能导致：
         * <ul>
         *   <li>线程泄漏（插件卸载后线程仍在运行）</li>
         *   <li>carrier thread pinning（virtual thread 模型被破坏）</li>
         *   <li>region 线程安全违规（在非 region 线程访问 region 数据）</li>
         * </ul>
         *
         * @param allow true 表示允许
         * @return this
         */
        @NotNull
        public Builder allowPluginThreadCreation(boolean allow) {
            this.allowPluginThreadCreation = allow;
            return this;
        }

        /**
         * 设置是否允许插件创建 ExecutorService。
         *
         * <p><b>警告</b>：启用此选项会降低安全性。
         *
         * @param allow true 表示允许
         * @return this
         */
        @NotNull
        public Builder allowPluginExecutorCreation(boolean allow) {
            this.allowPluginExecutorCreation = allow;
            return this;
        }

        /**
         * 设置是否启用强制模式。
         *
         * <p>强制模式下，安全违规会抛出 {@link SecurityException}。
         * 非强制模式下，仅记录审计日志。
         *
         * @param enforce true 表示启用强制模式
         * @return this
         */
        @NotNull
        public Builder enforceMode(boolean enforce) {
            this.enforceMode = enforce;
            return this;
        }

        /**
         * 设置每个插件的最大并发任务数。
         *
         * @param max 最大并发数
         * @return this
         */
        @NotNull
        public Builder maxConcurrentTasksPerPlugin(int max) {
            if (max < 1) throw new IllegalArgumentException("maxConcurrentTasksPerPlugin must be >= 1");
            this.maxConcurrentTasksPerPlugin = max;
            return this;
        }

        /**
         * 设置每个插件的最大排队任务数。
         *
         * @param max 最大排队数
         * @return this
         */
        @NotNull
        public Builder maxQueuedTasksPerPlugin(int max) {
            if (max < 1) throw new IllegalArgumentException("maxQueuedTasksPerPlugin must be >= 1");
            this.maxQueuedTasksPerPlugin = max;
            return this;
        }

        /**
         * 设置默认任务超时时间。
         *
         * @param timeoutMs 超时时间（毫秒）
         * @return this
         */
        @NotNull
        public Builder defaultTaskTimeoutMs(long timeoutMs) {
            if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must be >= 0");
            this.defaultTaskTimeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置是否启用审计日志。
         *
         * @param audit true 表示启用
         * @return this
         */
        @NotNull
        public Builder auditLogging(boolean audit) {
            this.auditLogging = audit;
            return this;
        }

        /**
         * 构建安全策略。
         *
         * @return 安全策略实例
         */
        @NotNull
        public SecurityPolicy build() {
            return new SecurityPolicy(this);
        }
    }
}
