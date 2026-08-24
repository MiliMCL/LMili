package fun.bm.mili.lmili.commands.control;

import fun.bm.mili.lmili.runtime.policy.PolicyController;
import fun.bm.mili.lmili.runtime.policy.PressureStateMachine;
import fun.bm.mili.lmili.runtime.policy.RuntimePolicySnapshot;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

/**
 * /lmili control status —— 只读面板（§3.9；只读权限 lmili.control.read）。
 *
 * <p><strong>D-03</strong>：只经 PolicyController 的 policy 包 API 读取（snapshot/
 * stateMachine/circuitBreakerStatus/auditCount/isFrozen）；不 import runtime.control
 * （panelSnapshot 的 v2 字段由 Web 面板消费）。
 */
public class StatusSubcommand extends ControlSubcommand {

    public StatusSubcommand(ControlCommand parent) {
        super("status", parent);
    }

    @Override
    protected String requiredPermission() {
        return ControlCommand.PERM_READ;
    }

    @Override
    public boolean execute(@NotNull CommandContext context) {
        final CommandSender sender = context.getSender();
        final PolicyController policy = parent.policy();
        if (policy == null) {
            reply(sender, "[Control] Adaptive runtime not available", 255, 85, 0);
            return true;
        }
        try {
            final RuntimePolicySnapshot s = policy.snapshot();
            final PressureStateMachine sm = policy.stateMachine();
            reply(sender, "=== Mili Adaptive Runtime ===", 85, 255, 255);
            reply(sender, String.format("Policy version: %d (appliedBy: %s)",
                    s.version(), s.appliedBy()), 255, 255, 255);
            reply(sender, String.format("Pressure: %s  [locked=%s, transitionsInWindow=%d, failures=%d]",
                    s.pressure(),
                    sm.isLocked() ? "YES (lock until +" + (sm.lockedUntilMillis() - System.currentTimeMillis()) + "ms)" : "no",
                    sm.transitionsInWindow(), sm.failureCount()), 255, 255, 255);
            reply(sender, String.format("TPS target: %.1f | CPU budget: %d%% | IO budget: %d%%",
                    fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler.tpsTarget,
                    s.cpuBudgetPct(), s.ioBudgetPct()), 255, 255, 255);
            reply(sender, String.format("Region tick budget: soft=%.1fms hard=%.1fms",
                    s.regionTickSoftBudgetNanos() / 1_000_000.0, s.regionTickHardBudgetNanos() / 1_000_000.0), 255, 255, 255);
            reply(sender, String.format("Scheduler workers: %d | IO workers: %d | maxFanOut: %d",
                    s.schedulerWorkers(), s.ioWorkers(), s.maxFanOut()), 255, 255, 255);
            reply(sender, String.format("ParallelTick: %s | OLinear: %s | EntityThrottle: %s | Backpressure: %s",
                    s.parallelTickEnabled() ? "ON" : "off",
                    s.oLinearEnabled() ? "ON" : "off",
                    s.entityThrottleEnabled() ? "ON" : "off",
                    s.backpressureActive() ? "ON" : "off"), 255, 255, 255);
            reply(sender, "Circuit breakers: " + policy.circuitBreakerStatus(), 255, 255, 255);
            reply(sender, String.format("Policy frozen: %s | Audit entries: %d",
                    policy.isFrozen() ? "YES" : "no", policy.auditCount()), 255, 255, 255);
        } catch (Throwable t) {
            reply(sender, "[Control] status error: " + t, 255, 0, 0);
        }
        return true;
    }
}
