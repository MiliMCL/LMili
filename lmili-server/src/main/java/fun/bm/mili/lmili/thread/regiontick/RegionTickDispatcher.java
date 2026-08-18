package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.FoliaTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.suspend.MiliThreadFactory;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Region tick 调度器 —— 管理 region tick 的 worker 线程池和任务分派。
 *
 * <p>支持两种 worker 模式：
 * <ul>
 *   <li><b>Virtual Thread 模式</b>（推荐，JDK 24+）：使用虚拟线程作为 worker，
 *       适合高并发场景，不受 OS 线程数限制。</li>
 *   <li><b>Platform Thread 模式</b>：传统固定大小线程池，适合保守部署或兼容性需求。</li>
 * </ul>
 *
 * <h3>执行模式</h3>
 * <ul>
 *   <li>Virtual 模式下：将 chunks 拆分为多个 slice，每个 slice 一个 virtual thread 并行执行，
 *       使用 StructuredScope 管理生命周期。</li>
 *   <li>Platform 模式下：按 worker 数量将 slices 分发给固定 worker 线程队列消费。</li>
 * </ul>
 */
public final class RegionTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RegionTickDispatcher instance;

    private final ExecutorService workerPool;
    private final boolean useVirtualThreads;
    private final RegionTickWorker[] workers;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts = new ConcurrentHashMap<>();
    private final int maxWorkersPerRegion;
    private final int minWorkersPerRegion;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final ModernDagTickExecutor dagExecutor;
    private final LongAdder totalTicksDispatched = new LongAdder();
    private final LongAdder totalTickErrors = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);
    private static final long SLOW_TICK_THRESHOLD_MS = 50;

    // 实体 tick 诊断队列 —— 记录最近 20 个慢实体信息（用于运维排查）
    private final ConcurrentLinkedQueue<String> slowEntities = new ConcurrentLinkedQueue<>();

    // 并行 chunk tick 追踪 —— 防止 tick 重叠（前一 tick 未完成时跳过下一 tick）
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkFutures = new ConcurrentHashMap<>();

    // Async Catcher 引用计数 —— 防止多 region 并发修改导致的竞态条件
    private final AtomicInteger asyncCatcherRefCount = new AtomicInteger(0);
    // 记录用户配置的 disable_async_catchers 基线值，计数归零时恢复它，
    // 避免强制将其硬编码为 false 而覆盖用户的配置。
    private final AtomicBoolean asyncCatcherBaseline = new AtomicBoolean(false);

    private RegionTickDispatcher(final int workerCount, final int maxWorkersPerRegion,
                                  final int minWorkersPerRegion, final int parallelismThreshold,
                                  final int sliceSize,
                                  final boolean useVirtualThreads) {
        this.maxWorkersPerRegion = maxWorkersPerRegion;
        this.minWorkersPerRegion = minWorkersPerRegion;
        this.parallelismThreshold = parallelismThreshold;
        this.sliceSize = sliceSize;
        this.useVirtualThreads = useVirtualThreads;

        if (useVirtualThreads) {
            this.workers = null;
            this.workerPool = Executors.newThreadPerTaskExecutor(
                    MiliThreadFactory.virtual("RegionTickPool-Virtual-"));
            LOGGER.info("[RegionTickPool] Worker pool: virtual thread per task");
        } else {
            this.workers = new RegionTickWorker[workerCount];
            for (int i = 0; i < workerCount; i++) {
                this.workers[i] = new RegionTickWorker("RegionTickPool-Worker-" + i);
            }
            this.workerPool = Executors.newFixedThreadPool(workerCount,
                    MiliThreadFactory.platform("RegionTickPool-Worker-"));
            LOGGER.info("[RegionTickPool] Worker pool: {} platform threads", workerCount);

            for (RegionTickWorker worker : this.workers) {
                this.workerPool.submit(worker);
            }
        }

        // 初始化现代 DAG 执行器
        this.dagExecutor = new ModernDagTickExecutor(this.workerPool);

        // 选择 executor
        RegionTickExecutor foliaExec = new FoliaTickExecutor();
        RegionTickExecutor.register(foliaExec);
        LOGGER.info("[RegionTickPool] Dispatcher initialized (virtualThreads={}, workers={})",
                useVirtualThreads, useVirtualThreads ? "unlimited" : workerCount);
    }

    public static RegionTickDispatcher init() {
        RegionTickDispatcher existing = instance;
        if (existing != null) return existing;
        synchronized (RegionTickDispatcher.class) {
            if (instance == null) {
                RegionTickPoolConfig config = new RegionTickPoolConfig();
                instance = new RegionTickDispatcher(
                        config.getWorkerCount(), config.getMaxWorkersPerRegion(),
                        config.getMinWorkersPerRegion(), config.parallelismThreshold,
                        config.sliceSize,
                        config.useVirtualThreads);
            }
            return instance;
        }
    }

    public static RegionTickDispatcher getInstance() { return instance; }

    /**
     * Returns true if the tick dispatcher is initialized and ready.
     */
    public static boolean isRunning() {
        return instance != null && RegionTickPoolConfig.enabled;
    }

    public boolean isVirtualThreadMode() {
        return useVirtualThreads;
    }

    public RegionTickContext registerRegion(final long regionId,
                                             final io.papermc.paper.threadedregions.ThreadedRegionizer
                                                     .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        return context;
    }

    public void unregisterRegion(final long regionId) {
        this.activeContexts.remove(regionId);
        this.pendingChunkFutures.remove(regionId);
    }

    public RegionTickContext getOrCreateContext(
            final io.papermc.paper.threadedregions.TickRegions.TickRegionData regionData) {
        final long regionId = regionData.id;
        RegionTickContext context = this.activeContexts.get(regionId);
        if (context != null) return context;
        synchronized (this) {
            context = this.activeContexts.get(regionId);
            if (context != null) return context;
            context = new RegionTickContext(regionId, regionData.region);
            this.activeContexts.put(regionId, context);
            return context;
        }
    }

    /**
     * 分派 region tick 任务 —— 主入口。
     *
     * <p>根据区域 chunk 数量决定执行策略：
     * <ul>
     *   <li>global region (id==0) 或空 chunk：跳过</li>
     *   <li>chunk 数低于 parallelismThreshold：单线程同步执行（无调度开销）</li>
     *   <li>virtual 模式：多 virtual thread 并行执行 slices</li>
     *   <li>platform 模式：分发给 worker 队列执行</li>
     * </ul>
     */
    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;

        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        long startNanos = System.nanoTime();
        try {
            if (chunkCount < parallelismThreshold) {
                // 小 region 直接同步执行，避免调度开销
                dispatchSingleSlice(context, chunks.toLongArray());
            } else if (useVirtualThreads) {
                dispatchParallelVirtual(context, chunks.toLongArray());
            } else {
                dispatchParallelPlatform(context, chunks.toLongArray());
            }
        } catch (Throwable throwable) {
            totalTickErrors.increment();
            LOGGER.error("[RegionTickPool] dispatchTick failed for region #{}", context.regionId, throwable);
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            totalTicksDispatched.increment();
            maxTickDurationNanos.accumulateAndGet(elapsed, Math::max);

            // 慢 tick 告警
            long elapsedMs = elapsed / 1_000_000;
            if (elapsedMs > SLOW_TICK_THRESHOLD_MS) {
                LOGGER.warn("[RegionTickPool] SLOW tick in region #{}: {}ms (chunks={}, mode={})",
                        context.regionId, elapsedMs, chunkCount,
                        useVirtualThreads ? "virtual" : "platform");
            }

            // 诊断日志（每 1000 tick）
            long total = totalTicksDispatched.sum();
            if (total % 1000L == 0L) {
                LOGGER.info("[RegionTickPool] Stats: total_ticks={}, errors={}, max_tick_ms={}, regions={}",
                        total, totalTickErrors.sum(), maxTickDurationNanos.get() / 1_000_000,
                        activeContexts.size());
            }
        }
    }

    /**
     * 单 slice 同步 tick —— 小 region 专用。
     */
    private void dispatchSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray) {
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Single-slice tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
    }

    /**
     * Virtual Thread 并行模式 —— 将 chunks 拆分为多个 slice，
     * 每个 slice 作为一个 virtual thread 并行执行。
     *
     * <p><b>非阻塞设计</b>：chunk tick 提交后立即返回，不阻塞 region tick thread。
     * 使用 CompletableFuture 追踪完成状态，下一 tick 如果检测到前一 tick 仍在运行，
     * 则跳过本次 chunk tick（避免堆积）。
     *
     * <p>兼容性说明：Folia 通过 {@code TickThreadRunner.currentTickingWorldRegionizedData} 跟踪 region data，
     * 虚拟线程不在该体系中。本方法通过 {@link RegionDataThreadLocal} 将当前 region data 传播到每个虚拟线程，
     * 确保 chunk tick 内的 {@code getCurrentWorldData()} / {@code getLocalPlayers()} 等调用不返回 null。
     */
    private void dispatchParallelVirtual(@NotNull final RegionTickContext context, final long[] chunkArray) {
        final long regionId = context.regionId;

        // 检查前一 tick 是否仍在运行 —— 如果仍在运行则执行增量 tick（部分 chunk）
        CompletableFuture<Void> previous = pendingChunkFutures.get(regionId);
        boolean incrementalTick = false;
        if (previous != null && !previous.isDone()) {
            LOGGER.warn("[RegionTickPool] Previous chunk tick still in-flight for region #{} — performing incremental tick ({} chunks)",
                    regionId, chunkArray.length);
            incrementalTick = true;
        }

        int total = chunkArray.length;

        // Mili start - 增量 tick 模式下：只 tick 一半的 chunk（每隔一个取一个），
        // 确保即使前一 tick 超时，也能持续处理部分 chunk 而非完全跳过
        long[] effectiveChunkArray = chunkArray;
        if (incrementalTick) {
            // 每隔一个 chunk 取一个，确保每次增量 tick 处理不同的子集
            int halfCount = (total + 1) / 2;
            long[] halfArray = new long[halfCount];
            for (int i = 0; i < halfCount; i++) {
                halfArray[i] = chunkArray[i * 2];
            }
            effectiveChunkArray = halfArray;
            total = halfCount;
        }
        // Mili end

        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);

        // Mili start: 捕获当前 region 的 RegionizedWorldData，传递给每个虚拟线程
        io.papermc.paper.threadedregions.RegionizedWorldData currentRegionData = null;
        try {
            net.minecraft.world.level.Level world = context.region.getData().world;
            if (world instanceof net.minecraft.server.level.ServerLevel) {
                currentRegionData = world.getCurrentWorldData();
            }
        } catch (Exception e) {
            LOGGER.warn("[RegionTickPool] Failed to capture region data for virtual dispatch", e);
        }
        final io.papermc.paper.threadedregions.RegionizedWorldData regionData = currentRegionData;

        if (regionData == null) {
            // 无法获取 region data 时回退到单线程执行
            LOGGER.warn("[RegionTickPool] No region data for virtual dispatch in region #{} — falling back to single-slice",
                    regionId);
            dispatchSingleSlice(context, effectiveChunkArray);
            return;
        }
        // Mili end

        // 创建 CompletableFuture 追踪本次 tick 的所有 slice
        CompletableFuture<Void>[] sliceFutures = new CompletableFuture[sliceCount];

        // Mili start - Async Catcher 引用计数：每个参与并行 tick 的 region 都无条件计入。
        // 绝不依赖 "enabled 当前是否为 false" 来决定是否计数 —— 否则当 region A 提前把
        // enabled 置为 true 后，region B 会跳过计数（only increment when disabled），
        // 而 A 的异步完成回调可能在 B 的虚拟线程仍执行 chunk tick 时把 enabled 置回 false，
        // 导致 "Thread failed main thread check" 竞态崩溃（已在生产日志中复现）。
        // 正确做法：所有 virtual dispatch 全部计数，只有计数归零（所有 region 都完成）才恢复基线值。
        // 首个 dispatch（计数 0->1）记录用户配置基线并强制启用绕过；
        // 最后一个完成的 dispatch（计数 1->0）恢复基线。
        // 使用同步块确保计数检查和状态修改的原子性
        synchronized (this) {
            if (asyncCatcherRefCount.incrementAndGet() == 1) {
                asyncCatcherBaseline.set(fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled);
                fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled = true;
            }
        }

        try {
            for (int i = 0; i < sliceCount; i++) {
                int from = i * sliceSize;
                int to = Math.min(from + sliceSize, total);
                long[] sliceArray = java.util.Arrays.copyOfRange(effectiveChunkArray, from, to);
                RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i);
                final int sliceIndex = i;

                sliceFutures[i] = CompletableFuture.runAsync(() -> {
                    // Mili start: 在虚拟线程中设置 region data 回退
                    RegionDataThreadLocal.setCurrent(regionData);
                    try {
                        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                        if (executor != null) {
                            executor.executeSlice(null, slice, context);
                        }
                    } catch (Throwable throwable) {
                        LOGGER.error("[RegionTickPool] Virtual slice #{} failed for region #{}",
                                sliceIndex, regionId, throwable);
                    } finally {
                        RegionDataThreadLocal.clear();
                    }
                    // Mili end
                }, workerPool);
            }

            // 创建组合 future 追踪所有 slice 完成，但不阻塞当前线程
            CompletableFuture<Void> allSlices = CompletableFuture.allOf(sliceFutures);
            // 保存引用以便下一 tick 检查
            pendingChunkFutures.put(regionId, allSlices);

            // 设置超时和完成处理（使用 handle 避免双重异常处理）
            allSlices.orTimeout(RegionTickPoolConfig.virtualThreadTimeoutMs, TimeUnit.MILLISECONDS)
                    .handle((result, throwable) -> {
                        // 清理 pending 状态
                        pendingChunkFutures.remove(regionId);
                        if (throwable instanceof TimeoutException) {
                            LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{} after {}ms",
                                    regionId, RegionTickPoolConfig.virtualThreadTimeoutMs);
                        } else if (throwable != null) {
                            totalTickErrors.increment();
                            LOGGER.error("[RegionTickPool] Virtual tick completed with error for region #{}", regionId, throwable);
                        }
                        // 恢复 async catcher（引用计数归零时 —— 所有 region 的 virtual dispatch 都完成后，恢复用户基线）
                        // 使用同步块确保计数检查和状态恢复的原子性
                        synchronized (this) {
                            if (asyncCatcherRefCount.decrementAndGet() == 0) {
                                fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled =
                                        asyncCatcherBaseline.get();
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            // 异常时恢复 async catcher（未派发成功也要归还计数）
            synchronized (this) {
                if (asyncCatcherRefCount.decrementAndGet() == 0) {
                    fun.bm.mili.config.modules.experiment.DisableAsyncCatcherConfig.enabled =
                            asyncCatcherBaseline.get();
                }
            }
            throw e;
        }
    }

    /**
     * Platform Thread 并行模式 —— 将 slices 分配给 worker 队列。
     *
     * <p>阻塞式设计：等待所有 worker 完成。
     * 此模式适合保守部署（worker 数量有限，总执行时间可控）。
     * 如果 chunk tick 时间过长，可能触发 Folia watchdog。
     * 建议仅在 virtual thread 不可用时使用此模式。
     */
    private void dispatchParallelPlatform(@NotNull final RegionTickContext context, final long[] chunkArray) {
        if (workers == null || workers.length == 0) return;

        RegionTickSlice[] slices = RegionTickSlice.fromChunkArray(context, chunkArray, sliceSize);
        // Mili start - beginTick 的 parties 应为 slice 数 + 1（region tick 线程本身）。
        // 原代码传入 slices.length，但 workers 会对每个 slice 调用一次 arriveSlice()
        // （共 slices.length 次），加上 awaitTickCompletion() 中主线程的 arrive()，
        // 总到达数 = slices.length + 1。若只注册 slices.length 个参与方，
        // 相位会在最后一个 slice 尚未完成时提前推进，导致 dispatchParallelPlatform 提前返回、
        // 与下一 tick 产生竞态。改为 slices.length + 1 与文档契约一致。
        context.beginTick(slices.length + 1);

        // 贪心负载均衡分发给 workers
        int actualWorkers = Math.min(slices.length, workers.length);
        int[] load = new int[actualWorkers];
        for (RegionTickSlice slice : slices) {
            int minIdx = 0;
            for (int i = 1; i < actualWorkers; i++) {
                if (load[i] < load[minIdx]) minIdx = i;
            }
            workers[minIdx].submit(slice);
            load[minIdx]++;
        }

        // 等待所有 worker 完成
        context.awaitTickCompletion();
        context.endTick();
    }

    /**
     * 实体 tick 调度 —— 在 region tick 线程上同步执行。
     *
     * <p>注意：实体 tick 会调用 {@code Level.getLocalPlayers()}，依赖 Folia 的线程本地 region 数据，
     * 因此必须在 region tick 线程上直接执行，不能派发到其他线程。
     *
     * <p>诊断能力：
     * <ul>
     *   <li>单实体警告：单个实体耗时超过 {@code per-entity-warn-ms} 时记录诊断信息</li>
     *   <li>慢实体队列：记录最近 N 个慢实体的类型和位置，供运维排查</li>
     * </ul>
     */
    public void dispatchEntityTick(final long regionId,
                                    @NotNull final RegionTickContext context,
                                    @NotNull final ServerLevel level,
                                    @NotNull final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData) {
        if (this.shutdown.get()) return;

        regionizedWorldData.forEachTickingEntity(entity -> {
            if (entity.isRemoved()) return;
            if (level.tickRateManager().isEntityFrozen(entity)) return;

            long entityStartNanos = System.nanoTime();

            try {
                entity.checkDespawn();
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Entity checkDespawn failed for {} in region #{}", entity, regionId, throwable);
            }
            if (entity.isRemoved()) return;
            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) return;
                entity.stopRiding();
            }
            level.guardEntityTick(level::tickNonPassenger, entity);

            // 单个实体 tick 过慢时记录诊断信息
            long entityElapsedNanos = System.nanoTime() - entityStartNanos;
            if (entityElapsedNanos >= RegionTickPoolConfig.perEntityWarnMs * 1_000_000L) {
                recordSlowEntity(entity, entityElapsedNanos / 1_000_000, regionId);
            }
        });

        // 限制慢实体诊断队列大小（保留最近 20 条）
        trimSlowEntityLog();
    }

    /**
     * 记录慢实体的诊断信息。
     */
    private void recordSlowEntity(net.minecraft.world.entity.Entity entity, long elapsedMs, long regionId) {
        String entityInfo = String.format("%s[id=%d] at [%.1f, %.1f, %.1f] took %dms in region #%d",
                entity.getType().toString(), entity.getId(),
                entity.getX(), entity.getY(), entity.getZ(),
                elapsedMs, regionId);
        slowEntities.offer(entityInfo);
    }

    /**
     * 限制慢实体诊断队列大小。
     */
    private void trimSlowEntityLog() {
        while (slowEntities.size() > 20) {
            slowEntities.poll();
        }
    }

    public void registerDagSystem(@NotNull final String name,
                                   @NotNull final SystemProfile profile,
                                   @NotNull final Scope scope,
                                   @NotNull final java.util.function.BiConsumer<SystemProfile, Scope> executor) {
        // 包装为 BiConsumer<SystemProfile, Object> 以适配 ModernDagTickExecutor 的签名
        dagExecutor.registerSystem(name, profile, scope, (prof, scp) -> executor.accept(prof, (Scope) scp));
        RegionTickExecutor.register(dagExecutor);
        LOGGER.info("[RegionTickPool] DAG system '{}' registered, total systems={}", name, dagExecutor.getSystemCount());
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_regions", this.activeContexts.size());
        stats.put("total_ticks_dispatched", this.totalTicksDispatched.sum());
        stats.put("total_tick_errors", this.totalTickErrors.sum());
        stats.put("max_tick_duration_ms", this.maxTickDurationNanos.get() / 1_000_000);
        stats.put("worker_count", useVirtualThreads ? "unlimited (virtual)" : (workers != null ? workers.length : 0));
        stats.put("use_virtual_threads", useVirtualThreads);
        stats.put("dag_systems", dagExecutor.getSystemCount());
        stats.put("pending_chunk_ticks", this.pendingChunkFutures.size());
        stats.put("slow_entity_diagnostics", new ArrayList<>(this.slowEntities));
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        if (workers != null) {
            for (RegionTickWorker worker : this.workers) worker.shutdown();
        }
        this.workerPool.shutdown();
        try {
            if (!this.workerPool.awaitTermination(5, TimeUnit.SECONDS)) this.workerPool.shutdownNow();
        } catch (InterruptedException e) {
            this.workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        this.activeContexts.clear();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
