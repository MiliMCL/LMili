package io.papermc.paper.threadedregions.commands;

import ca.spottedleaf.common.time.TickData;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.lmili.thread.regiontick.RegionTickDispatcher;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.ClickEvent.Payload;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import org.jetbrains.annotations.NotNull;

/**
 * /tps 命令 —— 显示 Mili 服务器健康状态。
 *
 * <p>适配 Mili 统一调度器，显示：
 * <ul>
 *   <li>服务器概览（运行时间、内存、玩家）</li>
 *   <li>Mili 调度器状态（任务、区域、Work-Stealing）</li>
 *   <li>RegionTickPool 状态（chunk tick、超时）</li>
 *   <li>最低 TPS 区域（可点击传送）</li>
 * </ul>
 */
public final class CommandServerHealth extends Command {

    private static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.00");
    });
    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.0");
    });
    private static final ThreadLocal<DecimalFormat> NO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0");
    });

    private static final TextColor HEADER = TextColor.color(79, 164, 240);
    private static final TextColor PRIMARY = TextColor.color(48, 145, 237);
    private static final TextColor SECONDARY = TextColor.color(104, 177, 240);
    private static final TextColor INFORMATION = TextColor.color(180, 220, 255);
    private static final TextColor LIST = TextColor.color(33, 97, 188);
    private static final TextColor SUCCESS = TextColor.color(80, 200, 120);
    private static final TextColor WARNING = TextColor.color(255, 200, 0);
    private static final TextColor DANGER = TextColor.color(255, 80, 80);

    public CommandServerHealth() {
        super("tps");
        this.setUsage("/<command> [server/region] [lowest regions to display]");
        this.setDescription("Reports information about server health.");
        this.setPermission("bukkit.command.tps");
    }

    private static Component formatRegionInfo(final String prefix, final double util, final double mspt, final double tps,
                                              final boolean newline) {
        return Component.text()
                .append(Component.text(prefix, PRIMARY, TextDecoration.BOLD))
                .append(Component.text(ONE_DECIMAL_PLACES.get().format(util * 100.0), getUtilColor(util)))
                .append(Component.text("% " + I18nManager.get("tps.region.util"), PRIMARY))
                .append(Component.text(" | ", SECONDARY))
                .append(Component.text(TWO_DECIMAL_PLACES.get().format(mspt), getMsptColor(mspt)))
                .append(Component.text(" " + I18nManager.get("tps.region.mspt"), PRIMARY))
                .append(Component.text(" | ", SECONDARY))
                .append(Component.text(TWO_DECIMAL_PLACES.get().format(tps), getTpsColor(tps)))
                .append(Component.text(" " + I18nManager.get("tps.region.tps") + (newline ? "\n" : ""), PRIMARY))
                .build();
    }

    private static Component formatRegionStats(final TickRegions.RegionStats stats, final boolean newline) {
        return Component.text()
                .append(Component.text(I18nManager.get("tps.region.chunks") + ": ", PRIMARY))
                .append(Component.text(NO_DECIMAL_PLACES.get().format((long)stats.getChunkCount()), INFORMATION))
                .append(Component.text("  " + I18nManager.get("tps.region.players") + ": ", PRIMARY))
                .append(Component.text(NO_DECIMAL_PLACES.get().format((long)stats.getPlayerCount()), INFORMATION))
                .append(Component.text("  " + I18nManager.get("tps.region.entities") + ": ", PRIMARY))
                .append(Component.text(NO_DECIMAL_PLACES.get().format((long)stats.getEntityCount()) + (newline ? "\n" : ""), INFORMATION))
                .build();
    }

    private static boolean executeRegion(final CommandSender sender, final String commandLabel, final String[] args) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            sender.sendMessage(Component.text(I18nManager.get("tps.error.not.in.region"), NamedTextColor.RED));
            return true;
        }

        final long currTime = System.nanoTime();

        final TickData.TickReportData report15s = region.getData().getRegionSchedulingHandle().getTickReport15s(currTime);
        final TickData.TickReportData report1m = region.getData().getRegionSchedulingHandle().getTickReport1m(currTime);

        final ServerLevel world = region.regioniser.world;
        final ChunkPos chunkCenter = region.getCenterChunk();
        final int centerBlockX = ((chunkCenter.x() << 4) | 7);
        final int centerBlockZ = ((chunkCenter.z() << 4) | 7);

        final double util15s = report15s.utilisation();
        final double tps15s = report15s.tpsData().segmentAll().average();
        final double mspt15s = report15s.timePerTickData().segmentAll().average() / 1.0E6;

        final double util1m = report1m.utilisation();
        final double tps1m = report1m.tpsData().segmentAll().average();
        final double mspt1m = report1m.timePerTickData().segmentAll().average() / 1.0E6;

        final String location = world.getWorld().getName() + " (" + centerBlockX + ", " + centerBlockZ + ")";

        final Component line = Component.text()
                .append(Component.text(I18nManager.get("tps.region.around.block") + " ", PRIMARY))
                .append(Component.text(location, INFORMATION))
                .append(Component.text(":\n", PRIMARY))

                .append(formatRegionInfo("15s: ", util15s, mspt15s, tps15s, true))
                .append(formatRegionInfo("1m:  ", util1m, mspt1m, tps1m, true))
                .append(formatRegionStats(region.getData().getRegionStats(), false))
                .build();

        sender.sendMessage(line);

        return true;
    }

    private static boolean executeServer(final CommandSender sender, final String commandLabel, final String[] args) {
        final int lowestRegionsCount;
        if (args.length < 2) {
            lowestRegionsCount = 3;
        } else {
            try {
                lowestRegionsCount = Integer.parseInt(args[1]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage(Component.text(I18nManager.get("tps.error.invalid.count", args[1]), NamedTextColor.RED));
                return true;
            }
        }

        // 收集所有 region
        final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions =
                new ArrayList<>();

        for (final World bukkitWorld : Bukkit.getWorlds()) {
            final ServerLevel world = ((CraftWorld)bukkitWorld).getHandle();
            world.regioniser.computeForAllRegions(regions::add);
        }

        // 计算 TPS 统计
        final double minTps;
        final double medianTps;
        final double maxTps;
        double totalUtil = 0.0;

        final DoubleArrayList tpsByRegion = new DoubleArrayList();
        final List<TickData.TickReportData> reportsByRegion = new ArrayList<>();
        final int maxThreadCount = TickRegions.getScheduler().getTotalThreadCount();

        final long currTime = System.nanoTime();
        final TickData.TickReportData globalTickReport = RegionizedServer.getGlobalTickData().getTickReport15s(currTime);

        // 内存信息
        final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        final MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        final long usedMemory = heapUsage.getUsed() / (1024 * 1024);
        final long maxMemory = heapUsage.getMax() / (1024 * 1024);
        final double memPercent = (double) usedMemory / maxMemory * 100.0;

        // 运行时间
        final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        final long uptime = runtimeBean.getUptime();
        final String uptimeStr = formatUptime(uptime);

        // 收集每个 region 的 TPS
        for (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : regions) {
            final TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport15s(currTime);
            tpsByRegion.add(report == null ? 20.0 : report.tpsData().segmentAll().average());
            reportsByRegion.add(report);
            totalUtil += (report == null ? 0.0 : report.utilisation());
        }

        // Mili 调度器统计
        final RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
        final Map<String, Object> schedulerStats = dispatcher != null ? dispatcher.getStats() : Map.of();

        final double genRate = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkFullTask.genRate(currTime);
        final double loadRate = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkFullTask.loadRate(currTime);

        totalUtil += globalTickReport.utilisation();

        // 排序 region 按负载
        tpsByRegion.sort(null);
        if (!tpsByRegion.isEmpty()) {
            minTps = tpsByRegion.getDouble(0);
            maxTps = tpsByRegion.getDouble(tpsByRegion.size() - 1);
            final int middle = tpsByRegion.size() >> 1;
            if ((tpsByRegion.size() & 1) == 0) {
                medianTps = (tpsByRegion.getDouble(middle - 1) + tpsByRegion.getDouble(middle)) / 2.0;
            } else {
                medianTps = tpsByRegion.getDouble(middle);
            }
        } else {
            minTps = medianTps = maxTps = 20.0;
        }

        // 按负载排序 region
        final List<ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>>
                regionsByLoad = new ArrayList<>();

        for (int i = 0, len = regions.size(); i < len; ++i) {
            final TickData.TickReportData report = reportsByRegion.get(i);
            regionsByLoad.add(new ObjectObjectImmutablePair<>(regions.get(i), report));
        }

        regionsByLoad.sort((p1, p2) -> {
            final TickData.TickReportData report1 = p1.right();
            final TickData.TickReportData report2 = p2.right();
            final double util1 = report1 == null ? 0.0 : report1.utilisation();
            final double util2 = report2 == null ? 0.0 : report2.utilisation();
            return Double.compare(util2, util1);
        });

        // 统计区块和实体
        long totalChunks = 0;
        long totalEntities = 0;

        for (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : regions) {
            final TickRegions.RegionStats stats = region.getData().getRegionStats();
            totalChunks += stats.getChunkCount();
            totalEntities += stats.getEntityCount();
        }

        // 构建最低 TPS 区域列表
        final TextComponent.Builder lowestRegionsBuilder = Component.text();

        if (sender instanceof Player) {
            lowestRegionsBuilder.append(Component.text(" " + I18nManager.get("tps.server.click.to.teleport") + "\n", SECONDARY));
        }
        for (int i = 0, len = Math.min(lowestRegionsCount, regionsByLoad.size()); i < len; ++i) {
            final ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>
                    pair = regionsByLoad.get(i);

            final TickData.TickReportData report = pair.right();
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                    pair.left();

            if (report == null) continue;

            final ServerLevel world = region.regioniser.world;
            final ChunkPos chunkCenter = region.getCenterChunk();
            if (chunkCenter == null) continue;

            final int centerBlockX = ((chunkCenter.x() << 4) | 7);
            final int centerBlockZ = ((chunkCenter.z() << 4) | 7);
            final double util = report.utilisation();
            final double tps = report.tpsData().segmentAll().average();
            final double mspt = report.timePerTickData().segmentAll().average() / 1.0E6;

            final int yLoc = 80;
            final String location = world.getWorld().getName() + " (" + centerBlockX + ", " + centerBlockZ + ")";

            final Component line = Component.text()
                    .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                    .append(Component.text(I18nManager.get("tps.region.at") + " ", PRIMARY))
                    .append(Component.text(location, INFORMATION))
                    .append(Component.text(":\n", PRIMARY))

                    .append(Component.text("    ", PRIMARY))
                    .append(Component.text(ONE_DECIMAL_PLACES.get().format(util * 100.0), getUtilColor(util)))
                    .append(Component.text("% " + I18nManager.get("tps.region.util"), PRIMARY))
                    .append(Component.text(" | ", SECONDARY))
                    .append(Component.text(TWO_DECIMAL_PLACES.get().format(mspt), getMsptColor(mspt)))
                    .append(Component.text(" " + I18nManager.get("tps.region.mspt"), PRIMARY))
                    .append(Component.text(" | ", SECONDARY))
                    .append(Component.text(TWO_DECIMAL_PLACES.get().format(tps), getTpsColor(tps)))
                    .append(Component.text(" " + I18nManager.get("tps.region.tps") + "\n", PRIMARY))

                    .append(Component.text("    ", PRIMARY))
                    .append(formatRegionStats(region.getData().getRegionStats(), (i + 1) != len))
                    .build()

                    .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, Payload.string("/minecraft:execute as @s in " + world.getWorld().getKey().toString() + " run tp " + centerBlockX + ".5 " + yLoc + " " + centerBlockZ + ".5")))
                    .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text(I18nManager.get("tps.server.click.to.teleport") + " " + location, SECONDARY)));

            lowestRegionsBuilder.append(line);
        }

        // 构建完整消息
        sender.sendMessage(
                Component.text()
                        // 标题
                        .append(Component.text(I18nManager.get("tps.server.health.report"), HEADER, TextDecoration.BOLD))
                        .append(Component.text(" (" + I18nManager.get("tps.server.uptime") + ": ", SECONDARY))
                        .append(Component.text(uptimeStr, getUtilColor(totalUtil / (double)maxThreadCount)))
                        .append(Component.text(")\n", SECONDARY))

                        // 在线玩家
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.online.players") + ": ", PRIMARY))
                        .append(Component.text(Bukkit.getOnlinePlayers().size(), INFORMATION))
                        .append(Component.newline())

                        // 区域和区块统计
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.total.regions") + ": ", PRIMARY))
                        .append(Component.text(regions.size(), INFORMATION))
                        .append(Component.text(", ", PRIMARY))
                        .append(Component.text(I18nManager.get("tps.server.total.chunks") + ": ", PRIMARY))
                        .append(Component.text(NO_DECIMAL_PLACES.get().format(totalChunks), INFORMATION))
                        .append(Component.text(", ", PRIMARY))
                        .append(Component.text(I18nManager.get("tps.server.total.entities") + ": ", PRIMARY))
                        .append(Component.text(NO_DECIMAL_PLACES.get().format(totalEntities) + "\n", INFORMATION))

                        // 负载率
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.utilisation") + ": ", PRIMARY))
                        .append(Component.text(ONE_DECIMAL_PLACES.get().format(totalUtil * 100.0), getUtilColor(totalUtil / (double)maxThreadCount)))
                        .append(Component.text("% / ", SECONDARY))
                        .append(Component.text(ONE_DECIMAL_PLACES.get().format(maxThreadCount * 100.0), INFORMATION))
                        .append(Component.text("%\n", PRIMARY))

                        // Mili 调度器状态
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.mili.scheduler") + ": ", PRIMARY))
                        .append(Component.text(I18nManager.get("tps.server.active.regions") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("active_regions", "?")), INFORMATION))
                        .append(Component.text(", ", SECONDARY))
                        .append(Component.text(I18nManager.get("tps.server.worker.count") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("worker_count", "?")), INFORMATION))
                        .append(Component.text(", ", SECONDARY))
                        .append(Component.text(I18nManager.get("tps.server.dag.systems") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("dag_systems", "?")), INFORMATION))
                        .append(Component.newline())

                        // RegionTickPool 状态
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.region.tick.pool") + ": ", PRIMARY))
                        .append(Component.text(I18nManager.get("tps.server.total.ticks") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("total_ticks_dispatched", "?")), INFORMATION))
                        .append(Component.text(", ", SECONDARY))
                        .append(Component.text(I18nManager.get("tps.server.tick.errors") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("total_tick_errors", "?")), INFORMATION))
                        .append(Component.text(", ", SECONDARY))
                        .append(Component.text(I18nManager.get("tps.server.tick.timeouts") + "=", SECONDARY))
                        .append(Component.text(String.valueOf(schedulerStats.getOrDefault("chunk_tick_timeouts", "?")), INFORMATION))
                        .append(Component.newline())

                        // 区块加载速率
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.load.rate") + ": ", PRIMARY))
                        .append(Component.text(TWO_DECIMAL_PLACES.get().format(loadRate), INFORMATION))
                        .append(Component.text(", ", PRIMARY))
                        .append(Component.text(I18nManager.get("tps.server.gen.rate") + ": ", PRIMARY))
                        .append(Component.text(TWO_DECIMAL_PLACES.get().format(genRate) + "\n", INFORMATION))

                        // 内存
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.memory") + ": ", PRIMARY))
                        .append(Component.text(NO_DECIMAL_PLACES.get().format(usedMemory), getMemoryColor(memPercent)))
                        .append(Component.text(" " + I18nManager.get("tps.memory.mb"), getMemoryColor(memPercent)))
                        .append(Component.text(" / ", SECONDARY))
                        .append(Component.text(NO_DECIMAL_PLACES.get().format(maxMemory), INFORMATION))
                        .append(Component.text(" " + I18nManager.get("tps.memory.mb"), INFORMATION))
                        .append(Component.text(" (", SECONDARY))
                        .append(Component.text(I18nManager.get("tps.memory.percent", ONE_DECIMAL_PLACES.get().format(memPercent)), getMemoryColor(memPercent)))
                        .append(Component.text(")\n", SECONDARY))

                        // TPS 统计
                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.lowest.region.tps") + ": ", PRIMARY))
                        .append(Component.text(TWO_DECIMAL_PLACES.get().format(minTps) + "\n", getTpsColor(minTps)))

                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.median.region.tps") + ": ", PRIMARY))
                        .append(Component.text(TWO_DECIMAL_PLACES.get().format(medianTps) + "\n", getTpsColor(medianTps)))

                        .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                        .append(Component.text(I18nManager.get("tps.server.highest.region.tps") + ": ", PRIMARY))
                        .append(Component.text(TWO_DECIMAL_PLACES.get().format(maxTps) + "\n", getTpsColor(maxTps)))

                        // 最低 TPS 区域
                        .append(Component.text(I18nManager.get("tps.server.highest.utilisation.regions", Integer.toString(lowestRegionsCount)), HEADER, TextDecoration.BOLD))
                        .append(lowestRegionsBuilder.build())
                        .build()
        );

        return true;
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        // Mili start - sync locale from LanguageConfig before rendering
        try {
            Class<?> configClass = Class.forName("fun.bm.mili.config.modules.function.LanguageConfig");
            Object value = configClass.getField("lang").get(null);
            if (value instanceof String && !((String) value).isEmpty()) {
                I18nManager.setLocale((String) value);
            }
        } catch (Throwable ignored) {
            // LanguageConfig not available — use default locale
        }
        // Mili end
        final String type;
        if (args.length < 1) {
            type = "server";
        } else {
            type = args[0];
        }

        switch (type.toLowerCase(Locale.ROOT)) {
            case "server": {
                return executeServer(sender, commandLabel, args);
            }
            case "region": {
                if (!(sender instanceof Entity)) {
                    sender.sendMessage(Component.text(I18nManager.get("tps.error.console.region"), NamedTextColor.RED));
                    return true;
                }
                return executeRegion(sender, commandLabel, args);
            }
            default: {
                sender.sendMessage(Component.text(I18nManager.get("tps.error.invalid.type", args[0]), NamedTextColor.RED));
                return true;
            }
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) throws IllegalArgumentException {
        if (args.length == 0) {
            if (sender instanceof Entity) {
                return CommandUtil.getSortedList(Arrays.asList("server", "region"));
            } else {
                return CommandUtil.getSortedList(Arrays.asList("server"));
            }
        } else if (args.length == 1) {
            if (sender instanceof Entity) {
                return CommandUtil.getSortedList(Arrays.asList("server", "region"), args[0]);
            } else {
                return CommandUtil.getSortedList(Arrays.asList("server"), args[0]);
            }
        }
        return new ArrayList<>();
    }

    private static @NotNull String formatUptime(long uptimeMillis) {
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMillis);
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis) % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(I18nManager.get("tps.time.days", days));
        if (hours > 0) sb.append(" ").append(I18nManager.get("tps.time.hours", hours));
        if (minutes > 0) sb.append(" ").append(I18nManager.get("tps.time.minutes", minutes));
        sb.append(" ").append(I18nManager.get("tps.time.seconds", seconds));

        return sb.toString();
    }

    // 颜色辅助方法
    private static TextColor getTpsColor(double tps) {
        if (tps >= 19.0) return SUCCESS;
        if (tps >= 15.0) return WARNING;
        return DANGER;
    }

    private static TextColor getMsptColor(double mspt) {
        if (mspt <= 30.0) return SUCCESS;
        if (mspt <= 45.0) return WARNING;
        return DANGER;
    }

    private static TextColor getUtilColor(double util) {
        if (util <= 0.7) return SUCCESS;
        if (util <= 0.9) return WARNING;
        return DANGER;
    }

    private static TextColor getMemoryColor(double percent) {
        if (percent < 60) return SUCCESS;
        if (percent < 85) return WARNING;
        return DANGER;
    }
}
