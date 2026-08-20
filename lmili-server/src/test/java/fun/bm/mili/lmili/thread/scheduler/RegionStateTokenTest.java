package fun.bm.mili.lmili.thread.scheduler;

import fun.bm.mili.lmili.thread.scheduler.execute.RegionState;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState.ExecutionToken;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState.ExecState;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LATEST-01 / LATEST-07 测试: ExecutionToken 生命周期不变量。
 */
public final class RegionStateTokenTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== RegionState ExecutionToken Lifecycle Tests ===\n");

        testAcquireRelease();
        testDoubleRelease();
        testTripleRelease();
        testConcurrentAcquireOnlyOneSucceeds();
        testReleaseAfterReleaseDoesNothing();
        testTransferOwnership();
        testTransferThenRelease();
        testGenerationMismatchReleasesFails();
        testAcquireAfterTransferSucceedsWithNewOwner();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testAcquireRelease() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);

        checkNotNull("acquire should return non-null token", token);
        assertEquals("state should be RUNNING", ExecState.RUNNING, state.getSnapshot().execState());
        assertEquals("owner should be 0", 0, state.getSnapshot().owner());

        token.release();

        assertEquals("state should be IDLE after release", ExecState.IDLE, state.getSnapshot().execState());
        assertEquals("owner should be -1 after release", -1, state.getSnapshot().owner());
        pass("testAcquireRelease");
    }

    static void testDoubleRelease() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("acquire should succeed", token);

        token.release();
        assertEquals("first release -> IDLE", ExecState.IDLE, state.getSnapshot().execState());

        token.release();
        assertEquals("second release still IDLE (no double decrement)", ExecState.IDLE, state.getSnapshot().execState());

        ExecutionToken token2 = state.tryAcquireExecution(1);
        checkNotNull("should be able to acquire after release", token2);
        assertEquals("new owner should be 1", 1, state.getSnapshot().owner());
        pass("testDoubleRelease");
    }

    static void testTripleRelease() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("acquire should succeed", token);

        token.release();
        token.release();
        token.release();

        assertEquals("triple release -> IDLE", ExecState.IDLE, state.getSnapshot().execState());

        ExecutionToken token2 = state.tryAcquireExecution(2);
        checkNotNull("re-acquire after triple release should succeed", token2);
        pass("testTripleRelease");
    }

    static void testConcurrentAcquireOnlyOneSucceeds() throws Exception {
        final int THREADS = 8;
        final RegionState state = new RegionState();
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        final AtomicInteger successCount = new AtomicInteger(0);

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int workerId = i;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    ExecutionToken token = state.tryAcquireExecution(workerId);
                    if (token != null) {
                        successCount.incrementAndGet();
                        Thread.sleep(10);
                        token.release();
                    }
                } catch (Exception e) {
                    // ignore
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        assertEquals("only one thread should acquire", 1, successCount.get());
        pass("testConcurrentAcquireOnlyOneSucceeds");
    }

    static void testReleaseAfterReleaseDoesNothing() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("token not null", token);

        token.release();
        ExecState stateAfterFirstRelease = state.getSnapshot().execState();

        for (int i = 0; i < 100; i++) {
            token.release();
        }
        assertEquals("state unchanged after many releases", stateAfterFirstRelease, state.getSnapshot().execState());
        pass("testReleaseAfterReleaseDoesNothing");
    }

    static void testTransferOwnership() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("acquire by worker 0", token);
        assertEquals("owner is 0", 0, state.getSnapshot().owner());

        ExecutionToken newToken = token.transferTo(-1);
        checkNotNull("transfer should succeed", newToken);
        assertEquals("new owner is -1", -1, state.getSnapshot().owner());

        token.release();
        assertEquals("old token release doesn't change state", ExecState.RUNNING, state.getSnapshot().execState());

        newToken.release();
        assertEquals("new token release -> IDLE", ExecState.IDLE, state.getSnapshot().execState());
        pass("testTransferOwnership");
    }

    static void testTransferThenRelease() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("token not null", token);

        ExecutionToken transferred = token.transferTo(5);
        checkNotNull("transferred not null", transferred);

        token.release();
        assertEquals("old token release invalid after transfer", ExecState.RUNNING, state.getSnapshot().execState());

        transferred.release();
        assertEquals("new token release valid", ExecState.IDLE, state.getSnapshot().execState());
        pass("testTransferThenRelease");
    }

    static void testGenerationMismatchReleasesFails() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("token not null", token);

        ExecutionToken newToken = token.transferTo(1);
        checkNotNull("newToken not null", newToken);

        token.release();
        assertEquals("old generation token release invalid", ExecState.RUNNING, state.getSnapshot().execState());

        newToken.release();
        assertEquals("new generation token release valid", ExecState.IDLE, state.getSnapshot().execState());
        pass("testGenerationMismatchReleasesFails");
    }

    static void testAcquireAfterTransferSucceedsWithNewOwner() {
        RegionState state = new RegionState();
        ExecutionToken token = state.tryAcquireExecution(0);
        checkNotNull("token not null", token);

        ExecutionToken newToken = token.transferTo(2);
        checkNotNull("newToken not null", newToken);
        assertEquals("owner is 2 after transfer", 2, state.getSnapshot().owner());

        newToken.release();
        assertEquals("IDLE after new token release", ExecState.IDLE, state.getSnapshot().execState());

        ExecutionToken reAcquired = state.tryAcquireExecution(3);
        checkNotNull("re-acquire after full release", reAcquired);
        pass("testAcquireAfterTransferSucceedsWithNewOwner");
    }

    // ---- assertion helpers ----

    static void checkNotNull(String msg, Object obj) {
        if (obj == null) fail(msg + " (got null)");
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
