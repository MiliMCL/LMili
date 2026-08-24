package fun.bm.mili.config.modules.function;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.IllegalFormatConversionExceptionWithOrigin;
import fun.bm.mili.lmili.config.flags.*;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import fun.bm.mili.lmili.enums.EnumRegionFormat;
import fun.bm.mili.lmili.runtime.RuntimeBootstrap;
import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "region_format")
public class RegionFormatConfig implements IConfigModule {
    @HotReloadUnsupported
    @TransformedConfig(name = "format", directory = {"misc", "region_format"})
    @ConfigInfo(name = "format", allowAutoReset = false, comments = "Available choices: MCA, O_LINEAR")
    public static EnumRegionFormat regionFormat = EnumRegionFormat.MCA;
    @HotReloadUnsupported
    @TransformedConfig(name = "linear_compression_level", directory = {"misc", "region_format"})
    @ConfigInfo(name = "linear_compression_level", comments = "Decides the compression level of the region file (Only works for O_LINEAR)")
    public static int linearCompressionLevel = 1;
    @HotReloadUnsupported
    @ConfigInfo(name = "olinear_io_flush_delay_ms", comments = "Decides when it will be flushed to the region file when there has been no write operations for n(default is 3000) milliseconds(Only works for O_LINEAR)")
    public static int olinearIoFlushDelayMs = 3000;
    @HotReloadUnsupported
    @ConfigInfo(name = "olinear_io_thread_count", comments = "Decides the worker thread count of optimized linear(Only works for O_LINEAR)")
    public static int olinearIoThreadCount = 6;
    @HotReloadUnsupported
    @ConfigInfo(name = "olinear_max_sync_age_ms", comments = "Forces a master-file sync after this many ms even while the region is being written to continuously, bounding how much recent data a power failure can lose (Only works for O_LINEAR). Default 15000.")
    public static int olinearMaxSyncAgeMs = 15000;

    // =====================================================================
    // Mili start - Auto-enable o_linear optimizations when regionFormat = O_LINEAR
    // ---------------------------------------------------------------------
    // 设计目标：用户只需切换 regionFormat = O_LINEAR，本类下所有优化（A1 gather read +
    //   A2 gather buffer pool + B writeChunk buffer pool + C1 跳过 fsync + D1 compact 内
    //   跳过 fsync）即自动生效，无须单独开启。
    //
    // 总开关 `linear_optimizations_enabled`（默认 true）允许高级用户一键关闭所有优化。
    // 单独开关（如 `linear_gather_read_enabled`）允许高级用户关闭单项优化。
    //
    // 所有单独开关默认值 = "优化已启用"（A1/B/D1 true；C1 false = 跳过 fsync 即优化）。
    // 单独开关仅在 regionFormat = O_LINEAR && linear_optimizations_enabled 时才生效。
    // MCA 用户：单独开关值不影响任何行为（site-level gate 跳过）。
    // =====================================================================

