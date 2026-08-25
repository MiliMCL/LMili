package fun.bm.mili.api;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.LMili;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插件线程监控器 —— 监控和限制插件自行创建线程的行为。
 *
 * <p><b>核心安全策略</b>：
 * <ul>
 *   <li>插件不得自行创建 Thread、ThreadGroup、ExecutorService</li>
 *   <li>所有异步任务必须通过 {@link UnifiedSchedulerAPI} 提交</li>
 *   <li>违规线程创建会被记录，强制模式下会被阻止</li>
 * </ul>
 *
 * <p>监控器通过以下方式工作：
 * <ol>
 *   <li>定期扫描 JVM 线程，识别不属于 LMili 框架的线程</li>
 *   <li>通过栈追踪识别线程创建者插件</li>
 *   <li>记录违规并可选地中断违规线程</li>
 * </ol>
 *
 * @since 2.0.0
 */
public final class PluginThreadMonitor {

    private final SchedulerSecurityManager securityManager;
    private final ConcurrentMap<Long, MonitoredThread> monitoredThreads =
            new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalDetections = new AtomicLong();
    private final AtomicLong totalInterruptions = new AtomicLong();

    private volatile long scanIntervalMs = 5000; // 默认 5 秒扫描一次
    private volatile boolean interruptViolatingThreads = false; // 是否中断违规线程

    private Thread monitorThread;

    /**
     * 创建插件线程监控器。
     *
     * @param securityMgr 安全管理器
     */
    public PluginThreadMonitor(@NotNull SchedulerSecurityManager securityMgr) {
        this.securityManager = Objects.requireNonNull(securityMgr, "securityManager");
    }

    /**
     * 启动线程监控。
     *
     * <p>监控器会定期扫描 JVM 线程，检测插件违规创建的线程。
     */
    public void startMonitoring() {
        if (running.getAndSet(true)) return;

        // 注册 LMili 框架线程组为允许
        ThreadGroup frameworkGroup = Thread.currentThread().getThreadGroup();
        securityManager.registerAllowedThreadGroup("LMili-Framework", frameworkGroup);

        monitorThread = new Thread(this::monitorLoop, "LMili-ThreadMonitor");
        monitorThread.setDaemon(true);
        monitorThread.setPriority(Thread.MIN_PRIORITY);
        monitorThread.start();
    }

    /**
     * 停止线程监控。
     */
    public void stopMonitoring() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    /**
     * 检查当前线程是否允许创建新线程。
     *
     * <p>此方法设计为从 Thread 构造函数或线程创建点调用。
     *
     * @return true 如果允许创建
     */
    public boolean allowThreadCreation() {
        // 框架线程始终允许
        if (securityManager.isFrameworkThread()) return true;

        // 检查是否是已监控的合法线程
        MonitoredThread current = monitoredThreads.get(Thread.currentThread().threadId());
        if (current != null && current.isAllowed) return true;

        // 尝试识别创建者插件
        PluginId creator = identifyCreatorPlugin();
        return securityManager.allowThreadCreation(creator, "Thread construction");
    }

    /**
     * 注册一个允许的线程（LMili 框架内部使用）。
     *
     * @param threadId 线程 ID
     * @param purpose 线程用途说明
     */
    public void registerAllowedThread(long threadId, @NotNull String purpose) {
        monitoredThreads.put(threadId, new MonitoredThread(threadId, purpose, true, null));
    }

    /**
     * 注销一个线程。
     *
     * @param threadId 线程 ID
     */
    public void unregisterThread(long threadId) {
        monitoredThreads.remove(threadId);
    }

    /**
     * 获取检测到的违规线程总数。
     *
     * @return 违规线程数
     */
    public long getTotalDetections() {
        return totalDetections.get();
    }

    /**
     * 获取被中断的线程总数。
     *
     * @return 中断数
     */
    public long getTotalInterruptions() {
        return totalInterruptions.get();
    }

    /**
     * 设置扫描间隔。
     *
     * @param intervalMs 扫描间隔（毫秒）
     */
    public void setScanIntervalMs(long intervalMs) {
        this.scanIntervalMs = Math.max(1000, intervalMs);
    }

