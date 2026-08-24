package fun.bm.mili.lmili.observability.threading;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.threading.PluginCancellable;
import fun.bm.mili.lmili.api.threading.PluginExecutor;
import fun.bm.mili.lmili.api.threading.PluginRegionScheduler;
import fun.bm.mili.lmili.api.threading.PluginRegionTask;
import fun.bm.mili.lmili.api.threading.PluginScheduler;
import fun.bm.mili.lmili.api.threading.PluginThreadSpec;
import fun.bm.mili.lmili.api.threading.StructuredConcurrency;
import fun.bm.mili.lmili.api.threading.Threading;
import fun.bm.mili.lmili.api.threading.ThreadingBackend;
import fun.bm.mili.lmili.thread.scheduler.MiliSchedulerHolder;
import fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import fun.bm.mili.lmili.thread.scheduler.execute.VirtualThreadPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threading backend server-side impl.
 *
 * <p>Reuses existing VirtualThreadPool + MiliSchedulerHolder + PluginSchedulerBridge
 * (§18.1 reuse-first principle).
 */
public final class LmiliThreadingBackend implements ThreadingBackend {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, PluginExecutorImpl> executorsByKey = new ConcurrentHashMap<>();
    private final Map<String, PluginSchedulerImpl> schedulersByKey = new ConcurrentHashMap<>();

    private static final VirtualThreadPool REGION_DELAY_POOL = new VirtualThreadPool("PluginRegionScheduler-Delay");

    public LmiliThreadingBackend() {
        Threading.installBackend(this);
        LOGGER.info("[Threading] backend installed (reuses VirtualThreadPool + MiliSchedulerHolder)");
    }

    @Override
    public @NotNull PluginExecutor executor(@NotNull PluginThreadSpec spec) {
        String key = keyOf(spec);
        return executorsByKey.computeIfAbsent(key, k -> createExecutor(spec));
    }

    @Override
    public @NotNull PluginScheduler scheduler(@NotNull PluginThreadSpec spec) {
        String key = keyOf(spec);
        return schedulersByKey.computeIfAbsent(key, k -> createScheduler(spec));
    }

    @Override
    public @NotNull StructuredConcurrency scope() {
        try {
            return new JdkStructuredScope();
        } catch (Throwable t) {
            return new LatchedStructuredScope();
        }
    }

    @Override
    public @Nullable Object miliScheduler() {
        return MiliSchedulerHolder.get();
    }

    @Override
    public @NotNull PluginRegionScheduler regionScheduler() {
        return new ServerPluginRegionScheduler();
    }

    @Override
    public @NotNull String version() {
        return "lmili-1.0";
    }

    @Override
    public @Nullable Object snapshotMetrics() {
        return LMili.schedulerMetrics();
    }

    @Override
    public @Nullable Object longTailEvents() {
        return LMili.longTailEvents();
    }

    @Override
    public void shutdown() {
        LOGGER.info("[Threading] shutting down (executors={}, schedulers={})",
                executorsByKey.size(), schedulersByKey.size());
        for (PluginExecutorImpl e : executorsByKey.values()) {
            try { e.shutdown(); } catch (Throwable ignored) {}
        }
        for (PluginSchedulerImpl s : schedulersByKey.values()) {
            try { s.shutdown(); } catch (Throwable ignored) {}
        }
        executorsByKey.clear();
        schedulersByKey.clear();
        try { REGION_DELAY_POOL.shutdown(); } catch (Throwable ignored) {}
    }

    private PluginExecutorImpl createExecutor(PluginThreadSpec spec) {
        VirtualThreadPool pool = new VirtualThreadPool(spec.namePrefix());
        return new PluginExecutorImpl(spec, pool);
    }

    private PluginSchedulerImpl createScheduler(PluginThreadSpec spec) {
        VirtualThreadPool pool = new VirtualThreadPool(spec.namePrefix() + "-Scheduled");
        return new PluginSchedulerImpl(spec, pool);
    }

    private static String keyOf(PluginThreadSpec spec) {
        Object pid = spec.pluginId();
        return (pid == null ? "global" : String.valueOf(pid)) + "::" + spec.namePrefix();
    }

    private static final class PluginExecutorImpl implements PluginExecutor {
        private final PluginThreadSpec spec;
        private final VirtualThreadPool pool;
        private final AtomicInteger active = new AtomicInteger(0);

