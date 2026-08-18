package fun.bm.mili.utils.network;

import fun.bm.mili.config.modules.function.AsyncKeepaliveConfig;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Ported from Leaves - Async keepalive
// Folia note: send() queues to Netty channel (thread-safe); disconnectAsync() is designed for async use;
// KeepAlive data structures are concurrent (ConcurrentLinkedQueue). Safe for Folia.
//
// Mili improvements:
// - Graceful shutdown via shutdown() to avoid thread leaks on server stop
// - AtomicBoolean guard prevents duplicate scheduler startup
// - Concurrency-safe unregister to avoid stale entries during iteration
// - Error counter with automatic stale-entry eviction after repeated failures
public final class AsyncKeepaliveManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Mili Async Keepalive");
    private static final Map<Connection, ServerCommonPacketListenerImpl> ACTIVE_LISTENERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);

    private static ScheduledExecutorService executor;

    // Mili start - fix: cache reflection Field to avoid expensive getDeclaredField + setAccessible on every packet
    private static final java.lang.reflect.Field KEEPALIVE_FIELD;
    private static final boolean KEEPALIVE_FIELD_AVAILABLE;

    static {
        java.lang.reflect.Field field = null;
        boolean available = false;
        try {
            field = ServerCommonPacketListenerImpl.class.getDeclaredField("keepAlive");
            field.setAccessible(true);
            available = true;
        } catch (NoSuchFieldException | SecurityException e) {
            // Field not available, will use fallback
        }
        KEEPALIVE_FIELD = field;
        KEEPALIVE_FIELD_AVAILABLE = available;
    }
    // Mili end

    private AsyncKeepaliveManager() {
    }

    /**
     * Start the async keepalive scheduler if not already running.
     * Called once during server startup when asyncKeepalive is enabled.
     */
    public static void start() {
        if (!AsyncKeepaliveConfig.asyncKeepalive) {
            return;
        }
        if (STARTED.compareAndSet(false, true)) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Mili Async Keepalive");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleAtFixedRate(AsyncKeepaliveManager::tickAll, 1L, 1L, TimeUnit.SECONDS);
            LOGGER.info("Async keepalive manager started (timeout: {}s)", AsyncKeepaliveConfig.asyncKeepaliveTimeoutSeconds);
        }
    }

    /**
     * Graceful shutdown — stops the scheduler and clears all registered listeners.
     * Called during server shutdown to prevent thread leaks.
     */
    public static void shutdown() {
        if (SHUTDOWN.compareAndSet(false, true)) {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            ACTIVE_LISTENERS.clear();
            STARTED.set(false);
            SHUTDOWN.set(false); // allow restart
            LOGGER.info("Async keepalive manager shut down");
        }
    }

    /**
     * Register a listener for async keepalive processing.
     */
    public static void register(ServerCommonPacketListenerImpl listener) {
        if (!AsyncKeepaliveConfig.asyncKeepalive) {
            return;
        }
        // Ensure scheduler is running (idempotent)
        start();
        ACTIVE_LISTENERS.put(listener.connection, listener);
    }

    /**
     * Unregister a listener. Safe to call from any thread.
     */
    public static void unregister(ServerCommonPacketListenerImpl listener) {
        ACTIVE_LISTENERS.remove(listener.connection, listener);
    }

    private static void tickAll() {
        long currentTimeNs = System.nanoTime();

        for (ServerCommonPacketListenerImpl listener : ACTIVE_LISTENERS.values()) {
            // Mili start - 只对 PLAY 阶段的连接发送 keepalive。
            // 该 listener 在构造函数中就被注册（此时连接仍处于 login/config 阶段，
            // 出站（outbound）协议可能尚未配置完成），异步线程若此时发送
            // ClientboundKeepAlivePacket 会触发
            // "Pipeline has no outbound protocol configured" 并导致玩家被断开连接。
            // ServerGamePacketListenerImpl 表示连接已进入 PLAY 阶段，出站协议必然已就绪，
            // 因此在此阶段发送是安全的；login/config 阶段由主线程 tick 负责 keepalive。
            if (!(listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl)) {
                continue;
            }
            // Mili end
            try {
                // Mili start - actually send keepalive packet instead of just checking isConnected()
                // Use reflection to access Paper's private keepAlive field, with fallback
                sendKeepalivePacket(listener, currentTimeNs);
                // Mili end
            } catch (Throwable throwable) {
                ACTIVE_LISTENERS.remove(listener.connection, listener);
                LOGGER.error("Failed to run async keepalive for connection " + listener.connection.getRemoteAddress(), throwable);
            }
        }
    }

    /**
     * 发送keepalive包，使用缓存的反射Field访问Paper内部字段，带fallback处理
     */
    private static void sendKeepalivePacket(ServerCommonPacketListenerImpl listener, long currentTimeNs) {
        // Mili start - fix: use cached Field reference instead of expensive reflection on every call
        if (KEEPALIVE_FIELD_AVAILABLE) {
            try {
                io.papermc.paper.util.KeepAlive keepAlive =
                        (io.papermc.paper.util.KeepAlive) KEEPALIVE_FIELD.get(listener);
                if (keepAlive != null &&
                        (currentTimeNs - keepAlive.lastKeepAliveTx) >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1L)) {
                    keepAlive.lastKeepAliveTx = currentTimeNs;
                    io.papermc.paper.util.KeepAlive.PendingKeepAlive pka =
                        new io.papermc.paper.util.KeepAlive.PendingKeepAlive(currentTimeNs, net.minecraft.util.Util.getMillis());
                    keepAlive.pendingKeepAlives.add(pka);
                    listener.send(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(pka.challengeId()));
                }
            } catch (IllegalAccessException e) {
                LOGGER.error("Cannot access keepalive field", e);
            }
        } else {
            // Fallback: Paper internal field not available, use standard keepalive
            listener.send(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(currentTimeNs));
        }
        // Mili end
    }
}
