package fun.bm.mili.testplugin;

import fun.bm.mili.api.*;
import fun.bm.mili.api.world.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LMili Test Plugin - Demonstrates LMili API unified scheduling.
 * 
 * This plugin strictly uses LMili API for ALL task scheduling.
 * No self-created threads or ExecutorServices.
 * 
 * <p>Demonstrates both standard scheduler API and new World Scheduler API.
 */
public final class LMiliTestPlugin extends JavaPlugin {

    private final AtomicInteger asyncTaskCounter = new AtomicInteger();
    private final AtomicInteger syncTaskCounter = new AtomicInteger();
    private final AtomicInteger worldTaskCounter = new AtomicInteger();

    @Override
    public void onEnable() {
        if (!Mili.isSupported()) {
            getLogger().warning("LMili scheduler not available!");
        } else {
            getLogger().info("LMili scheduler detected: " + Mili.version());
        }

        getLogger().info("MiliPlugin supported: " + MiliPlugin.isMiliSupported(this));
        
        // Log World Scheduler API status
        if (MiliWorlds.isSupported()) {
            getLogger().info("MiliWorlds API supported: " + MiliWorlds.version());
        } else {
            getLogger().info("MiliWorlds API not supported (expected on non-multiworld servers)");
        }
        
        scheduleRecurringTask();
        getLogger().info("LMiliTestPlugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("LMiliTestPlugin disabled. Total async: " + asyncTaskCounter.get() + ", world tasks: " + worldTaskCounter.get());
    }

    private void scheduleRecurringTask() {
        if (!Mili.isSupported()) return;
        scheduleTask();
    }

    private void scheduleTask() {
        if (!isEnabled()) return;

        PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
        scheduler.runDelayed(() -> {
            if (!isEnabled()) return;
            int count = asyncTaskCounter.incrementAndGet();
            getLogger().info("Recurring task #" + count + " via LMili scheduler");
            scheduleTask();
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lmtask")) {
            return handleTaskCommand(sender, args);
        } else if (command.getName().equalsIgnoreCase("lmtps")) {
            return handleTpsCommand(sender);
        } else if (command.getName().equalsIgnoreCase("lmworld")) {
            return handleWorldCommand(sender, args);
        }
        return false;
    }

    private boolean handleTaskCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("[LMili] /lmtask <async|sync|metrics>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "async" -> testAsyncTask(sender);
            case "sync" -> testSyncTask(sender);
            case "metrics" -> showMetrics(sender);
            default -> sender.sendMessage("[LMili] Use: async, sync, metrics");
        }
        return true;
    }

    private void testAsyncTask(CommandSender sender) {
        if (!checkMiliSupported(sender)) return;

        PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
        int taskId = asyncTaskCounter.incrementAndGet();
        long startTime = System.nanoTime();

        scheduler.runAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long duration = System.nanoTime() - startTime;
            scheduler.runDelayed(() -> {
                sender.sendMessage("[LMili] Async #" + taskId + " done in " + (duration / 1_000_000) + "ms");
            }, 0, TimeUnit.MILLISECONDS);
        });

