package fun.bm.mili.lmili.api.threading;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;

/**
 * <h1>LMili 任务契约（plugin 必须实现）</h1>
 *
 * <p>所有要交给 LMili 调度的任务（Runnable / Callable）必须实现本接口，
 * 或用 {@link LmiliRunnable#wrap(PluginId, Runnable)} 包装普通 Runnable。
 *
 * <p><b>强约束</b>（§C LMili Required）：server 启动时若 plugin 没声明
 * {@code "schedulerDelegation": "LMILI"} 或者运行时直接把原生
 * {@code BukkitScheduler.runTask} 调过来，LMili 会在 BukkitScheduler hook
 * 里抛 {@link IllegalStateException}（plugin 的非 LMili 路径被禁用）。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 方式 1：直接实现
 * public class MyTick implements LmiliRunnable {
 *     @Override public PluginId lmiliOwner() { return MY_ID; }
 *     @Override public void run() { doWork(); }
 * }
 * LMili.scheduler().runTask(plugin, new MyTick());
 *
 * // 方式 2：包装
 * LMili.scheduler().runTask(plugin,
 *     LmiliRunnable.wrap(MY_ID, () -> doWork()));
 * }</pre>
 *
 * <h2>为什么不直接用 Runnable</h2>
 * <p>Runnable 不能携带 PluginId 标识，LMili 在 hook BukkitScheduler 时无法
 * 判断一个 task 是从 LMili 路径来的还是 plugin 直接 {@code Bukkit.runTask} 来的。
 * LmiliRunnable 把 PluginId 作为必填字段，hook 层据此判断并拒绝非 LMili 路径。
 */
public interface LmiliRunnable extends Runnable {

    /** 任务所属 plugin 的 PluginId（必填；hook 据此判断 task 来源） */
    @NotNull
    PluginId lmiliOwner();

    /**
     * 包装普通 Runnable 为 LmiliRunnable。
     *
     * @param owner 任务所属 plugin（必填）
     * @param inner 实际工作
     */
    @NotNull
    static LmiliRunnable wrap(@NotNull PluginId owner, @NotNull Runnable inner) {
        if (owner == null) throw new IllegalArgumentException("owner must not be null");
        if (inner == null) throw new IllegalArgumentException("inner must not be null");
        return new LmiliRunnable() {
            @Override public @NotNull PluginId lmiliOwner() { return owner; }
            @Override public void run() { inner.run(); }
        };
    }

    /**
     * 包装 Callable 为 LmiliRunnable-like（LMili.scheduler().call(...) 使用）。
     */
    @NotNull
    static <V> LmiliCallable<V> wrap(@NotNull PluginId owner, @NotNull Callable<V> inner) {
        if (owner == null) throw new IllegalArgumentException("owner must not be null");
        if (inner == null) throw new IllegalArgumentException("inner must not be null");
        return new LmiliCallable<V>() {
            @Override public @NotNull PluginId lmiliOwner() { return owner; }
            @Override public V call() throws Exception { return inner.call(); }
        };
    }

    /**
     * 标记 Callable 为 LMili 任务。功能同 {@link LmiliRunnable} 但带返回值。
     */
    interface LmiliCallable<V> extends Callable<V> {
        @NotNull PluginId lmiliOwner();
    }
}