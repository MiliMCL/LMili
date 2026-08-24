package fun.bm.mili.lmili.runtime;

/**
 * 自适应运行时生命周期异常 —— 注册冲突、非法状态迁移等（ARCHITECTURE_AdaptiveRuntime.md §3.1）。
 *
 * <p>语义：由 {@link MiliRuntime#register(RuntimeModule)} 在重复 name 注册时抛出；
 * 也用于启动/关闭序列中的非法状态变更。
 */
public class RuntimeLifecycleException extends RuntimeException {

    public RuntimeLifecycleException(String message) {
        super(message);
    }

    public RuntimeLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
