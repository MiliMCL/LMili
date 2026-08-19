package fun.bm.mili.utils.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步登录处理器 —— 确保登录阶段不会因新调度器而阻塞或超时。
 *
 * <p>主要功能：
 * <ul>
 *   <li>将登录阶段的耗时操作（如 Mojang 认证查询）异步化</li>
 *   <li>防止登录线程阻塞 netty IO 线程</li>
 *   <li>提供登录超时检测和清理</li>
 *   <li>兼容新调度器的异步执行模型</li>
 * </ul>
 *
 * <h3>使用方式：</h3>
 * <pre>{@code
 * // 在登录监听器中调用
 * AsyncLoginHandler.handleLoginAsync(connection, () -> {
 *     // 执行登录逻辑
 *     return gameProfile;
 * }).thenAccept(profile -> {
 *     // 登录成功处理
 * });
 * }</pre>
 */
public final class AsyncLoginHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Mili Async Login");
    private static final Map<Connection, PendingLogin> PENDING_LOGINS = new ConcurrentHashMap<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);

    private static ExecutorService executor;
    private static final long LOGIN_TIMEOUT_MS = 30000; // 30秒登录超时

    private AsyncLoginHandler() {}

    /**
     * 启动异步登录处理器。
     */
    public static void start() {
        if (STARTED.compareAndSet(false, true)) {
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "Mili Async Login Handler");
                thread.setDaemon(true);
                return thread;
            });
            LOGGER.info("Async login handler started (timeout: {}ms)", LOGIN_TIMEOUT_MS);
        }
    }

    /**
     * 关闭异步登录处理器。
     */
    public static void shutdown() {
        if (SHUTDOWN.compareAndSet(false, true)) {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            // 取消所有待处理的登录
            for (PendingLogin pending : PENDING_LOGINS.values()) {
                pending.future.completeExceptionally(
                    new RuntimeException("Server shutting down"));
            }
            PENDING_LOGINS.clear();
            STARTED.set(false);
            SHUTDOWN.set(false);
            LOGGER.info("Async login handler shut down");
        }
    }

    /**
     * 异步处理登录请求。
     *
     * @param connection 网络连接
     * @param loginTask  登录任务
     * @return 登录结果的 Future
     */
    public static CompletableFuture<GameProfile> handleLoginAsync(
            final Connection connection,
            final java.util.function.Supplier<GameProfile> loginTask
    ) {
        start();
        if (executor == null) {
            CompletableFuture<GameProfile> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Executor not available"));
            return future;
        }

        PendingLogin pending = new PendingLogin(connection);
        PENDING_LOGINS.put(connection, pending);

        CompletableFuture<GameProfile> future = CompletableFuture.supplyAsync(() -> {
            try {
                // 在异步线程中执行登录逻辑，不阻塞 netty IO 线程
                return loginTask.get();
            } catch (Exception e) {
                LOGGER.error("Async login failed for connection {}",
                    connection.getRemoteAddress(), e);
                throw e;
            }
        }, executor);

        future.whenComplete((profile, throwable) -> {
            // 清理待处理记录
            PENDING_LOGINS.remove(connection, pending);
            if (throwable != null) {
                LOGGER.warn("Login failed for connection {}: {}",
                    connection.getRemoteAddress(), throwable.getMessage());
            } else {
                LOGGER.info("Login successful for connection {} (profile: {})",
                    connection.getRemoteAddress(), profile.name());
            }
        });

        // 设置超时
        pending.future = future;
        scheduleTimeout(connection, future);

        return future;
    }

    /**
     * 调度登录超时检测。
     */
    private static void scheduleTimeout(Connection connection, CompletableFuture<GameProfile> future) {
        if (executor == null) return;
        executor.submit(() -> {
            try {
                Thread.sleep(LOGIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!future.isDone() && PENDING_LOGINS.containsKey(connection)) {
                future.completeExceptionally(
                    new java.util.concurrent.TimeoutException("Login timed out after " + LOGIN_TIMEOUT_MS + "ms"));
                PENDING_LOGINS.remove(connection);
                LOGGER.warn("Login timeout for connection {}", connection.getRemoteAddress());
                // 断开连接
                try {
                    connection.disconnect(net.minecraft.network.chat.Component.literal("Login timed out"));
                } catch (Exception e) {
                    LOGGER.error("Failed to disconnect timed-out connection", e);
                }
            }
        });
    }

    /**
     * 取消指定连接的登录处理。
     *
     * @param connection 网络连接
     */
    public static void cancelLogin(final Connection connection) {
        PendingLogin pending = PENDING_LOGINS.remove(connection);
        if (pending != null && pending.future != null && !pending.future.isDone()) {
            pending.future.completeExceptionally(
                new RuntimeException("Login cancelled"));
        }
    }

    /**
     * 获取待处理的登录数量。
     *
     * @return 待处理登录数
     */
    public static int pendingLoginCount() {
        return PENDING_LOGINS.size();
    }

    /**
     * 待处理的登录记录。
     */
    private static final class PendingLogin {
        final Connection connection;
        volatile CompletableFuture<GameProfile> future;

        PendingLogin(final Connection connection) {
            this.connection = connection;
        }
    }
}