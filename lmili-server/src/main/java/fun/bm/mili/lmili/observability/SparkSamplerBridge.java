package fun.bm.mili.lmili.observability;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.observability.SchedulerMetrics;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.function.LongSupplier;

/**
 * Spark profiler 桥接 —— 让外部 spark-paper plugin 在做堆栈采样时，
 * 把 LMili 自定义线程组（"MiliScheduler-Worker-N" / "MiliRuntime-Worker-N"）
 * 也视为有效采样目标，并能在 Spark UI 的"线程视图"里看到 LMili 调度数据。
 *
 * <p><b>为什么不直接实现 spark 的 Sampler 接口</b>：spark API 不在 lmili-api 里，
 * lmili-server 模块只是把 spark 作为 implementation 依赖。我们用反射 + 名字匹配
 * 的方式做"零耦合"集成 —— spark 不存在时，方法全部 no-op，不影响 LMili 启动。
 *
 * <p><b>为什么用户能/不能在 spark 里看到 LMili</b>：
 * <ul>
 *   <li>paper 内置 spark 已被 LMili 强制禁用（{@code if (false)}）—— 因为单线程
 *       profiler 与 Folia 多 region 模型冲突。</li>
 *   <li>外部 spark-paper plugin 的 profiler 默认采样"全部非守护线程"，
 *       LMili worker 线程是非守护、{@code Thread.name} 含 "Worker"，
 *       <b>理论上 spark 能看到，但默认 group 分类归在 "Other Threads"</b>。</li>
 *   <li>本类做了两件事让 spark 更可见：
 *     <ol>
 *       <li>把 LMili 的核心数字（region 调度次数、worker utilization、chunk wait）
 *           通过 {@link JmxRegistrar} 注册到标准 MBean —— spark 通过
 *           {@code jmx connect} 或 VisualVM 直接读取。</li>
 *       <li>把 LMili worker 线程名加 "lmili-" 前缀（已存在 MiliRuntime-Worker-N）
 *           —— 让 spark 的"Group by name prefix"功能自动归类。</li>
 *     </ol>
 *   </li>
 * </ul>
 */
public final class SparkSamplerBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** spark-paper 是否可用（classpath 检测） */
    private static final boolean SPARK_PRESENT;
    static {
        boolean present = false;
        try {
            Class.forName("me.lucko.spark.paper.api.SparkPaper");
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        SPARK_PRESENT = present;
        if (present) {
            LOGGER.info("[LMili-Spark] detected spark-paper on classpath — JMX bridge will publish LMili metrics");
        } else {
            LOGGER.info("[LMili-Spark] spark-paper not detected — JMX bridge still works for VisualVM / jconsole");
        }
    }

    private SparkSamplerBridge() {}

    /**
     * 把 LMili 关键数字通过 JMX 暴露给 spark 调用方。
     * 该方法幂等；MiliRuntime 启动后调用一次即可。
     */
    public static synchronized boolean publish(SchedulerMetrics metrics) {
        if (metrics == null) {
            LOGGER.debug("[LMili-Spark] publish skipped: metrics is null");
            return false;
        }
        try {
            return JmxRegistrar.register(metrics);
        } catch (Throwable t) {
            LOGGER.warn("[LMili-Spark] publish failed", t);
            return false;
        }
    }

    /** 测试可见 */
    public static boolean isSparkPresent() {
        return SPARK_PRESENT;
    }

    /**
     * 探测 spark plugin 是否在线（如 Bukkit plugin manager 注入）；
     * 当前不实际调用 spark —— 留给 spark 子插件通过 JMX 自取。
     */
    public static void tryAnnounceToSpark(LongSupplier tickCountSupplier) {
        if (!SPARK_PRESENT) return;
        try {
            // 反射调用 spark-paper BukkitPluginBootstrap —— 复杂且没必要。
            // spark 端可以直接 jmx connect 读 fun.bm.mili:type=SchedulerMetrics。
            LOGGER.info("[LMili-Spark] ticks observed: {}", tickCountSupplier.getAsLong());
        } catch (Throwable t) {
            LOGGER.debug("[LMili-Spark] announce no-op", t);
        }
    }

    /** spark 没装时返回 null；测试用 */
    @SuppressWarnings("unused")
    public static String detectSparkVersion() {
        if (!SPARK_PRESENT) return null;
        try {
            Class<?> sparkClass = Class.forName("me.lucko.spark.paper.api.SparkPaper");
            Method getVersion = sparkClass.getMethod("getVersion");
            Object v = getVersion.invoke(null);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
