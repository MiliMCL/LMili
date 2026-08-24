package fun.bm.mili.lmili.runtime.io;

/**
 * IO 状态探针 —— 任何 IO 后端可接入（本设计首个实现：{@link OLinearFlusherBridge}）
 * （ARCHITECTURE_AdaptiveRuntime.md §3.11）。
 */
public interface IOStateProbe {

    /**
     * 非阻塞采样；失败返回 null（调用方走 fail-safe，§6.2）。
     */
    IOState sample();
}