    /**
     * 设置是否中断违规线程。
     *
     * @param interrupt true 表示中断违规线程
     */
    public void setInterruptViolatingThreads(boolean interrupt) {
        this.interruptViolatingThreads = interrupt;
    }

    /**
     * 手动触发一次线程扫描。
     *
     * @return 本次扫描发现的违规线程数
     */
    public int scanNow() {
        return performScan();
    }

    // ---- 内部方法 ----

    private void monitorLoop() {
        while (running.get()) {
            try {
                performScan();
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // 监控器自身异常不应影响服务器
            }
        }
    }

    private int performScan() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] allThreads = threadBean.dumpAllThreads(false, false);

        int violations = 0;

        for (ThreadInfo info : allThreads) {
            long threadId = info.getThreadId();

            // 跳过已监控的线程
            MonitoredThread monitored = monitoredThreads.get(threadId);
            if (monitored != null) {
                if (monitored.isAllowed) continue;
                // 已标记为违规的线程，检查是否还在运行
                if (!isThreadAlive(threadId)) {
                    monitoredThreads.remove(threadId);
                }
                continue;
            }

            // 检查是否是框架线程
            if (isFrameworkThread(info.getThreadName())) continue;

            // 通过栈追踪检查创建者
            StackTraceElement[] stack = info.getStackTrace();
            if (stack == null || stack.length == 0) continue;

            PluginId creator = identifyCreatorFromStack(stack);
            if (creator != null && !creator.equals(LMili.SYSTEM_OWNER_ID)) {
                // 发现插件创建的线程
                violations++;
                totalDetections.incrementAndGet();

                monitoredThreads.put(threadId, new MonitoredThread(
                    threadId, info.getThreadName(), false, creator));

                // 记录违规
                securityManager.allowThreadCreation(creator,
                    "Detected thread: " + info.getThreadName());

                // 可选：中断违规线程
                if (interruptViolatingThreads) {
                    interruptThread(threadId);
                }
            }
        }

        return violations;
    }

    private boolean isFrameworkThread(@NotNull String threadName) {
        return threadName.startsWith("Mili-") ||
               threadName.startsWith("RegionTickPool-") ||
               threadName.startsWith("LMili-") ||
               threadName.startsWith("Server-") ||
               threadName.startsWith("Paper-") ||
               threadName.startsWith("Craft-") ||
               threadName.contains("Scheduler") ||
               threadName.contains("Worker");
    }

    @Nullable
    private PluginId identifyCreatorPlugin() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            return identifyCreatorFromStack(stack);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private PluginId identifyCreatorFromStack(@NotNull StackTraceElement[] stack) {
        for (int i = 0; i < stack.length; i++) {
            String className = stack[i].getClassName();

            // 跳过框架类
            if (className.startsWith("fun.bm.mili.") ||
                className.startsWith("org.bukkit.") ||
                className.startsWith("java.") ||
                className.startsWith("jdk.") ||
                className.startsWith("sun.") ||
                className.startsWith("com.sun.")) {
                continue;
            }

            // 查找匹配的插件
            for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                if (plugin.isEnabled() &&
                    className.startsWith(plugin.getClass().getPackage().getName())) {
                    return LMili.getPluginIdentityManager()
                        .findByBukkitName(plugin.getName())
                        .map(identity -> identity.id())
                        .orElse(null);
                }
            }
        }
        return null;
    }

    private boolean isThreadAlive(long threadId) {
        // 简化检查：遍历所有线程
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.threadId() == threadId && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private void interruptThread(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.threadId() == threadId) {
                try {
                    t.interrupt();
                    totalInterruptions.incrementAndGet();
                } catch (SecurityException ignored) {
                    // 无权限中断
                }
                break;
            }
        }
    }

    /**
     * 被监控的线程信息。
     */
    private static final class MonitoredThread {
        final long threadId;
        final String name;
        final boolean isAllowed;
        final PluginId creatorPlugin;
        final long registeredAt;

        MonitoredThread(long threadId, String name, boolean isAllowed, PluginId creatorPlugin) {
            this.threadId = threadId;
            this.name = name;
            this.isAllowed = isAllowed;
            this.creatorPlugin = creatorPlugin;
            this.registeredAt = System.currentTimeMillis();
        }
    }
}
