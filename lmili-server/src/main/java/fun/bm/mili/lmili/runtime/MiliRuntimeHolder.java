package fun.bm.mili.lmili.runtime;

/**
 * 运行时单例持有器（double-checked volatile；幂等）（ARCHITECTURE_AdaptiveRuntime.md §3.13）。
 *
 * <p><strong>装配期回调不经过本类</strong>（RuntimeBootstrap 直接构造/注入 flusher 观察者，
 * 避免 RegionFormatConfig 加载时过早触发完整装配 —— 见 RuntimeBootstrap 注释）。
 */
public final class MiliRuntimeHolder {

    private static volatile MiliRuntime INSTANCE;

    private MiliRuntimeHolder() {
    }

    /** 获取当前实例（未创建返回 null；不创建） */
    public static MiliRuntime get() {
        return INSTANCE;
    }

    /**
     * 幂等获取或创建（double-checked volatile）。
     * 创建后立即 start()（幂等；控制周期随装配就绪后启动）。
     */
    public static MiliRuntime getOrCreate() {
        MiliRuntime rt = INSTANCE;
        if (rt != null) {
            return rt;
        }
        synchronized (MiliRuntimeHolder.class) {
            if (INSTANCE == null) {
                INSTANCE = new MiliRuntime();
                INSTANCE.start();
            }
            return INSTANCE;
        }
    }

    /** 显式替换（测试隔离用；仅测试调用） */
    public static void setForTest(MiliRuntime runtime) {
        INSTANCE = runtime;
    }

    /** 清空（测试隔离用；仅测试调用） */
    public static void resetForTest() {
        INSTANCE = null;
    }
}
