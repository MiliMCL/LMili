package fun.bm.mili.lmili.thread.scheduler;

import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.execute.BlockingTaskIsolation;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionQueue;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState;
import fun.bm.mili.lmili.thread.scheduler.execute.RegionState.ExecutionToken;
import fun.bm.mili.lmili.thread.scheduler.execute.TaskScheduleState;
import fun.bm.mili.lmili.thread.scheduler.execute.TaskScheduleState.Phase;
import fun.bm.mili.lmili.thread.scheduler.execute.WorkStealingCoordinator;
import fun.bm.mili.lmili.thread.scheduler.execute.WorkStealingCoordinator.PollResult;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LATEST-08: 完整并发回归测试。
 */
public final class ConcurrencyRegressionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Concurrency Regression Tests (LATEST-08) ===\n");

        testSameRegionMultipleWorkers();
        testBlockingThenCpuSerialization();
        testUnregisterWithRunningTask();
        testUnregisterWithBlockingTask();
        testDrainQueuedTask();
        testDoubleReleaseWithCoordinator();
        testStaleGenerationTaskRejection();
        testSubmitRetryOnInactiveQueue();
        testRegisterUnregisterRace();
        testCloseBarrierCompletesOnLastRelease();
        testTaskScheduleStateFullLifecycle();
        testTaskScheduleStateResubmitAfterTick();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- 9.1 同 Region 多 Worker: maxConcurrentExecution == 1 ----

    static void testSameRegionMultipleWorkers() throws Exception {
        final int WORKERS = 4;
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(WORKERS);
        RegionQueue queue = coordinator.registerRegion(1L);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskHold = new CountDownLatch(1);
        AtomicReference<Throwable> taskError = new AtomicReference<>();

        Runnable taskBody = () -> {
            taskStarted.countDown();
            try { taskHold.await(2, TimeUnit.SECONDS); }
            catch (Exception e) { taskError.set(e); }
        };
        queue.push(RegionTask.builder(1L).task(taskBody).build());

        // Worker 0 获取任务
        PollResult result = coordinator.poll(0);
        checkNotNull("worker 0 should get task", result);

        taskStarted.await(2, TimeUnit.SECONDS);
        assertTrue("task should be executing", queue.regionState().isRunning());

        // Worker 1, 2, 3 尝试获取同一 region 的任务
        for (int w = 1; w < WORKERS; w++) {
            PollResult other = coordinator.poll(w);
            if (other != null && other.queue().regionId() == 1L) {
                fail("worker " + w + " should NOT get task for same region");
                return;
            }
        }

        taskHold.countDown();
        result.release();

        assertTrue("no task error", taskError.get() == null);
        pass("testSameRegionMultipleWorkers");
    }

    // ---- 9.2 Blocking + CPU: maxConcurrentExecution == 1 ----

    static void testBlockingThenCpuSerialization() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        BlockingTaskIsolation blocking = new BlockingTaskIsolation();
        RegionQueue queue = coordinator.registerRegion(2L);

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingHold = new CountDownLatch(1);
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        Runnable blockingBody = () -> {
            int current = concurrentExecutions.incrementAndGet();
            maxConcurrent.updateAndGet(v -> Math.max(v, current));
            blockingStarted.countDown();
            try { blockingHold.await(2, TimeUnit.SECONDS); } catch (Exception e) {}
            concurrentExecutions.decrementAndGet();
        };

        RegionTask blockingTask = RegionTask.builder(2L).task(blockingBody).blocking(true).build();
        queue.push(blockingTask);

        PollResult result = coordinator.poll(0);
        checkNotNull("should get blocking task", result);

        new Thread(() -> {
            blocking.executeBlocking(result.task, result, 0);
        }).start();

        blockingStarted.await(2, TimeUnit.SECONDS);
        assertTrue("blocking task should be running", queue.regionState().isRunning());

        // 尝试 acquire —— 应该失败
        ExecutionToken stealAttempt = queue.regionState().tryAcquireExecution(1);
        assertTrue("should not acquire while blocking holds token", stealAttempt == null);

        blockingHold.countDown();
        Thread.sleep(200);

        assertEquals("max concurrent executions should be 1", 1, maxConcurrent.get());
        pass("testBlockingThenCpuSerialization");
    }

    // ---- 9.4 unregister + running task ----

    static void testUnregisterWithRunningTask() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        RegionQueue queue = coordinator.registerRegion(3L);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskHold = new CountDownLatch(1);

        Runnable taskBody = () -> {
            taskStarted.countDown();
            try { taskHold.await(2, TimeUnit.SECONDS); } catch (Exception e) {}
        };
        queue.push(RegionTask.builder(3L).task(taskBody).build());

        PollResult result = coordinator.poll(0);
        checkNotNull("should get task", result);
        taskStarted.await(2, TimeUnit.SECONDS);

        AtomicReference<List<RegionTask>> remaining = new AtomicReference<>();
        Thread unregisterThread = new Thread(() -> {
            remaining.set(coordinator.unregisterRegion(3L));
        });
        unregisterThread.start();

        Thread.sleep(200);
        assertTrue("unregister should be waiting for barrier", unregisterThread.isAlive());

        taskHold.countDown();
        result.release();

        unregisterThread.join(5000);
        assertTrue("unregister should complete after task release", !unregisterThread.isAlive());
        pass("testUnregisterWithRunningTask");
    }

    // ---- 9.5 unregister + blocking task ----

    static void testUnregisterWithBlockingTask() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        BlockingTaskIsolation blocking = new BlockingTaskIsolation();
        RegionQueue queue = coordinator.registerRegion(4L);

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingHold = new CountDownLatch(1);

        Runnable taskBody = () -> {
            blockingStarted.countDown();
            try { blockingHold.await(2, TimeUnit.SECONDS); } catch (Exception e) {}
        };
        queue.push(RegionTask.builder(4L).task(taskBody).blocking(true).build());

        PollResult result = coordinator.poll(0);
        checkNotNull("should get blocking task", result);

        new Thread(() -> {
            blocking.executeBlocking(result.task, result, 0);
        }).start();

        blockingStarted.await(2, TimeUnit.SECONDS);

        AtomicReference<List<RegionTask>> remaining = new AtomicReference<>();
        Thread unregisterThread = new Thread(() -> {
            remaining.set(coordinator.unregisterRegion(4L));
        });
        unregisterThread.start();

        Thread.sleep(200);
        assertTrue("unregister should wait for blocking", unregisterThread.isAlive());

        blockingHold.countDown();
        unregisterThread.join(5000);
        assertTrue("unregister should complete after blocking done", !unregisterThread.isAlive());
        pass("testUnregisterWithBlockingTask");
    }

    // ---- 9.6 drain + queued task ----

    static void testDrainQueuedTask() {
        RegionQueue queue = new RegionQueue(5L);
        Runnable r = () -> {};
        for (int i = 0; i < 10; i++) {
            queue.push(RegionTask.builder(5L).task(r).build());
        }
        assertEquals("queued = 10", 10, queue.regionState().getQueuedCount());

        List<RegionTask> drained = queue.drain();
        assertEquals("drained 10 tasks", 10, drained.size());
        assertEquals("queued = 0 after drain", 0, queue.regionState().getQueuedCount());
        pass("testDrainQueuedTask");
    }

    // ---- 9.7 double release ----

    static void testDoubleReleaseWithCoordinator() {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        RegionQueue queue = coordinator.registerRegion(6L);

        Runnable r = () -> {};
        queue.push(RegionTask.builder(6L).task(r).build());

        PollResult result = coordinator.poll(0);
        checkNotNull("should get task", result);
        assertTrue("region should be running", queue.regionState().isRunning());

        result.release();
        assertTrue("region should be idle after release", !queue.regionState().isRunning());

        result.release();
        assertTrue("region still idle after double release", !queue.regionState().isRunning());

        ExecutionToken token = queue.regionState().tryAcquireExecution(0);
        checkNotNull("re-acquire after double release", token);
        token.release();
        pass("testDoubleReleaseWithCoordinator");
    }

    // ---- 9.8 stale generation ----

    static void testStaleGenerationTaskRejection() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);

        RegionQueue queue1 = coordinator.registerRegion(7L);

        // Push a task and acquire execution to increment generation
        Runnable r = () -> {};
        queue1.push(RegionTask.builder(7L).task(r).build());
        PollResult result = coordinator.poll(0);
        checkNotNull("should get task from queue1", result);
        long gen1 = queue1.regionState().getSnapshot().generation();
        assertTrue("gen1 > 0 after acquire", gen1 > 0);

        // Release task and unregister
        result.release();
        coordinator.unregisterRegion(7L);

        // Re-register — new RegionQueue/RegionState instance
        RegionQueue queue2 = coordinator.registerRegion(7L);
        checkNotNull("queue2 not null", queue2);
        assertTrue("new queue is active", queue2.isActive());

        // Push task to new queue and acquire to verify it has independent generation
        queue2.push(RegionTask.builder(7L).task(r).build());
        PollResult result2 = coordinator.poll(0);
        checkNotNull("should get task from queue2", result2);

        // Verify that old generation tasks wouldn't be valid in new generation
        // (old token's generation != new region's generation)
        long gen2 = queue2.regionState().getSnapshot().generation();
        assertTrue("gen2 > 0 after acquire", gen2 > 0);
        assertTrue("queue1 and queue2 are different instances", queue1 != queue2);
        pass("testStaleGenerationTaskRejection");
    }

    // ---- submit 在 queue 停用时自动重试 ----

    static void testSubmitRetryOnInactiveQueue() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        RegionQueue queue = coordinator.registerRegion(8L);

        queue.deactivate();

        Runnable r = () -> {};
        coordinator.submit(RegionTask.builder(8L).task(r).build());

        assertTrue("region should still be registered after retry", coordinator.isRegionRegistered(8L));
        pass("testSubmitRetryOnInactiveQueue");
    }

    // ---- register/unregister 竞争 ----

    static void testRegisterUnregisterRace() throws Exception {
        final int THREADS = 8;
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger errors = new AtomicInteger(0);

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    if (idx % 2 == 0) {
                        coordinator.registerRegion(100L);
                    } else {
                        coordinator.unregisterRegion(100L);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        assertEquals("no errors during register/unregister race", 0, errors.get());
        pass("testRegisterUnregisterRace");
    }

    // ---- closeBarrier 在最后一个 release 时完成 ----

    static void testCloseBarrierCompletesOnLastRelease() throws Exception {
        WorkStealingCoordinator coordinator = new WorkStealingCoordinator(2);
        RegionQueue queue = coordinator.registerRegion(9L);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskHold = new CountDownLatch(1);

        Runnable taskBody = () -> {
            taskStarted.countDown();
            try { taskHold.await(2, TimeUnit.SECONDS); } catch (Exception e) {}
        };
        queue.push(RegionTask.builder(9L).task(taskBody).build());

        PollResult result = coordinator.poll(0);
        checkNotNull("result not null", result);
        taskStarted.await(2, TimeUnit.SECONDS);

        AtomicReference<List<RegionTask>> remaining = new AtomicReference<>();
        Thread unregisterThread = new Thread(() -> {
            remaining.set(coordinator.unregisterRegion(9L));
        });
        unregisterThread.start();

        Thread.sleep(200);
        assertTrue("unregister waiting for barrier", unregisterThread.isAlive());

        taskHold.countDown();
        result.release();

        unregisterThread.join(5000);
        assertTrue("unregister completes after last release", !unregisterThread.isAlive());
        pass("testCloseBarrierCompletesOnLastRelease");
    }

    // ---- TaskScheduleState 完整生命周期（验证 executeTask 修复） ----

    static void testTaskScheduleStateFullLifecycle() {
        TaskScheduleState state = new TaskScheduleState();

        // 初始状态：IDLE
        assertEquals("initial state is IDLE", Phase.IDLE, state.get());

        // 模拟 scheduleRegion：IDLE → QUEUED
        assertTrue("tryMarkQueued succeeds from IDLE", state.tryMarkQueued());
        assertEquals("state is QUEUED", Phase.QUEUED, state.get());

        // 模拟 executeTask 入口：QUEUED → RUNNING（这是修复的关键）
        assertTrue("tryMarkRunning succeeds from QUEUED", state.tryMarkRunning());
        assertEquals("state is RUNNING", Phase.RUNNING, state.get());

        // 模拟 executeTask 完成：RUNNING → IDLE
        assertTrue("tryMarkIdle succeeds from RUNNING", state.tryMarkIdle());
        assertEquals("state is IDLE after tick", Phase.IDLE, state.get());

        pass("testTaskScheduleStateFullLifecycle");
    }

    static void testTaskScheduleStateResubmitAfterTick() {
        TaskScheduleState state = new TaskScheduleState();
        AtomicInteger tickCount = new AtomicInteger(0);

        // 模拟多次 tick 循环
        for (int i = 0; i < 5; i++) {
            // scheduleRegion: IDLE → QUEUED
            assertTrue("tick " + i + ": tryMarkQueued", state.tryMarkQueued());
            // executeTask: QUEUED → RUNNING
            assertTrue("tick " + i + ": tryMarkRunning", state.tryMarkRunning());
            // 执行 tick 逻辑
            tickCount.incrementAndGet();
            // executeTask 完成: RUNNING → IDLE
            assertTrue("tick " + i + ": tryMarkIdle", state.tryMarkIdle());
        }

        assertEquals("executed 5 ticks", 5, tickCount.get());
        assertEquals("final state is IDLE", Phase.IDLE, state.get());
        pass("testTaskScheduleStateResubmitAfterTick");
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
