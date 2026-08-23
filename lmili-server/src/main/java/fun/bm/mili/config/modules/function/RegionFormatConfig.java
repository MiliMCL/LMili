package fun.bm.mili.config.modules.function;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.IllegalFormatConversionExceptionWithOrigin;
import fun.bm.mili.lmili.config.flags.*;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import fun.bm.mili.lmili.enums.EnumRegionFormat;
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
    @ConfigInfo(name = "olinear_max_sync_age_ms", comments = "Forces a master-file sync after this many ms even while the region is being written continuously, bounding how much recent data a power failure can lose (Only works for O_LINEAR). Default 15000.")
    public static int olinearMaxSyncAgeMs = 15000;

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
            olinearFlusher = new OptimizedLinearRegionFileFlusher(olinearIoThreadCount, 20, olinearIoFlushDelayMs, olinearMaxSyncAgeMs);

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
}
