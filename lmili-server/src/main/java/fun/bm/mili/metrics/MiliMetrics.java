package fun.bm.mili.metrics;

import fun.bm.mili.config.modules.misc.BStatsConfig;
import org.bstats.MetricsBase;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Properties;
import java.util.UUID;

/**
 * bStats metrics class for Mili server implementation.
 * Uses official bStats library for data collection and reporting.
 */
public class MiliMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger("MiliMetrics");
    private static MetricsBase metricsBase;
    private static volatile boolean enabled = false;

    /**
     * Initialize bStats metrics with configured plugin ID.
     */
    public static void init() {
        int pluginId = BStatsConfig.pluginId;
        if (pluginId <= 0) {
            LOGGER.info("[MiliMetrics] bStats disabled (pluginId <= 0)");
            return;
        }
        start(pluginId);
    }

    /**
     * Initialize bStats metrics with a specific plugin ID.
     */
    public static void init(int defaultPluginId) {
        if (defaultPluginId <= 0) return;
        start(defaultPluginId);
    }

    private static void start(int pluginId) {
        if (enabled) return;
        enabled = true;

        String serverUUID = getServerUUID();

        metricsBase = new MetricsBase(
                "server-implementation",
                serverUUID,
                pluginId,
                true,
                MiliMetrics::appendPlatformData,
                MiliMetrics::appendServiceData,
                null,
                () -> true,
                (message, error) -> LOGGER.warn("[MiliMetrics] {}", message, error),
                (message) -> LOGGER.info("[MiliMetrics] {}", message),
                false,
                false,
                false,
                false
        );

        // Add custom charts
        metricsBase.addCustomChart(new SimplePie("java_version", () -> System.getProperty("java.version")));
        metricsBase.addCustomChart(new SimplePie("os_name", () -> System.getProperty("os.name")));
        metricsBase.addCustomChart(new SimplePie("os_arch", () -> System.getProperty("os.arch")));
        metricsBase.addCustomChart(new SimplePie("mc_version", () -> Bukkit.getBukkitVersion().split("-")[0]));

        LOGGER.info("[MiliMetrics] Started bStats metrics (pluginId={})", pluginId);
    }

    private static void appendPlatformData(JsonObjectBuilder builder) {
        builder.appendField("playerAmount", Bukkit.getOnlinePlayers().size());
        builder.appendField("managedServers", Bukkit.getWorlds().size());
        builder.appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
        builder.appendField("minecraftVersion", Bukkit.getBukkitVersion().split("-")[0]);

        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private static void appendServiceData(JsonObjectBuilder builder) {
        builder.appendField("pluginVersion", getBukkitVersion());
    }

    private static String getServerUUID() {
        try {
            File file = new File(Bukkit.getWorldContainer(), "bStats/metricsId.txt");
            if (file.exists()) {
                String id = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
            String id = UUID.randomUUID().toString();
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(file.toPath(), id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return id;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static String getBukkitVersion() {
        try {
            String version = Bukkit.getVersion();
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    /**
     * Add a custom chart to bStats reporting.
     */
    public static void addCustomChart(CustomChart chart) {
        if (metricsBase != null) {
            metricsBase.addCustomChart(chart);
        }
    }

    /**
     * Shutdown bStats metrics scheduler.
     */
    public static void shutdown() {
        if (metricsBase != null) {
            metricsBase.shutdown();
            metricsBase = null;
        }
        enabled = false;
    }
}
