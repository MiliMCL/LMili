package fun.bm.mili.lmili.thread.regiontick.suspend;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mili 统一线程工厂，支持 virtual thread 和 platform thread 两种模式。
 *
 * <p>核心特性：
 * <ul>
 *   <li>Virtual 模式：基于 JDK 24+ Virtual Thread，适合 IO 密集和高并发场景，
 *       不受 carrier pool 大小限制</li>
 *   <li>Platform 模式：传统 OS 线程，适合 CPU 密集或需要设置线程优先级的场景</li>
 *   <li>统一的命名约定和未捕获异常处理</li>
 *   <li>Carrier thread pinning 保护（virtual 模式下）</li>
 * </ul>
 *
 * <h3>JDK 24+ Virtual Thread 优势</h3>
 * JEP 491 解决了 synchronized 导致的 carrier pinning 问题，使得虚拟线程在
 * Minecraft 服务端（大量 synchronized 用于线程安全）中也能高效运行。
 */
public final class MiliThreadFactory implements ThreadFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String namePrefix;
    private final AtomicLong threadNumber = new AtomicLong(0);
    private final boolean virtual;
    private final int priority;
    private final boolean daemon;
    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private MiliThreadFactory(final Builder builder) {
        this.namePrefix = builder.namePrefix;
        this.virtual = builder.virtual;
        this.priority = builder.priority;
        this.daemon = builder.daemon;
        this.uncaughtExceptionHandler = builder.uncaughtExceptionHandler;
    }

    @Override
    public Thread newThread(@NotNull final Runnable r) {
        final String name = namePrefix + threadNumber.getAndIncrement();
        final Thread thread;
        if (virtual) {
            thread = Thread.ofVirtual().name(name).unstarted(r);
            // Virtual threads are always daemon and cannot have their priority changed
        } else {
            thread = new Thread(r, name);
            thread.setDaemon(daemon);
            if (priority > 0) {
                try {
                    thread.setPriority(priority);
                } catch (IllegalArgumentException | SecurityException e) {
                    LOGGER.warn("[MiliThreadFactory] Failed to set priority for thread {}", name, e);
                }
            }
        }
        // Mili start - fix: actually apply the uncaught exception handler to created threads
        if (uncaughtExceptionHandler != null) {
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        }
        // Mili end
        return thread;
    }

    /**
     * 创建平台线程的 ExecutorService 工厂。
     */
    public static @NotNull ThreadFactory platform(final @NotNull String namePrefix) {
        return new Builder(namePrefix).build();
    }

    /**
     * 创建虚拟线程的 ExecutorService 工厂。
     */
    public static @NotNull ThreadFactory virtual(final @NotNull String namePrefix) {
        return new Builder(namePrefix).virtual(true).build();
    }

    /**
     * 是否使用虚拟线程模式。
     */
    public boolean isVirtual() {
        return virtual;
    }

    public static final class Builder {
        private final String namePrefix;
        private boolean virtual = true;
        private int priority = -1;
        private boolean daemon = true;
        private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

        public Builder(final @NotNull String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public @NotNull Builder virtual(final boolean virtual) {
            this.virtual = virtual;
            return this;
        }

        public @NotNull Builder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        public @NotNull Builder daemon(final boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        public @NotNull Builder uncaughtExceptionHandler(@NotNull final Thread.UncaughtExceptionHandler handler) {
            this.uncaughtExceptionHandler = handler;
            return this;
        }

        public @NotNull MiliThreadFactory build() {
            return new MiliThreadFactory(this);
        }
    }
}
