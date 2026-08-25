package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 同步任务约束 —— 定义同步任务执行的安全边界。
 *
 * <p>同步任务虽然立即执行，但必须有安全边界防止卡顿：
 * <ul>
 *   <li><b>超时</b>：最大执行时间，超时返回失败</li>
 *   <li><b>死锁检测</b>：禁止嵌套同步任务</li>
 *   <li><b>上下文检查</b>：确保不在危险上下文中执行</li>
 *   <li><b>中断响应</b>：任务应响应 Thread.interrupt()</li>
 * </ul>
 *
 * <h3>默认约束</h3>
 * <p>默认配置适用于大多数快速查询场景：
 * <ul>
 *   <li>超时：5ms（非常短，强制任务快速返回）</li>
 *   <li>禁止嵌套：true</li>
 *   <li>允许 Bukkit API 访问：true（只读操作）</li>
 *   <li>允许 NMS 访问：false</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class SyncTaskConstraints {

    /**
     * 默认约束 - 适用于快速查询（5ms 超时）。
     */
    public static final SyncTaskConstraints DEFAULT = new Builder().build();

    /**
     * 宽松约束 - 适用于较复杂的同步操作（50ms 超时）。
     */
    public static final SyncTaskConstraints LENIENT = new Builder()
        .timeout(50, TimeUnit.MILLISECONDS)
        .allowNestedSync(false)
        .allowBukkitApi(true)
        .allowNmsAccess(false)
        .interruptSensitive(true)
        .build();

    /**
     * 严格约束 - 禁止任何可能阻塞的操作（1ms 超时）。
     */
    public static final SyncTaskConstraints STRICT = new Builder()
        .timeout(1, TimeUnit.MILLISECONDS)
        .allowNestedSync(false)
        .allowBukkitApi(false)
        .allowNmsAccess(false)
        .interruptSensitive(true)
        .build();

    private final long timeoutMs;
    private final boolean allowNestedSync;
    private final boolean allowBukkitApi;
    private final boolean allowNmsAccess;
    private final boolean interruptSensitive;
    private final String description;

    private SyncTaskConstraints(Builder builder) {
        this.timeoutMs = builder.timeoutMs;
        this.allowNestedSync = builder.allowNestedSync;
        this.allowBukkitApi = builder.allowBukkitApi;
        this.allowNmsAccess = builder.allowNmsAccess;
        this.interruptSensitive = builder.interruptSensitive;
        this.description = builder.description;
    }

    /**
     * 创建自定义约束构建器。
     *
     * @return 构建器
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    // ---- accessors ----

    public long timeoutMs() { return timeoutMs; }

    public boolean isAllowNestedSync() { return allowNestedSync; }

    public boolean isAllowBukkitApi() { return allowBukkitApi; }

    public boolean isAllowNmsAccess() { return allowNmsAccess; }

    public boolean isInterruptSensitive() { return interruptSensitive; }

    @NotNull
    public String description() { return description; }

    /**
     * 获取默认超时时间（用于公共 API）。
     *
     * @return 默认超时毫秒数
     */
    public static long defaultTimeoutMs() {
        return DEFAULT.timeoutMs;
    }

    @Override
    @NotNull
    public String toString() {
        return "SyncTaskConstraints{" +
            "timeout=" + timeoutMs + "ms" +
            ", nested=" + allowNestedSync +
            ", bukkitApi=" + allowBukkitApi +
            ", nms=" + allowNmsAccess +
            ", interrupt=" + interruptSensitive +
            ", desc='" + description + "'}";
    }

    /**
     * 同步任务约束构建器。
     */
    public static final class Builder {
        private long timeoutMs = 5; // 默认 5ms
        private boolean allowNestedSync = false;
        private boolean allowBukkitApi = true;
        private boolean allowNmsAccess = false;
        private boolean interruptSensitive = true;
        private String description = "custom";

        Builder() {}

        /**
         * 设置超时时间。
         *
         * <p><b>注意</b>：超时时间越短，对任务的要求越严格。
         * 建议保持在 1-50ms 范围内。
         *
         * @param timeout 超时时间
         * @param unit    时间单位
         * @return this
         */
        @NotNull
        public Builder timeout(long timeout, @NotNull TimeUnit unit) {
            this.timeoutMs = unit.toMillis(timeout);
            return this;
        }

        /**
         * 设置是否允许嵌套同步任务。
         *
         * <p><b>警告</b>：启用嵌套可能导致死锁。
         * 强烈建议保持禁用。
         *
         * @param allow true 表示允许
         * @return this
         */
        @NotNull
        public Builder allowNestedSync(boolean allow) {
            this.allowNestedSync = allow;
            return this;
        }

        /**
         * 设置是否允许访问 Bukkit API。
         *
         * <p>只读操作通常是安全的，但写操作可能触发事件或区块加载。
         *
         * @param allow true 表示允许
         * @return this
         */
        @NotNull
        public Builder allowBukkitApi(boolean allow) {
            this.allowBukkitApi = allow;
            return this;
        }

        /**
         * 设置是否允许访问 NMS（Net Minecraft Server）。
         *
         * <p><b>警告</b>：NMS 操作通常不安全，可能破坏服务器状态。
         *
         * @param allow true 表示允许
         * @return this
         */
        @NotNull
        public Builder allowNmsAccess(boolean allow) {
            this.allowNmsAccess = allow;
            return this;
        }

        /**
         * 设置是否对中断敏感。
         *
         * <p>如果启用，任务应定期检查 Thread.interrupted() 并提前返回。
         *
         * @param sensitive true 表示敏感
         * @return this
         */
        @NotNull
        public Builder interruptSensitive(boolean sensitive) {
            this.interruptSensitive = sensitive;
            return this;
        }

        /**
         * 设置约束描述（用于日志和调试）。
         *
         * @param description 描述
         * @return this
         */
        @NotNull
        public Builder description(@NotNull String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /**
         * 构建约束。
         *
         * @return 约束实例
         */
        @NotNull
        public SyncTaskConstraints build() {
            return new SyncTaskConstraints(this);
        }
    }
}
