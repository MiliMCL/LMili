package fun.bm.mili.lmili.command;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.tps.DynamicEntityLimiter;
import fun.bm.mili.lmili.runtime.tps.TPSStabilizer;
import fun.bm.mili.lmili.runtime.tps.TickMonitorHook;
import fun.bm.mili.utils.performance.TPSTracker;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * TPS 稳定器命令 —— 查看和控制 TPS 稳定器状态。
 *
 * <p>命令列表：
 * <ul>
 *   <li>{@code /tpsstability status} - 查看 TPS 稳定器状态</li>
 *   <li>{@code /tpsstability enable} - 启用 TPS 稳定器</li>
 *   <li>{@code /tpsstability disable} - 禁用 TPS 稳定器</li>
 *   <li>{@code /tpsstability reset} - 重置统计信息</li>
 * </ul>
 *
 * @since 2.0.0
 */
public class TPSStabilityCommand implements CommandExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            showStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> showStatus(sender);
            case "enable" -> {
                TPSStabilizer.getInstance().setEnabled(true);
                TickMonitorHook.getInstance().setMonitoring(true);
                sender.sendMessage(ChatColor.GREEN + "[TPS稳定器] 已启用");
            }
            case "disable" -> {
                TPSStabilizer.getInstance().setEnabled(false);
                TickMonitorHook.getInstance().setMonitoring(false);
                sender.sendMessage(ChatColor.YELLOW + "[TPS稳定器] 已禁用");
            }
            case "reset" -> {
                TPSStabilizer.getInstance().reset();
                TickMonitorHook.getInstance().reset();
                DynamicEntityLimiter.getInstance().reset();
                sender.sendMessage(ChatColor.GREEN + "[TPS稳定器] 统计信息已重置");
            }
            default -> sender.sendMessage(ChatColor.RED + "用法: /tpsstability <status|enable|disable|reset>");
        }

        return true;
    }

    /**
     * 显示 TPS 稳定器状态。
     */
    private void showStatus(CommandSender sender) {
        TPSStabilizer stabilizer = TPSStabilizer.getInstance();
        TickMonitorHook monitor = TickMonitorHook.getInstance();
        DynamicEntityLimiter limiter = DynamicEntityLimiter.getInstance();

        TPSStabilizer.Stats stats = stabilizer.getStats();
        DynamicEntityLimiter.Stats limiterStats = limiter.getStats();

        // 颜色：根据 TPS 状态选择颜色
        ChatColor tpsColor = stats.currentLevel().ordinal() <= 1 ? ChatColor.GREEN :
                           stats.currentLevel().ordinal() <= 2 ? ChatColor.YELLOW : ChatColor.RED;

        sender.sendMessage(ChatColor.GOLD + "======= TPS 稳定器状态 =======");
        sender.sendMessage(ChatColor.YELLOW + "TPS: " + tpsColor + String.format("%.2f / %.1f",
                TPSTracker.getTPS(), TPSStabilizer.TARGET_TPS));
        sender.sendMessage(ChatColor.YELLOW + "状态: " + ChatColor.WHITE +
                (stabilizer.isEnabled() ? (ChatColor.GREEN + "已启用") : (ChatColor.RED + "已禁用")));
        sender.sendMessage(ChatColor.YELLOW + "降级等级: " + ChatColor.WHITE + stats.currentLevel());
        sender.sendMessage(ChatColor.YELLOW + "Tick 预算: " + ChatColor.WHITE +
                (stats.currentBudgetNanos() / 1_000_000) + "ms");
        sender.sendMessage(ChatColor.YELLOW + "平均 Tick: " + ChatColor.WHITE +
                String.format("%.2f", stats.averageTickDuration() / 1_000_000.0) + "ms");
        sender.sendMessage(ChatColor.YELLOW + "P99 Tick: " + ChatColor.WHITE +
                String.format("%.2f", stats.p99TickDuration() / 1_000_000.0) + "ms");
        sender.sendMessage(ChatColor.GREEN + "------- PI 控制器 -------");
        sender.sendMessage(ChatColor.YELLOW + "PI 输出: " + ChatColor.WHITE + String.format("%.4f", stats.piOutput()));
        sender.sendMessage(ChatColor.YELLOW + "积分累积: " + ChatColor.WHITE + String.format("%.4f", stats.piIntegral()));
        sender.sendMessage(ChatColor.GREEN + "------- 追赶机制 -------");
        sender.sendMessage(ChatColor.YELLOW + "追赶模式: " + ChatColor.WHITE + stats.catchupMode());
        sender.sendMessage(ChatColor.YELLOW + "追赶倍率: " + ChatColor.WHITE + String.format("%.2fx", stabilizer.getCatchupMultiplier()));
        sender.sendMessage(ChatColor.YELLOW + "建议速率: " + ChatColor.WHITE + String.format("%.2fx", stabilizer.getRecommendedTickRate()));
        sender.sendMessage(ChatColor.YELLOW + "追赶次数: " + ChatColor.WHITE + stats.catchupActivations());
        sender.sendMessage(ChatColor.YELLOW + "紧急次数: " + ChatColor.WHITE + stats.emergencyActivations());
        sender.sendMessage(ChatColor.YELLOW + "追赶 Tick: " + ChatColor.WHITE + stats.catchupTicks());
        sender.sendMessage(ChatColor.GREEN + "------- 统计信息 -------");
        sender.sendMessage(ChatColor.YELLOW + "总 Tick: " + ChatColor.WHITE + stats.totalTicks());
        sender.sendMessage(ChatColor.YELLOW + "降级次数: " + ChatColor.WHITE + stats.degradeCount());
        sender.sendMessage(ChatColor.YELLOW + "恢复次数: " + ChatColor.WHITE + stats.recoveryCount());
        sender.sendMessage(ChatColor.YELLOW + "连续超时: " + ChatColor.WHITE + stats.consecutiveOverruns());
        sender.sendMessage(ChatColor.GOLD + "------- 实体限制器 -------");
        sender.sendMessage(ChatColor.YELLOW + "实体数: " + ChatColor.WHITE +
                limiterStats.currentEntities() + " / " + limiterStats.maxEntities());
        sender.sendMessage(ChatColor.YELLOW + "清理次数: " + ChatColor.WHITE + limiterStats.totalCleaned());
    }
}