        PluginExecutorImpl(PluginThreadSpec spec, VirtualThreadPool pool) {
            this.spec = spec;
            this.pool = pool;
        }

        @Override
        public @NotNull Future<?> submit(@NotNull Runnable task) {
            active.incrementAndGet();
            return pool.submit(wrap(task));
        }

        @Override
        public @NotNull <T> Future<T> submit(@NotNull Callable<T> task) {
            active.incrementAndGet();
            return pool.submit(wrapCallable(task));
        }

        @Override
        public @NotNull List<Future<?>> submitAll(@NotNull Collection<? extends Runnable> tasks) {
            return tasks.stream().map(this::submit).toList();
        }

        @Override
        public void shutdown() { pool.shutdown(); }

        @Override
        public boolean isShutdown() { return pool.toString().contains("shutdown"); }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            while (active.get() > 0 && System.nanoTime() < end) {
                Thread.sleep(1);
            }
            return active.get() == 0;
        }

        @Override
        public int activeThreadCount() { return active.get(); }

        @Override
        public boolean isVirtual() { return spec.virtualThreads(); }

        private Runnable wrap(Runnable task) {
            return () -> {
                try { task.run(); }
                catch (Throwable t) { handleUncaught(t); }
                finally { active.decrementAndGet(); }
            };
        }

        private <T> Callable<T> wrapCallable(Callable<T> task) {
            return () -> {
                try { return task.call(); }
                catch (Throwable t) { handleUncaught(t); throw t; }
                finally { active.decrementAndGet(); }
            };
        }

