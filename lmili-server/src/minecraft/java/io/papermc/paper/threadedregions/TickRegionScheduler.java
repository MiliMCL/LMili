package io.papermc.paper.threadedregions;

import ca.spottedleaf.common.time.Schedule;
import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class TickRegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean MEASURE_CPU_TIME;
    static {
        MEASURE_CPU_TIME = THREAD_MX_BEAN.isThreadCpuTimeSupported();
        if (MEASURE_CPU_TIME) {
            THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
        } else {
            LOGGER.warn("TickRegionScheduler CPU time measurement is not available");
        }
    }

    public static final int TICK_RATE = 20;
    public static long TIME_BETWEEN_TICKS = 1_000_000_000L / TICK_RATE; // ns
    // Folia start - watchdog
    public static final FoliaWatchdogThread WATCHDOG_THREAD = new FoliaWatchdogThread();
    static {
        WATCHDOG_THREAD.start();
    }
    // Folia end - watchdog

    // Mili start - unified scheduler (Folia scheduler removed)
    // Stub enum kept for backward compatibility with GlobalConfiguration — not used by Mili scheduler
    public static enum SchedulerType {
        EDF,
        WORK_STEALING;
    }
    private final fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler miliScheduler;
    // Mili end

    /**
     * Mili 构造器 —— 使用 MiliTickRegionScheduler 替代 Folia 的 Scheduler。
     *
     * @param miliScheduler Mili 调度器实例
     */
    protected TickRegionScheduler(final fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler miliScheduler) {
        this.miliScheduler = miliScheduler;
    }

    public void start() {
        this.miliScheduler.start();
    }

    public void setThreads(final int threads) {
        this.miliScheduler.setThreads(threads);
    }

    public int getTotalThreadCount() {
        return this.miliScheduler.getTotalThreadCount();
    }

    private static void setTickingRegion(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        final Thread currThread = Thread.currentThread();
        // Mili start - support MiliTickThread
        if (currThread instanceof fun.bm.mili.lmili.thread.scheduler.MiliTickThread miliThread) {
            // 与 TickThreadRunner 相同的逻辑：设置 region 和 worldData
            miliThread.currentTickingRegion = region;
            if (region != null) {
                miliThread.currentTickingWorldRegionizedData = region.regioniser.world.worldRegionData.get();
            } else {
                miliThread.currentTickingWorldRegionizedData = null;
            }
            return;
        }
        // Mili end
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            throw new IllegalStateException("Must be tick thread runner");
        }
        if (region != null && tickThreadRunner.currentTickingRegion != null) {
            throw new IllegalStateException("Trying to double set ticking region!");
        }
        if (region == null && tickThreadRunner.currentTickingRegion == null) {
            throw new IllegalStateException("Trying to double unset ticking region!");
        }
        tickThreadRunner.currentTickingRegion = region;
        if (region != null) {
            tickThreadRunner.currentTickingWorldRegionizedData = region.regioniser.world.worldRegionData.get();
            // Folia start - profiler
            final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = region.getData().profiler;
            tickThreadRunner.profiler = profiler == null ? ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.NO_OP_HANDLE : profiler;
            // Folia end - profiler
        } else {
            tickThreadRunner.currentTickingWorldRegionizedData = null;
            tickThreadRunner.profiler = ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.NO_OP_HANDLE; // Folia - profiler
        }
    }

    private static void setTickTask(final SchedulableTick task) {
        final Thread currThread = Thread.currentThread();
        // Mili start - support MiliTickThread
        if (currThread instanceof fun.bm.mili.lmili.thread.scheduler.MiliTickThread) {
            // MiliTickThread 不需要设置 tickTask
            return;
        }
        // Mili end
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            throw new IllegalStateException("Must be tick thread runner");
        }
        if (task != null && tickThreadRunner.currentTickingTask != null) {
            throw new IllegalStateException("Trying to double set ticking task!");
        }
        if (task == null && tickThreadRunner.currentTickingTask == null) {
            throw new IllegalStateException("Trying to double unset ticking task!");
        }
        tickThreadRunner.currentTickingTask = task;
    }

    /**
     * Returns the current ticking region, or {@code null} if there is no ticking region.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> getCurrentRegion() {
        final Thread currThread = Thread.currentThread();
        if (currThread instanceof TickThreadRunner tickThreadRunner) {
            return tickThreadRunner.currentTickingRegion;
        }
        // Mili start - support MiliTickThread for new scheduler
        if (currThread instanceof fun.bm.mili.lmili.thread.scheduler.MiliTickThread miliThread) {
            return miliThread.currentTickingRegion;
        }
        // Mili end
        return RegionShutdownThread.getRegion();
    }

    /**
     * Returns the current ticking region's world regionised data, or {@code null} if there is no ticking region.
     * This is a faster alternative to calling the {@link RegionizedData#get()} method.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static RegionizedWorldData getCurrentRegionizedWorldData() {
        final Thread currThread = Thread.currentThread();
        if (currThread instanceof TickThreadRunner tickThreadRunner) {
            return tickThreadRunner.currentTickingWorldRegionizedData;
        }
        // Mili start - support MiliTickThread for new scheduler
        if (currThread instanceof fun.bm.mili.lmili.thread.scheduler.MiliTickThread miliThread) {
            return miliThread.currentTickingWorldRegionizedData;
        }
        // fallback for virtual threads in RegionTickPool
        final io.papermc.paper.threadedregions.RegionizedWorldData miliFallback = fun.bm.mili.lmili.thread.regiontick.RegionDataThreadLocal.getCurrent();
        if (miliFallback != null) {
            return miliFallback;
        }
        // Mili end
        return RegionShutdownThread.getWorldData();
    }

    /**
     * Returns the current ticking task, or {@code null} if there is no ticking region.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static SchedulableTick getCurrentTickingTask() {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            return null;
        }
        return tickThreadRunner.currentTickingTask;
    }

    // Folia start - profiler
    public static ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle getProfiler() {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            return ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.NO_OP_HANDLE;
        }
        return tickThreadRunner.profiler;
    }
    // Folia end - profiler


    /**
     * Schedules the given region
     * @throws IllegalStateException If the region is already scheduled or is ticking
     */
    public void scheduleRegion(final RegionScheduleHandle region) {
        region.scheduler = this;
        this.miliScheduler.scheduleRegion(region);
    }

    /**
     * Attempts to de-schedule the provided region. If the current region cannot be cancelled for its next tick or task
     * execution, then it will be cancelled after.
     */
    public void descheduleRegion(final RegionScheduleHandle region) {
        this.miliScheduler.descheduleRegion(region);
    }

    public boolean halt(final boolean sync, final long maxWaitNS) {
        this.miliScheduler.halt();
        if (!sync) {
            return this.miliScheduler.isHalted();
        }
        return this.miliScheduler.join(maxWaitNS == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(maxWaitNS));
    }

    void dumpAliveThreadTraces(final String reason) {
        this.miliScheduler.dumpAliveThreadTraces(reason);
    }

    public void setHasTasks(final RegionScheduleHandle region) {
        this.miliScheduler.setHasTasks(region);
    }

    private void uncaughtException(final Thread thread, final Throwable thr) {
        LOGGER.error("Uncaught exception in tick thread \"" + thread.getName() + "\"", thr);
        this.miliScheduler.halt();
        MinecraftServer.getServer().stopServer();
    }

    private void regionFailed(final RegionScheduleHandle handle, final boolean executingTasks, final Throwable thr) {
        this.miliScheduler.halt();

        final ChunkPos center = handle.region == null ? null : handle.region.region.getCenterChunk();
        final ServerLevel world = handle.region == null ? null : handle.region.world;

        LOGGER.error("Region #" + (handle.region == null ? -1L : handle.region.id) + " centered at chunk " + center + " in world '" + (world == null ? "null" : world.getWorld().getName()) + "' failed to " + (executingTasks ? "execute tasks" : "tick") + ":", thr);

        MinecraftServer.getServer().stopServer();
    }

    // By using our own thread object, we can use a field for the current region rather than a ThreadLocal.
    // This is much faster than a thread local, since the thread local has to use a map lookup.
    private static final class TickThreadRunner extends TickThread {

        private ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentTickingRegion;
        private RegionizedWorldData currentTickingWorldRegionizedData;
        private SchedulableTick currentTickingTask;
        // Folia start - profiler
        private ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.NO_OP_HANDLE;
        // Folia end - profiler

        public TickThreadRunner(final ThreadGroup group, final Runnable run, final String name) {
            super(group, run, name);
        }
    }

    public static abstract class RegionScheduleHandle extends SchedulableTick {

        protected long currentTick;
        protected long lastTickStart;

        protected final TickData tickTimes5s;
        protected final TickData tickTimes15s;
        protected final TickData tickTimes1m;
        protected final TickData tickTimes5m;
        protected final TickData tickTimes15m;
        protected TickTime currentTickData;
        protected long intermediateTaskTime = 0L;
        protected long intermediateTaskTimeCPU = 0L;
        protected Thread currentTickingThread;

        public final TickRegions.TickRegionData region;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        protected final Schedule tickSchedule;

        private TickRegionScheduler scheduler;

        // C-06 修复：next-tick gate —— 下次允许 tick 的最早时间（毫秒）
        // 防止 region 在允许时间之前被重复 tick
        public volatile long nextAllowedTickTimeMillis = 0L;

        public RegionScheduleHandle(final TickRegions.TickRegionData region, final long firstStart) {
            this.currentTick = 0L;
            this.lastTickStart = TimeUtil.DEADLINE_NOT_SET;
            this.tickTimes5s = new TickData(TimeUnit.SECONDS.toNanos(5L));
            this.tickTimes15s = new TickData(TimeUnit.SECONDS.toNanos(15L));
            this.tickTimes1m = new TickData(TimeUnit.MINUTES.toNanos(1L));
            this.tickTimes5m = new TickData(TimeUnit.MINUTES.toNanos(5L));
            this.tickTimes15m = new TickData(TimeUnit.MINUTES.toNanos(15L));
            this.region = region;

            this.setScheduledStart(firstStart);
            this.tickSchedule = new Schedule(firstStart == TimeUtil.DEADLINE_NOT_SET ? firstStart : firstStart - TIME_BETWEEN_TICKS);
        }

        /**
         * Subclasses should call this instead of {@link #setScheduledStart(long)}
         * so that the tick schedule and scheduled start remain synchronised
         */
        protected final void updateScheduledStart(final long to) {
            this.setScheduledStart(to);
            this.tickSchedule.setLastPeriod(to == TimeUtil.DEADLINE_NOT_SET ? to : to - TIME_BETWEEN_TICKS);
        }

        public final void markNonSchedulable() {
            this.cancelled.set(true);
        }

        public final boolean isMarkedAsNonSchedulable() {
            return this.cancelled.get();
        }

        protected abstract boolean tryMarkTicking();

        protected abstract boolean markNotTicking();

        protected abstract void tickRegion(final long tickCount, final long startTime, final long scheduledEnd);

        protected abstract void runRegionTasks(final BooleanSupplier canContinue);

        protected abstract boolean hasIntermediateTasks();

        @Override
        public final boolean hasTasks() {
            return this.hasIntermediateTasks();
        }

        @Override
        public final boolean runTasks(final BooleanSupplier canContinue) {
            if (this.cancelled.get()) {
                return false;
            }

            final long cpuStart = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
            final long tickStart = System.nanoTime();

            if (!this.tryMarkTicking()) {
                if (!this.cancelled.get()) {
                    throw new IllegalStateException("Scheduled region should be acquirable");
                }
                // region was killed
                return false;
            }

            TickRegionScheduler.setTickTask(this);
            if (this.region != null) {
                TickRegionScheduler.setTickingRegion(this.region.region);
            }

            synchronized (this) {
                this.currentTickingThread = Thread.currentThread();
            }

            final FoliaWatchdogThread.RunningTick runningTick = new FoliaWatchdogThread.RunningTick(tickStart, this, Thread.currentThread()); // Folia - watchdog
            WATCHDOG_THREAD.addTick(runningTick); // Folia - watchdog
            try {
                this.runRegionTasks(() -> {
                    return !RegionScheduleHandle.this.cancelled.get() && canContinue.getAsBoolean();
                });
            } catch (final Throwable thr) {
                this.scheduler.regionFailed(this, true, thr);
                // don't release region for another tick
                return false;
            } finally {
                WATCHDOG_THREAD.removeTick(runningTick); // Folia - watchdog
                final long tickEnd = System.nanoTime();
                final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;

                synchronized (this) {
                    this.intermediateTaskTime += (tickEnd - tickStart);
                    this.intermediateTaskTimeCPU += (cpuEnd - cpuStart);
                }

                TickRegionScheduler.setTickTask(null);
                if (this.region != null) {
                    TickRegionScheduler.setTickingRegion(null);
                }
            }

            return this.markNotTicking() && !this.cancelled.get();
        }

        @Override
        public final boolean runTick() {
            // Remember, we are supposed use setScheduledStart if we return true here, otherwise
            // the scheduler will try to schedule for the same time.
            if (this.cancelled.get()) {
                return false;
            }

            final long cpuStart = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
            final long tickStart = System.nanoTime();

            // use max(), don't assume that tickStart >= scheduledStart
            final long tickCount = Math.max(1L, this.tickSchedule.getPeriodsAhead(TIME_BETWEEN_TICKS, tickStart));

            if (!this.tryMarkTicking()) {
                if (!this.cancelled.get()) {
                    throw new IllegalStateException("Scheduled region should be acquirable");
                }
                // region was killed
                return false;
            }
            if (this.cancelled.get()) {
                this.markNotTicking();
                // region should be killed
                return false;
            }

            TickRegionScheduler.setTickTask(this);
            if (this.region != null) {
                TickRegionScheduler.setTickingRegion(this.region.region);
            }
            this.incrementTickCount();
            final long lastTickStart = this.lastTickStart;
            this.lastTickStart = tickStart;

            final long scheduledStart = this.getScheduledStart();
            final long scheduledEnd = scheduledStart + TIME_BETWEEN_TICKS;

            final long intermediateTaskTime;
            final long intermediateTaskTimeCPU;

            synchronized (this) {
                intermediateTaskTime = this.intermediateTaskTime;
                intermediateTaskTimeCPU = this.intermediateTaskTimeCPU;
                this.intermediateTaskTime = 0L;
                this.intermediateTaskTimeCPU = 0L;

                this.currentTickData = new TickTime(
                    lastTickStart, scheduledStart, tickStart, cpuStart,
                    TimeUtil.DEADLINE_NOT_SET, TimeUtil.DEADLINE_NOT_SET,
                    intermediateTaskTime, intermediateTaskTimeCPU,
                    MEASURE_CPU_TIME
                );
                this.currentTickingThread = Thread.currentThread();
            }

            final FoliaWatchdogThread.RunningTick runningTick = new FoliaWatchdogThread.RunningTick(tickStart, this, Thread.currentThread()); // Folia - region threading
            WATCHDOG_THREAD.addTick(runningTick); // Folia - region threading
            try {
                // next start isn't updated until the end of this tick
                this.tickRegion(tickCount, tickStart, scheduledEnd);
            } catch (final Throwable thr) {
                this.scheduler.regionFailed(this, false, thr);
                // regionFailed will schedule a shutdown, so we should avoid letting this region tick further
                return false;
            } finally {
                WATCHDOG_THREAD.removeTick(runningTick); // Folia - region threading
                final long tickEnd = System.nanoTime();
                final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;

                // in order to ensure all regions get their chance at scheduling, we have to ensure that regions
                // that exceed the max tick time are not always prioritised over everything else. Thus, we use the greatest
                // of the current time and "ideal" next tick start.
                this.tickSchedule.advanceBy(tickCount, TIME_BETWEEN_TICKS);
                this.setScheduledStart(TimeUtil.getGreatestTime(tickEnd, this.tickSchedule.getDeadline(TIME_BETWEEN_TICKS)));

                final TickTime time = new TickTime(
                    lastTickStart, scheduledStart, tickStart, cpuStart, tickEnd, cpuEnd,
                    intermediateTaskTime, intermediateTaskTimeCPU,
                    MEASURE_CPU_TIME
                );

                this.addTickTime(time);
                TickRegionScheduler.setTickTask(null);
                if (this.region != null) {
                    TickRegionScheduler.setTickingRegion(null);
                }
            }

            // Only AFTER updating the tickStart
            return this.markNotTicking() && !this.cancelled.get();
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        protected void addTickTime(final TickTime time) {
            synchronized (this) {
                this.currentTickData = null;
                this.currentTickingThread = null;
                this.tickTimes5s.addDataFrom(time);
                this.tickTimes15s.addDataFrom(time);
                this.tickTimes1m.addDataFrom(time);
                this.tickTimes5m.addDataFrom(time);
                this.tickTimes15m.addDataFrom(time);
            }
        }

        private TickTime adjustCurrentTickData(final long tickEnd) {
            final TickTime currentTickData = this.currentTickData;
            if (currentTickData == null) {
                return null;
            }

            final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getThreadCpuTime(this.currentTickingThread.threadId()) : 0L;

            return new TickTime(
                currentTickData.previousTickStart(), currentTickData.scheduledTickStart(),
                currentTickData.tickStart(), currentTickData.tickStartCPU(),
                tickEnd, cpuEnd, 0L, 0L, // TODO
                MEASURE_CPU_TIME
            );
        }

        public final TickData.TickReportData getTickReport5s(final long currTime) {
            synchronized (this) {
                return this.tickTimes5s.generateTickReport(this.adjustCurrentTickData(currTime), currTime, TIME_BETWEEN_TICKS);
            }
        }

        public final TickData.TickReportData getTickReport15s(final long currTime) {
            synchronized (this) {
                return this.tickTimes15s.generateTickReport(this.adjustCurrentTickData(currTime), currTime, TIME_BETWEEN_TICKS);
            }
        }

        public final TickData.TickReportData getTickReport1m(final long currTime) {
            synchronized (this) {
                return this.tickTimes1m.generateTickReport(this.adjustCurrentTickData(currTime), currTime, TIME_BETWEEN_TICKS);
            }
        }

        public final TickData.TickReportData getTickReport5m(final long currTime) {
            synchronized (this) {
                return this.tickTimes5m.generateTickReport(this.adjustCurrentTickData(currTime), currTime, TIME_BETWEEN_TICKS);
            }
        }

        public final TickData.TickReportData getTickReport15m(final long currTime) {
            synchronized (this) {
                return this.tickTimes15m.generateTickReport(this.adjustCurrentTickData(currTime), currTime, TIME_BETWEEN_TICKS);
            }
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        private void incrementTickCount() {
            ++this.currentTick;
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        public final long getCurrentTick() {
            return this.currentTick;
        }

        protected final void setCurrentTick(final long value) {
            this.currentTick = value;
        }
    }
}