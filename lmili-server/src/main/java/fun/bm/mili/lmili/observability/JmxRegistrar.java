package fun.bm.mili.lmili.observability;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import org.slf4j.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;

/**
 * 把 LMili SchedulerMetrics 注册到 JVM MBeanServer —— 让 Spark / VisualVM / jconsole
 * 通过标准 JMX 协议读 LMili §15 全部指标。
 *
 * <p><b>为什么要走 JMX（不只是 LMili.schedulerMetrics() 静态 API）</b>：
 * <ul>
 *   <li>spark-paper plugin 的 {@code /spark jmx} 子命令 + VisualVM 可以"零修改"读 LMili 指标。</li>
 *   <li>JMX 是 JVM 标准协议，不引入 spark 特定耦合。</li>
 *   <li>LMili.schedulerMetrics() 是同源数据 —— 插件作者两条路径都能用。</li>
 * </ul>
 *
 * <p>注册时机：MiliRuntime.start() 完成之后。失败仅日志（fail-safe）。
 */
public final class JmxRegistrar {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile ObjectName REGISTERED_NAME;
    private static volatile SchedulerMetricsMXBean MXBEAN;

    private JmxRegistrar() {}

    /**
     * 注册 SchedulerMetrics MXBean。重复注册会先反注册旧的（幂等）。
     *
     * @return true 注册成功
     */
    public static synchronized boolean register(SchedulerMetricsMXBean bean) {
        if (bean == null) return false;
        try {
            unregister();
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(LMili.jmxObjectName());
            server.registerMBean(bean, name);
            REGISTERED_NAME = name;
            MXBEAN = bean;
            LOGGER.info("[LMili-JMX] registered SchedulerMetrics MXBean at {}", name);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[LMili-JMX] failed to register MXBean", t);
            return false;
        }
    }

    /**
     * 注册（直接从 SchedulerMetrics source 包一个 MXBean）。便捷重载。
     */
    public static synchronized boolean register(fun.bm.mili.lmili.api.observability.SchedulerMetrics source) {
        if (source == null) return false;
        // 如果 source 本身就是 MXBean，直接注册
        if (source instanceof SchedulerMetricsMXBean bean) {
            return register(bean);
        }
        return register(new JmxSchedulerMetricsMXBean(source));
    }

    public static synchronized boolean unregister() {
        if (REGISTERED_NAME == null) return true;
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            if (server.isRegistered(REGISTERED_NAME)) {
                server.unregisterMBean(REGISTERED_NAME);
            }
            REGISTERED_NAME = null;
            MXBEAN = null;
            LOGGER.info("[LMili-JMX] unregistered SchedulerMetrics MXBean");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[LMili-JMX] failed to unregister MXBean", t);
            return false;
        }
    }

    public static ObjectName registeredName() { return REGISTERED_NAME; }

    public static SchedulerMetricsMXBean mxbean() { return MXBEAN; }

    /** 测试可见：内部 helper */
    public static StandardMBean wrapAsStandard(SchedulerMetricsMXBean bean) {
        return new StandardMBean(bean, SchedulerMetricsMXBean.class, false);
    }
}
