package fun.bm.mili.lmili.thread.scheduler;

import fun.bm.mili.lmili.thread.scheduler.execute.RegionState;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState.ExecutionToken;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState.Phase;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionQueue;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LATEST-02 / LATEST-03 / Invariant 5 测试: Region 生命周期 + drain + barrier。
 */
public final class RegionLifecycleTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Region Lifecycle Tests ===\n");

        testTryBeginDrainRejectsNewTasks();
        testTryCloseWhenIdleAndEmpty();
        testTryCloseFailsWhenRunning();
        testTryCloseFailsWhenQueued();
        testForceCloseAlwaysSucceeds();
        testDrainReleasesQueuedCount();
        testDrainOnEmptyQueue();
        testQueueCountMatchesPushedTasks();
        testCloseCallbackFiresOnSuccessfulClose();
        testCloseCallbackFiresOnce();
        testCloseCallbackNotFiredOnFailedClose();
        testReRegisterAfterClose();
        testDeactivateImmediatelyClosesIfEmpty();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testTryBeginDrainRejectsNewTasks() {
        RegionState state = new RegionState();
        assertTrue("begin drain should succeed", state.tryBeginDrain());
        assertEquals("phase should be DRAINING", Phase.DRAINING, state.getPhase());
        assertTrue("accept task should fail when DRAINING", !state.tryAcceptTask());
        pass("testTryBeginDrainRejectsNewTasks");
    }

    static void testTryCloseWhenIdleAndEmpty() {
        RegionState state = new RegionState();
        state.tryBeginDrain();
        assertTrue("tryClose should succeed when IDLE and empty", state.tryClose());
        assertEquals("phase should be CLOSED", Phase.CLOSED, state.getPhase());
        pass("testTryCloseWhenIdleAndEmpty");
    }

    static void testTryCloseFailsWhenRunning() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("acquire", token);
        state.tryBeginDrain();

        assertTrue("tryClose should fail when RUNNING", !state.tryClose());
        assertEquals("phase should still be DRAINING", Phase.DRAINING, state.getPhase());

        token.release();
        assertEquals("after release, phase should be CLOSED", Phase.CLOSED, state.getPhase());
        pass("testTryCloseFailsWhenRunning");
    }

    static void testTryCloseFailsWhenQueued() {
        RegionState state = new RegionState();
        state.tryAcceptTask();
        state.tryBeginDrain();

        assertTrue("tryClose should fail when queued > 0", !state.tryClose());
        assertEquals("phase should still be DRAINING", Phase.DRAINING, state.getPhase());
        assertEquals("queued should be 1", 1, state.getQueuedCount());

        state.releaseTask();
        assertEquals("after releaseTask, phase should be CLOSED", Phase.CLOSED, state.getPhase());
        pass("testTryCloseFailsWhenQueued");
    }

    static void testForceCloseAlwaysSucceeds() {
        RegionState state = new RegionState();
        state.tryAcceptTask();
        state.forceClose();
        assertEquals("forceClose -> CLOSED", Phase.CLOSED, state.getPhase());
        pass("testForceCloseAlwaysSucceeds");
    }

    static void testDrainReleasesQueuedCount() {
        RegionQueue queue = new RegionQueue(42L);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Runnable r = () -> {};
            queue.push(RegionTask.builder(42L).task(r).name("task-" + idx).build());
        }
        assertEquals("queued should be 5", 5, queue.regionState().getQueuedCount());

        List<RegionTask> remaining = queue.drain();
        assertEquals("drained 5 tasks", 5, remaining.size());
        assertEquals("queued should be 0 after drain", 0, queue.regionState().getQueuedCount());
        pass("testDrainReleasesQueuedCount");
    }

    static void testDrainOnEmptyQueue() {
        RegionQueue queue = new RegionQueue(1L);
        List<RegionTask> remaining = queue.drain();
        assertEquals("drain empty queue returns empty list", 0, remaining.size());
        pass("testDrainOnEmptyQueue");
    }

    static void testQueueCountMatchesPushedTasks() {
        RegionQueue queue = new RegionQueue(100L);
        Runnable r = () -> {};
        queue.push(RegionTask.builder(100L).task(r).build());
        queue.push(RegionTask.builder(100L).task(r).build());
        queue.push(RegionTask.builder(100L).task(r).build());
        assertEquals("queued = 3", 3, queue.regionState().getQueuedCount());

        queue.pop();
        assertEquals("queued = 2 after pop", 2, queue.regionState().getQueuedCount());

        queue.steal();
        assertEquals("queued = 1 after steal", 1, queue.regionState().getQueuedCount());
        pass("testQueueCountMatchesPushedTasks");
    }

    static void testCloseCallbackFiresOnSuccessfulClose() {
        RegionState state = new RegionState();
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        state.setCloseCallback(() -> callbackFired.set(true));

        state.tryBeginDrain();
        state.tryClose();

        assertTrue("close callback should fire", callbackFired.get());
        pass("testCloseCallbackFiresOnSuccessfulClose");
    }

    static void testCloseCallbackFiresOnce() {
        RegionState state = new RegionState();
        AtomicInteger fireCount = new AtomicInteger(0);
        state.setCloseCallback(() -> fireCount.incrementAndGet());

        state.tryBeginDrain();
        state.tryClose();
        state.tryClose();

        assertEquals("callback should fire exactly once", 1, fireCount.get());
        pass("testCloseCallbackFiresOnce");
    }

    static void testCloseCallbackNotFiredOnFailedClose() {
        RegionState state = new RegionState();
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        state.setCloseCallback(() -> callbackFired.set(true));

        state.tryAcceptTask();
        state.tryBeginDrain();
        state.tryClose();

        assertTrue("callback should NOT fire on failed close", !callbackFired.get());
        pass("testCloseCallbackNotFiredOnFailedClose");
    }

    static void testReRegisterAfterClose() {
        RegionQueue queue = new RegionQueue(200L);
        queue.deactivate();
        queue.tryClose();

        RegionQueue newQueue = new RegionQueue(200L);
        assertTrue("new queue should be active", newQueue.isActive());
        pass("testReRegisterAfterClose");
    }

    static void testDeactivateImmediatelyClosesIfEmpty() {
        RegionQueue queue = new RegionQueue(300L);
        assertTrue("initially active", queue.isActive());

        queue.deactivate();
        assertTrue("deactivate on empty queue -> CLOSED", !queue.isActive());
        assertEquals("phase should be CLOSED", Phase.CLOSED, queue.regionState().getPhase());
        pass("testDeactivateImmediatelyClosesIfEmpty");
    }

    // ---- assertion helpers ----

    static void checkNotNull(String msg, Object obj) {
        if (obj == null) fail(msg + " (got null)");
    }

    static void assertTrue(String msg, boolean condition) {
        if (!condition) fail(msg);
    }

    static void assertEquals(String msg, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            fail(msg + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    static void pass(String name) {
        passed++;
        System.out.println("[PASS] " + name);
    }

    static void fail(String msg) {
        failed++;
        System.out.println("[FAIL] " + msg);
    }
}
