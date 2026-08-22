package fun.bm.mili.lmili.api.identity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PluginRuntimeContextTest {

    @Test
    void activePluginHasSchedulerDomain() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.ACTIVE);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        assertTrue(ctx.schedulerDomain().acceptsSubmissions());
        assertFalse(ctx.schedulerDomain().enforceQuota());
        assertEquals(SchedulerDomain.DEFAULT_PRIORITY, ctx.schedulerDomain().priority());
        assertEquals(PermissionLevel.SCHEDULER, ctx.permissionContext().level());
    }

    @Test
    void conflictPluginIsHeavilyRestricted() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.CONFLICT);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        assertEquals(SchedulerDomain.CONFLICT_PRIORITY, ctx.schedulerDomain().priority());
        assertTrue(ctx.schedulerDomain().enforceQuota());
        assertEquals(1, ctx.schedulerDomain().maxConcurrentTasks());
        assertEquals(PermissionLevel.NONE, ctx.permissionContext().level());
    }

    @Test
    void observePluginIsLightlyRestricted() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.OBSERVE);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        assertEquals(SchedulerDomain.OBSERVE_PRIORITY, ctx.schedulerDomain().priority());
        assertTrue(ctx.schedulerDomain().acceptsSubmissions());
    }

    @Test
    void disabledPluginCannotSchedule() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.DISABLED);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        assertFalse(ctx.schedulerDomain().acceptsSubmissions());
        assertEquals(PermissionLevel.NONE, ctx.permissionContext().level());
        assertThrows(SecurityException.class, ctx::requireScheduleSlot);
    }

    @Test
    void addonSharesParentSchedulerDomain() {
        final PluginIdentity addon = makeAddon("xucy.mili.market", "xucy.mili");
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(addon);
        assertTrue(ctx.schedulerDomain().sharedWithParent());
        assertEquals(Optional.of(PluginId.parse("xucy.mili")),
                ctx.schedulerDomain().inheritedFrom());
    }

    @Test
    void observabilityCountsPermissionDenials() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.CONFLICT);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        final long before = ctx.observability().permissionDenials();
        try { ctx.requirePermission(PermissionLevel.SCHEDULER); }
        catch (final SecurityException ignored) { }
        assertEquals(before + 1, ctx.observability().permissionDenials());
    }

    @Test
    void quotaCountersIncrement() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.ACTIVE);
        final ResourceQuota quota = PluginRuntimeContext.forIdentity(id).resourceQuota();
        quota.onTaskSubmit();
        quota.onTaskSubmit();
        quota.onTaskSubmit();
        quota.onTaskStart();
        quota.onTaskStart();
        quota.onTaskFinish(1_000_000L, true);
        quota.onTaskFinish(500_000L, false);
        quota.onRejected();
        assertEquals(3L, quota.tasksSubmitted());
        assertEquals(0L, quota.tasksRunning());
        assertEquals(2L, quota.tasksCompleted());
        assertEquals(1L, quota.tasksFailed());
        assertEquals(1L, quota.rejectedCount());
        assertEquals(1_500_000L, quota.totalExecutionNanos());
    }

    @Test
    void requirePermissionThrowsWhenInsufficient() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.CONFLICT);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        assertThrows(SecurityException.class,
                () -> ctx.requirePermission(PermissionLevel.SCHEDULER));
        assertDoesNotThrow(() -> ctx.requirePermission(PermissionLevel.NONE));
    }

    @Test
    void byPluginNameIndexRoundTrip() {
        final PluginIdentity id = makePlugin("xucy.mili", PluginStatus.ACTIVE);
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(id);
        PluginRuntimeContext.registerForPlugin("XucyMili", ctx);
        assertSame(ctx, PluginRuntimeContext.forBukkitPlugin("XucyMili"));
        PluginRuntimeContext.unregisterForPlugin("XucyMili");
        assertNull(PluginRuntimeContext.forBukkitPlugin("XucyMili"));
    }

    // ---- helpers --------------------------------------------------------

    private static PluginIdentity makePlugin(final String id, final PluginStatus status) {
        final PluginId pid = PluginId.parse(id);
        final PluginIdentity identity = PluginIdentity.of(pid, pid.value(),
                "1.0.0", pid.publisher(),
                PluginType.PLUGIN, Optional.empty(), "lmili.json", null);
        return identity.withStatus(status);
    }

    private static PluginIdentity makeAddon(final String id, final String parent) {
        final PluginId pid = PluginId.parse(id);
        return PluginIdentity.of(pid, pid.value(), "1.0.0", pid.publisher(),
                PluginType.ADDON, Optional.of(PluginId.parse(parent)),
                "lmili.json", null);
    }
}