        sender.sendMessage("[LMili] Async task #" + taskId + " submitted");
    }

    private void testSyncTask(CommandSender sender) {
        if (!checkMiliSupported(sender)) return;
        PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
        int taskId = syncTaskCounter.incrementAndGet();
        long startTime = System.nanoTime();

        SyncTaskResult<String> result = scheduler.runSync(() -> {
            int sum = 0;
            for (int i = 0; i < 1000; i++) {
                sum += i;
            }
            return "Sum=" + sum;
        });

        long duration = System.nanoTime() - startTime;
        if (result.isSuccess()) {
            sender.sendMessage("[LMili] Sync #" + taskId + " result: " + result.value() + " (" + (duration / 1_000_000) + "ms)");
        } else {
            sender.sendMessage("[LMili] Sync #" + taskId + " failed: " + result.failureReason());
        }
    }

    private void showMetrics(CommandSender sender) {
        if (!checkMiliSupported(sender)) return;
        PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
        UnifiedSchedulerAPI.SchedulerMetrics metrics = scheduler.metrics();
        sender.sendMessage("======== LMili Scheduler Metrics ========");
        sender.sendMessage("Owner: " + metrics.owner().value());
        sender.sendMessage("Submitted: " + metrics.tasksSubmitted());
        sender.sendMessage("Running: " + metrics.tasksRunning());
        sender.sendMessage("Queued: " + metrics.tasksQueued());
        sender.sendMessage("Completed: " + metrics.tasksCompleted());
        sender.sendMessage("Failed: " + metrics.tasksFailed());
        sender.sendMessage("Avg Exec: " + String.format("%.2fms", metrics.averageExecutionMs()));
        sender.sendMessage("Success Rate: " + String.format("%.1f%%", metrics.successRate() * 100));
    }

    private boolean handleTpsCommand(CommandSender sender) {
        double[] tps = Bukkit.getTPS();
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        sender.sendMessage("======== Server Status ========");
        sender.sendMessage("TPS (1m/5m/15m): " + String.format("%.2f / %.2f / %.2f", tps[0], tps[1], tps[2]));
        sender.sendMessage("Memory: " + usedMemory + "MB / " + maxMemory + "MB");
        sender.sendMessage("Players: " + Bukkit.getOnlinePlayers().size());
        sender.sendMessage("LMili: " + Mili.isSupported());
        if (Mili.isSupported()) {
            sender.sendMessage("LMili Version: " + Mili.version());
        }
        return true;
    }

    // ==================== World Scheduler API Demo ====================

    private boolean handleWorldCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("[LMili] /lmworld <status|config|task|metrics>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> showWorldApiStatus(sender);
            case "config" -> showWorldConfig(sender);
            case "task" -> testWorldTask(sender);
            case "metrics" -> showWorldMetrics(sender);
            default -> sender.sendMessage("[LMili] Use: status, config, task, metrics");
        }
        return true;
    }

    private void showWorldApiStatus(CommandSender sender) {
        sender.sendMessage("======== MiliWorlds API Status ========");
        sender.sendMessage("Supported: " + MiliWorlds.isSupported());
        sender.sendMessage("Version: " + MiliWorlds.version());
        
        if (sender instanceof Player player) {
            World world = player.getWorld();
            sender.sendMessage("Current World: " + world.getName());
            
            WorldTickConfig config = MiliWorlds.getWorldConfig(world);
            sender.sendMessage("TPS Target: " + config.tpsTarget());
            sender.sendMessage("CPU Budget: " + (config.cpuBudgetNanos() / 1_000_000) + "ms");
            sender.sendMessage("Parallel Tick: " + config.parallelTickEnabled());
        }
        
        sender.sendMessage("Registered Worlds: " + MiliWorlds.getRegisteredWorlds().size());
        for (World w : MiliWorlds.getRegisteredWorlds()) {
            sender.sendMessage("  - " + w.getName());
        }
    }

    private void showWorldConfig(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[LMili] Only players can use this command");
            return;
        }
        
        World world = player.getWorld();
        WorldTickConfig config = MiliWorlds.getWorldConfig(world);
        
        sender.sendMessage("======== World Config: " + world.getName() + " ========");
        sender.sendMessage("TPS Target: " + config.tpsTarget());
        sender.sendMessage("Tick Interval: " + String.format("%.2fms", config.tickIntervalMs()));
        sender.sendMessage("CPU Budget: " + (config.cpuBudgetNanos() / 1_000_000) + "ms");
        sender.sendMessage("Parallel Tick: " + config.parallelTickEnabled());
        sender.sendMessage("Entity Priority: " + config.entityTickPriority());
        sender.sendMessage("Max Concurrent: " + config.maxConcurrentTasks());
        sender.sendMessage("Max Queue Depth: " + config.maxQueueDepth());
    }

    private void testWorldTask(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[LMili] Only players can use this command");
            return;
        }
        
        World world = player.getWorld();
        Location loc = player.getLocation();
        int taskId = worldTaskCounter.incrementAndGet();
        
        // Test async task via WorldSchedulerAPI
        long startTime = System.nanoTime();
        WorldSchedulerAPI.runAsync(world, () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long duration = System.nanoTime() - startTime;
            Bukkit.getScheduler().runTask(LMiliTestPlugin.this, () -> {
                sender.sendMessage("[LMili] World async #" + taskId + " done in " + (duration / 1_000_000) + "ms");
            });
        });
        
        // Test sync task via WorldSchedulerAPI
        WorldSyncTaskResult<String> result = WorldSchedulerAPI.runSync(world, () -> {
            int sum = 0;
            for (int i = 0; i < 500; i++) {
                sum += i;
            }
            return "WorldSum=" + sum;
        });
        
        if (result.isSuccess()) {
            sender.sendMessage("[LMili] World sync #" + taskId + " result: " + result.result().orElse("null"));
        } else {
            sender.sendMessage("[LMili] World sync #" + taskId + " failed: " + result.error().orElse("unknown"));
        }
        
        sender.sendMessage("[LMili] World tasks #" + taskId + " submitted in " + world.getName());
    }

    private void showWorldMetrics(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[LMili] Only players can use this command");
            return;
        }
        
        World world = player.getWorld();
        WorldSchedulerMetrics metrics = WorldSchedulerAPI.metrics(world);
        
        sender.sendMessage("======== World Metrics: " + world.getName() + " ========");
        sender.sendMessage("Submitted: " + metrics.tasksSubmitted());
        sender.sendMessage("Running: " + metrics.tasksRunning());
        sender.sendMessage("Queued: " + metrics.tasksQueued());
        sender.sendMessage("Completed: " + metrics.tasksCompleted());
        sender.sendMessage("Failed: " + metrics.tasksFailed());
        sender.sendMessage("Avg Exec: " + String.format("%.2fms", metrics.averageExecutionMs()));
        sender.sendMessage("Success Rate: " + String.format("%.1f%%", metrics.successRate() * 100));
        sender.sendMessage("TPS Target: " + metrics.tpsTarget());
        sender.sendMessage("CPU Budget: " + (metrics.cpuBudgetNanos() / 1_000_000) + "ms");
    }

    private boolean checkMiliSupported(CommandSender sender) {
        if (!Mili.isSupported()) {
            sender.sendMessage("[LMili] LMili scheduler not available!");
            return false;
        }
        return true;
    }
}