    /**
     * o_linear 优化总开关（仅 regionFormat = O_LINEAR 时生效）。
     * 默认 true —— 只要切换 regionFormat = O_LINEAR 即自动获得所有优化。
     * 设为 false 关闭所有优化（恢复部分原版行为；C1 force 由 olinearMaxSyncAgeMs 仍有效）。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_optimizations_enabled", comments = "Master switch for all o_linear optimizations (A1/A2/B/C1/D1). Auto-applies when regionFormat = O_LINEAR. Default true.")
    public static boolean linearOptimizationsEnabled = true;

    /**
     * Stage A1：相邻 sector 批量读（gather read）。
     * 默认 true —— O_LINEAR 用户自动启用。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_gather_read_enabled", comments = "Stage A1: batch read of physically contiguous sectors into the page cache (O_LINEAR only). Default true.")
    public static boolean linearGatherReadEnabled = true;

    /**
     * Stage A1 调参：批量读扇区数上限。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_gather_read_batch_size", comments = "Stage A1: max contiguous sectors per FileChannel.read. Default 4.")
    public static int linearGatherReadBatchSize = 4;

    /**
     * Stage A1 调参：批量读总字节上限。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_gather_read_max_batch_bytes", comments = "Stage A1: max total bytes per gather read. Default 262144 (256 KiB).")
    public static int linearGatherReadMaxBatchBytes = 262144;

    /**
     * Stage B：writeChunk chunkSectionBuilder ThreadLocal 复用池。
     * 默认 true —— O_LINEAR 用户自动启用。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_write_buffer_pool_enabled", comments = "Stage B: ThreadLocal buffer pool for writeChunk's chunkSectionBuilder (O_LINEAR only). Default true.")
    public static boolean linearWriteBufferPoolEnabled = true;

    /**
     * Stage C1：master 文件 sync 时是否强制 fsync。
     * 默认 false（= 跳过 fsync）—— 即 C1 优化默认启用。fsync 由 olinearMaxSyncAgeMs 兜底。
     * 设为 true 恢复原版"每次 sync 都 fsync"行为（高 durability，慢）。
     * 注：本开关语义与其他优化反向：true = 原版/未优化，false = 优化版。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_force_on_every_sync", comments = "Stage C1: fsync master file + parent dir on every sync. Default false (force only when maxSyncAgeMs triggers).")
    public static boolean linearForceOnEverySync = false;

    /**
     * Stage D2：compact 触发阈值（仅调参旋钮）。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_auto_compact_size_bytes", comments = "Stage D2: min swap spare size to trigger auto-compact on flush (O_LINEAR only). Default 1048576 (1 MiB).")
    public static long linearAutoCompactSizeBytes = 1024L * 1024L;

    /**
     * Stage D2：compact 触发相对阈值（仅调参旋钮）。
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_auto_compact_percent", comments = "Stage D2: compact triggers when spareSize > trackedSectorBytes * this ratio (O_LINEAR only). Default 0.6.")
    public static double linearAutoCompactPercent = 0.6;

    // Mili end

    @DoNotLoad
    public static OptimizedLinearRegionFileFlusher olinearFlusher = null;

    // Mili - centralized, null-safe flusher shutdown. Invoked from MinecraftServer.stopPart2()
    // (before region files are closed) and from the JVM shutdown hook (safety net). Idempotent:
    // OptimizedLinearRegionFileFlusher.shutdown() guards against double shutdown.
    public static void shutdownOlinearFlusher() {
        final OptimizedLinearRegionFileFlusher flusher = olinearFlusher;
        if (flusher != null) {
            flusher.shutdown();
        }
    }

    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> exs) {
        if (exs != null) {
            for (Exception e : exs) {
                if (e instanceof IllegalFormatConversionExceptionWithOrigin) {
                    throw new RuntimeException("Invalid region format: " + ((IllegalFormatConversionExceptionWithOrigin) e).getOrigin().toString());
                }
            }
        }

        if (regionFormat == EnumRegionFormat.O_LINEAR) {
            // Mili start - AdaptiveRuntime §5.6 (D-08)：flusher 以观察者钩子方式接入反馈环。
            // createFlusherObserver() 返回延迟转发观察者（runtime 未就绪时 no-op/fail-open）；
            // onFlusherCreated() 在 flusher 创建后晚绑定到 runtime（幂等；失败不阻断启动）。
            olinearFlusher = new OptimizedLinearRegionFileFlusher(
                    olinearIoThreadCount, 20, olinearIoFlushDelayMs, olinearMaxSyncAgeMs,
                    RuntimeBootstrap.createFlusherObserver());
            RuntimeBootstrap.onFlusherCreated(olinearFlusher);
            // Mili end

            checkCompressionLevel();

            // we don't need to consider that it will be reloaded more than once as this config is unreloadable
            // Mili - shutdown hook is a safety net for abnormal termination; the authoritative
            // shutdown happens in MinecraftServer.stopPart2() -> shutdownOlinearFlusher().
            Runtime.getRuntime().addShutdownHook(new Thread(RegionFormatConfig::shutdownOlinearFlusher));
        }
    }

    private static void checkCompressionLevel() {
        if (RegionFormatConfig.linearCompressionLevel > 22 || RegionFormatConfig.linearCompressionLevel < 1) {
            MinecraftServer.LOGGER.error("O_LINEAR region compression level should be between 1 and 22 in config: {}", RegionFormatConfig.linearCompressionLevel);
            MinecraftServer.LOGGER.error("Falling back to compression level 1.");
            RegionFormatConfig.linearCompressionLevel = 1;
        }
    }

    // Mili start - Auto-enable helper: 是否启用 o_linear 优化总开关？
    /**
     * @return {@code true} 当且仅当 {@code regionFormat == O_LINEAR} 且 {@link #linearOptimizationsEnabled} = true。
     *   各优化 site 在调用各自的细粒度开关前先调此方法，达成"切换 regionFormat = O_LINEAR 即自动启用所有优化"。
     */
    public static boolean isLinearOptimizationsActive() {
        return linearOptimizationsEnabled && regionFormat == EnumRegionFormat.O_LINEAR;
    }
    // Mili end
}