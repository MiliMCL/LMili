package fun.bm.mili.lmili.api.threading;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * plugin 线程规范 —— 描述"plugin 想要一个什么样的线程 / 池"。
 *
 * <p>不可变 record；通过 {@link Threading#spec(String)} 或
 * {@link Threading#spec(Object, String)} 构造。
 *
 * <h3>关键字段</h3>
 * <ul>
 *   <li>{@link #pluginId} —— 决定线程隔离与配额</li>
 *   <li>{@link #namePrefix} —— 线程名前缀（如 {@code "MyDB"} → {@code MyDB-Worker-1}）</li>
 *   <li>{@link #virtualThreads} —— 是否用 Java 21+ 虚拟线程（默认 true）</li>
 *   <li>{@link #uncaughtExceptionPolicy} —— 异常处理策略</li>
 * </ul>
 */
public record PluginThreadSpec(
        @Nullable Object pluginId,
        @NotNull String namePrefix,
        boolean virtualThreads,
        @NotNull UncaughtExceptionPolicy uncaughtExceptionPolicy,
        int priority
) {

    public PluginThreadSpec {
        Objects.requireNonNull(namePrefix, "namePrefix");
        if (namePrefix.isBlank()) {
            throw new IllegalArgumentException("namePrefix must not be blank");
        }
        Objects.requireNonNull(uncaughtExceptionPolicy, "uncaughtExceptionPolicy");
        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            priority = Thread.NORM_PRIORITY;
        }
    }

    /** 基础构造（virtualThreads=true, 默认异常策略） */
    public static PluginThreadSpec of(@NotNull String namePrefix) {
        return new PluginThreadSpec(null, namePrefix, true,
                UncaughtExceptionPolicy.LOG_AND_RECORD, Thread.NORM_PRIORITY);
    }

    /** 带 plugin id 的构造 */
    public static PluginThreadSpec of(@NotNull Object pluginId, @NotNull String namePrefix) {
        return new PluginThreadSpec(pluginId, namePrefix, true,
                UncaughtExceptionPolicy.LOG_AND_RECORD, Thread.NORM_PRIORITY);
    }

    /** 兼容 fun.bm.mili.lmili.api.identity.PluginId 的便捷重载 */
    public static PluginThreadSpec of(@NotNull PluginId pluginId, @NotNull String namePrefix) {
        return new PluginThreadSpec(pluginId, namePrefix, true,
                UncaughtExceptionPolicy.LOG_AND_RECORD, Thread.NORM_PRIORITY);
    }

    public PluginThreadSpec withNamePrefix(@NotNull String p) {
        return new PluginThreadSpec(pluginId, p, virtualThreads, uncaughtExceptionPolicy, priority);
    }

    public PluginThreadSpec withPlatformThreads() {
        return new PluginThreadSpec(pluginId, namePrefix, false, uncaughtExceptionPolicy, priority);
    }

    public PluginThreadSpec withVirtualThreads() {
        return new PluginThreadSpec(pluginId, namePrefix, true, uncaughtExceptionPolicy, priority);
    }

    public PluginThreadSpec withPolicy(@NotNull UncaughtExceptionPolicy p) {
        return new PluginThreadSpec(pluginId, namePrefix, virtualThreads, p, priority);
    }

    public PluginThreadSpec withPriority(int p) {
        return new PluginThreadSpec(pluginId, namePrefix, virtualThreads, uncaughtExceptionPolicy, p);
    }

    /** 未捕获异常策略。 */
    public enum UncaughtExceptionPolicy {
        /** 仅记录日志（写到 LMili observability） */
        LOG_ONLY,
        /** 记录日志 + 写入 §15 RuntimeMetrics 异常计数器 */
        LOG_AND_RECORD,
        /** 记录日志 + 终止该 plugin 的所有线程（极端场景） */
        LOG_AND_TERMINATE_PLUGIN_THREADS,
        /** 抛出到调用方（让 plugin 自己处理；适用于 PluginScheduler 调度场景） */
        RETHROW
    }
}