        private void handleUncaught(Throwable t) {
            switch (spec.uncaughtExceptionPolicy()) {
                case LOG_ONLY -> LOGGER.warn("[Threading/{}] uncaught", spec.namePrefix(), t);
                case LOG_AND_RECORD -> {
                    LOGGER.error("[Threading/{}] uncaught", spec.namePrefix(), t);
                    try { LMili.schedulerCapture(); } catch (Throwable ignored) {}
                }
                case LOG_AND_TERMINATE_PLUGIN_THREADS -> {
                    LOGGER.error("[Threading/{}] uncaught, terminating", spec.namePrefix(), t);
                    shutdown();
                }
                case RETHROW -> {
                    if (t instanceof RuntimeException re) throw re;
                    throw new RuntimeException(t);
                }
            }
        }
    }

    private static final class PluginSchedulerImpl implements PluginScheduler {
        private final PluginThreadSpec spec;
        private final VirtualThreadPool pool;
        private final AtomicInteger active = new AtomicInteger(0);

        PluginSchedulerImpl(PluginThreadSpec spec, VirtualThreadPool pool) {
            this.spec = spec;
            this.pool = pool;
        }

        @Override
        public @NotNull ScheduledFuture<?> schedule(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
            active.incrementAndGet();
            ScheduledFuture<?> f = pool.schedule(wrap(task), delay, unit);
            return new TrackedScheduledFuture<>(f, active);
        }

        @Override
        public @NotNull <V> ScheduledFuture<V> schedule(@NotNull Callable<V> task, long delay, @NotNull TimeUnit unit) {
            active.incrementAndGet();
            ScheduledFuture<V> f = pool.submit(task);
            return new TrackedScheduledFuture<>(f, active);
        }

        @Override
        public @NotNull PluginCancellable scheduleWithFixedDelay(@NotNull Runnable task, long period, @NotNull TimeUnit unit) {
            VirtualThreadPool.Cancellable c = pool.scheduleWithFixedDelay(wrap(task), period, unit);
            return new PluginCancellable() {
                @Override public void cancel() { c.cancel(); }
                @Override public boolean isCancelled() { return c.isCancelled(); }
            };
        }

        @Override
        public @NotNull PluginCancellable scheduleAtFixedRate(@NotNull Runnable task, long period, @NotNull TimeUnit unit) {
            VirtualThreadPool.Cancellable c = pool.scheduleAtFixedRate(wrap(task), period, unit);
            return new PluginCancellable() {
                @Override public void cancel() { c.cancel(); }
                @Override public boolean isCancelled() { return c.isCancelled(); }
            };
        }

        @Override
        public void shutdown() { pool.shutdown(); }

        @Override
        public boolean isVirtual() { return spec.virtualThreads(); }

        @Override
        public int activeTaskCount() { return active.get(); }

        private Runnable wrap(Runnable task) {
            return () -> {
                try { task.run(); }
                catch (Throwable t) { LOGGER.error("[Threading-sched/{}] uncaught", spec.namePrefix(), t); }
                finally { active.decrementAndGet(); }
            };
        }
    }

    private static final class TrackedScheduledFuture<T> implements ScheduledFuture<T> {
        private final ScheduledFuture<T> delegate;
        private final AtomicInteger active;
        private final java.util.concurrent.atomic.AtomicBoolean decremented = new java.util.concurrent.atomic.AtomicBoolean(false);

        TrackedScheduledFuture(ScheduledFuture<T> delegate, AtomicInteger active) {
            this.delegate = delegate;
            this.active = active;
        }

        @Override public long getDelay(TimeUnit u) { return delegate.getDelay(u); }
        @Override public int compareTo(java.util.concurrent.Delayed o) { return delegate.compareTo(o); }
        @Override public boolean cancel(boolean a) {
            boolean r = delegate.cancel(a);
            if (r && decremented.compareAndSet(false, true)) active.decrementAndGet();
            return r;
        }
        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public T get() throws java.util.concurrent.ExecutionException, InterruptedException {
            try { return delegate.get(); }
            finally { if (decremented.compareAndSet(false, true)) active.decrementAndGet(); }
        }
        @Override public T get(long t, TimeUnit u) throws java.util.concurrent.ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
            try { return delegate.get(t, u); }
            finally { if (decremented.compareAndSet(false, true)) active.decrementAndGet(); }
        }
    }

    private static final class JdkStructuredScope implements StructuredConcurrency {
        private final Object scope;

        JdkStructuredScope() {
            try {
                Class<?> sofClass = Class.forName("java.util.concurrent.StructuredTaskScope$ShutdownOnFailure");
                java.lang.reflect.Constructor<?> ctor = sofClass.getDeclaredConstructor();
                this.scope = ctor.newInstance();
            } catch (Throwable t) {
                throw new UnsupportedOperationException("JDK StructuredTaskScope not available", t);
            }
        }

        @Override
        public @NotNull <T> SubTask<T> fork(@NotNull Callable<T> task) {
            try {
                java.lang.reflect.Method fork = scope.getClass().getMethod("fork", Callable.class);
                Object subtask = fork.invoke(scope, task);
                return new JdkSubTask<>(subtask);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        public void join() throws Exception {
            try {
                java.lang.reflect.Method join = scope.getClass().getMethod("join");
                join.invoke(scope);
                java.lang.reflect.Method throwIfFailed = scope.getClass().getMethod("throwIfFailed");
                throwIfFailed.invoke(scope);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw new RuntimeException(c);
            }
        }

        @Override
        public void close() {
            try {
                java.lang.reflect.Method close = scope.getClass().getMethod("close");
                close.invoke(scope);
            } catch (Throwable ignored) {}
        }
    }

    private static final class JdkSubTask<T> implements SubTask<T> {
        private final Object delegate;

        JdkSubTask(Object delegate) { this.delegate = delegate; }

        @Override
        public T get() throws Exception {
            try {
                java.lang.reflect.Method get = delegate.getClass().getMethod("get");
                @SuppressWarnings("unchecked")
                T v = (T) get.invoke(delegate);
                return v;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw new RuntimeException(c);
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws Exception {
            try {
                java.lang.reflect.Method get = delegate.getClass().getMethod("get", long.class, TimeUnit.class);
                @SuppressWarnings("unchecked")
                T v = (T) get.invoke(delegate, timeout, unit);
                return v;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw new RuntimeException(c);
            }
        }

        @Override
        public State state() {
            try {
                java.lang.reflect.Method state = delegate.getClass().getMethod("state");
                Object v = state.invoke(delegate);
                String name = String.valueOf(v);
                return switch (name) {
                    case "SUCCESS" -> State.SUCCESS;
                    case "FAILED" -> State.FAILED;
                    case "CANCELLED" -> State.CANCELLED;
                    case "RUNNING" -> State.RUNNING;
                    default -> State.UNAVAILABLE;
                };
            } catch (Throwable t) {
                return State.UNAVAILABLE;
            }
        }
    }

    private static final class LatchedStructuredScope implements StructuredConcurrency {
        private final java.util.concurrent.ConcurrentHashMap<SubTask<?>, java.util.concurrent.CountDownLatch> latches = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean cancelled = false;

        @Override
        public @NotNull <T> SubTask<T> fork(@NotNull Callable<T> task) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            LatchedSubTask<T> handle = new LatchedSubTask<>(task, latch);
            latches.put(handle, latch);
            Thread.ofVirtual().name("lmili-scope-fork").start(() -> {
                if (cancelled) { latch.countDown(); return; }
                handle.run();
                latch.countDown();
            });
            return handle;
        }

        @Override
        public void join() throws Exception {
            for (java.util.concurrent.CountDownLatch l : latches.values()) l.await();
            for (SubTask<?> s : latches.keySet()) {
                if (s.state() == State.FAILED) ((LatchedSubTask<?>) s).throwIfFailed();
            }
        }

        @Override
        public void close() {
            cancelled = true;
            for (java.util.concurrent.CountDownLatch l : latches.values()) l.countDown();
        }
    }

    private static final class LatchedSubTask<T> implements StructuredConcurrency.SubTask<T> {
        private final Callable<T> task;
        private final java.util.concurrent.CountDownLatch latch;
        private volatile StructuredConcurrency.SubTask.State state = StructuredConcurrency.SubTask.State.RUNNING;
        private T value;
        private Throwable failure;

        LatchedSubTask(Callable<T> task, java.util.concurrent.CountDownLatch latch) {
            this.task = task;
            this.latch = latch;
        }

        void run() {
            try { this.value = task.call(); this.state = State.SUCCESS; }
            catch (Throwable t) { this.failure = t; this.state = State.FAILED; }
        }

        void throwIfFailed() throws Exception {
            if (failure instanceof Exception e) throw e;
            if (failure != null) throw new RuntimeException(failure);
        }

        @Override public T get() throws Exception { latch.await(); if (failure != null) throwIfFailed(); return value; }
        @Override public T get(long timeout, TimeUnit unit) throws Exception {
            if (!latch.await(timeout, unit)) throw new java.util.concurrent.TimeoutException();
            if (failure != null) throwIfFailed();
            return value;
        }
        @Override public StructuredConcurrency.SubTask.State state() { return state; }
    }

    private static final class ServerPluginRegionScheduler implements PluginRegionScheduler {
        @Override
        public @NotNull Object submit(@NotNull PluginRegionTask task) {
            MiliScheduler s = MiliSchedulerHolder.get();
            if (s == null) {
                task.runnable().run();
                return new Object();
            }
            RegionTask internal = adapt(task);
            PluginId owner = currentOwnerOrNull();
            if (owner != null) {
                return PluginSchedulerBridge.submit(owner, internal);
            }
            return s.submit(internal);
        }

        @Override
        public @NotNull Object scheduleDelayed(@NotNull PluginRegionTask task, long delay, @NotNull TimeUnit unit) {
            MiliScheduler s = MiliSchedulerHolder.get();
            if (s == null) {
                try { Thread.sleep(unit.toMillis(delay)); task.runnable().run(); } catch (InterruptedException ignored) {}
                return new Object();
            }
            RegionTask internal = adapt(task);
            PluginId owner = currentOwnerOrNull();
            if (owner != null) {
                TaskHandle h = PluginSchedulerBridge.submit(owner, internal);
                REGION_DELAY_POOL.schedule(h::cancel, delay, unit);
                return h;
            }
            return s.scheduleDelayed(internal, delay, unit);
        }

        @Override
        public boolean cancel(@NotNull Object handle) {
            if (handle instanceof TaskHandle th) return th.cancel();
            return false;
        }

        @Override
        public void shutdown() {}

        private static RegionTask adapt(PluginRegionTask task) {
            return new RegionTask() {
                @Override public void execute() { task.runnable().run(); }
                @Override public long regionId() { return task.regionId(); }
                @Override public boolean isBlocking() { return task.isBlocking(); }
                @Override public long timeoutMillis() { return task.timeoutMillis(); }
                @Override public @NotNull String name() { return task.name(); }
                @Override public void onCancel() {}
            };
        }

        private static PluginId currentOwnerOrNull() {
            try { return LMili.currentOwner(); }
            catch (Throwable ignored) { return null; }
        }
    }
}