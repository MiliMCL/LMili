package fun.bm.mili.lmili.runtime.exec;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * fan-out 治理器（内聚 CPU_PRESSURE 的并行限制逻辑，供 ParallelExecutor 使用）（ARCHITECTURE_AdaptiveRuntime.md §3.15）。
 */
public final class FanoutController {

    private final AtomicInteger maxFanOut;
    private final AtomicBoolean pressureLimited;

    public FanoutController() {
        this.maxFanOut = new AtomicInteger(clamp(Runtime.getRuntime().availableProcessors(), 1, 64));
        this.pressureLimited = new AtomicBoolean(false);
    }

    /** 当前有效 fan-out（CPU_PRESSURE → max(1, maxFanOut/2)） */
    public int effectiveFanOut() {
        final int configured = maxFanOut.get();
        if (!pressureLimited.get()) {
            return configured;
        }
        return Math.max(1, configured / 2);
    }

    public void setPressureLimited(boolean on) {
        pressureLimited.set(on);
    }

    public boolean isPressureLimited() {
        return pressureLimited.get();
    }

    /** 设置最大 fan-out（钳制 [1, 64]，§6.1 服务端侧校验域） */
    public void setMaxFanOut(int n) {
        maxFanOut.set(clamp(n, 1, 64));
    }

    public int maxFanOut() {
        return maxFanOut.get();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
