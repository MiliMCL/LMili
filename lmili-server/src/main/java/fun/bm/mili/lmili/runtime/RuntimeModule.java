package fun.bm.mili.lmili.runtime;

import fun.bm.mili.lmili.runtime.control.MetricsController;
import fun.bm.mili.lmili.runtime.policy.RuntimePolicySnapshot;

/**
 * 模块生命周期契约 —— 所有需要参与自适应运行的现有/新模块实现此接口（ARCHITECTURE_AdaptiveRuntime.md §3.1）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #onRuntimeStart(MiliRuntime)} —— 启动回调，此时可安全获取 runtime 子控制器；</li>
 *   <li>{@link #onRuntimeShutdown(MiliRuntime)} —— 关闭回调，模块先于 Controller 关闭（先停消费者，再停生产者）；</li>
 *   <li>{@link #reportMetrics(MetricsController)} —— 每控制周期汇报（或由 MetricsController 主动 poll —— 二选一，禁止双报）；</li>
 *   <li>{@link #onPolicyChange(RuntimePolicySnapshot)} —— 策略快照生效回调；<strong>不得阻塞，不得回调 submitCommand</strong>（R3 锁序防护）。</li>
 * </ul>
 */
public interface RuntimeModule {

    /** 模块显示名（注册键） */
    String name();

    /** 启动回调：此时可安全获取 runtime 子控制器 */
    default void onRuntimeStart(MiliRuntime runtime) {
    }

    /** 关闭回调：模块先于 Controller 关闭（先停消费者，再停生产者） */
    default void onRuntimeShutdown(MiliRuntime runtime) {
    }

    /** 每控制周期汇报（或由 MetricsController 主动 poll —— 二选一，禁止双报） */
    default void reportMetrics(MetricsController metrics) {
    }

    /** 策略快照生效回调：模块在此应用策略（不得阻塞，不得回调 submitCommand） */
    default void onPolicyChange(RuntimePolicySnapshot policy) {
    }
}
