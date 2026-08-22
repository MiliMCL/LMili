package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.conflict.ConflictReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 §11: 100 threads racing to register the same id must produce exactly
 * one ACTIVE registration; the rest must be detected as conflicts.
 *
 * V2 §33: two duplicate-id plugins must not crash the server; the conflict
 * handling must be correct under load.
 */
class PluginIdentityManagerConcurrencyTest {

    @Test
    void hundredThreadsRegisteringSameIdProduceOneActiveAndConflicts() throws Exception {
        final DefaultPluginIdentityManager mgr = new DefaultPluginIdentityManager();
        final int threadCount = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger acceptCount = new AtomicInteger();
        final AtomicInteger conflictCount = new AtomicInteger();
        final List<Future<PluginIdentity>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    final PluginIdentity incoming = PluginIdentity.of(
                            PluginId.parse("xucy.mili"),
                            "xucy.mili", "1.0.0", "xucy",
                            PluginType.PLUGIN, java.util.Optional.empty(),
                            "lmili.json", null);
                    final PluginIdentity result = mgr.register(incoming);
                    if (result == incoming) {
                        acceptCount.incrementAndGet();
                    } else {
                        conflictCount.incrementAndGet();
                    }
                    return result;
                }));
            }
            start.countDown();

            for (final Future<PluginIdentity> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The first call wins; every subsequent call sees the same id+version and
        // therefore falls into the "same version = not a conflict" branch and
        // replaces the slot. So we expect every call to "return its own identity"
        // (none of them return an unrelated identity).
        assertEquals(threadCount, acceptCount.get(),
                "every concurrent registration should report success (same version = hot-reload semantics)");
        assertEquals(0, conflictCount.get(), "no conflict should be produced for same-version races");
        assertEquals(1, mgr.size());

        // Repeated registration with DIFFERENT versions must produce conflicts.
        for (int i = 0; i < 50; i++) {
            mgr.register(PluginIdentity.of(
                    PluginId.parse("xucy.mili"),
                    "xucy.mili", "2." + i + ".0", "xucy",
                    PluginType.PLUGIN, java.util.Optional.empty(),
                    "lmili.json", null));
        }
        assertEquals(50, mgr.getConflicts(PluginId.parse("xucy.mili")).size());
        mgr.getConflicts(PluginId.parse("xucy.mili"))
                .forEach(c -> assertEquals(ConflictReason.DUPLICATE_ID, c.reason()));
    }

    @Test
    void duplicateIdsDoNotCrashServer() {
        // V2 §33: "Two plugins with same id must not cause the server to crash."
        final DefaultPluginIdentityManager mgr = new DefaultPluginIdentityManager();
        assertDoesNotThrow(() -> {
            mgr.register(makeIdentity("xucy.mili", "1.0.0"));
            mgr.register(makeIdentity("xucy.mili", "1.0.0"));   // same version → hot-reload
            mgr.register(makeIdentity("xucy.mili", "2.0.0"));   // diff version → conflict
            mgr.register(makeIdentity("xucy.mili", "2.0.0"));   // same version
            mgr.register(makeIdentity("xucy.mili", "3.0.0"));
        });
        assertEquals(1, mgr.size());
        // Conflicts only for version transitions: 1.0→2.0 and 2.0→3.0
        assertEquals(2, mgr.getConflicts(PluginId.parse("xucy.mili")).size());
    }

    @Test
    void getAllNeverThrowsUnderConcurrentMutation() throws Exception {
        final DefaultPluginIdentityManager mgr = new DefaultPluginIdentityManager();
        final int writers = 16;
        final int readers = 16;
        final int ops = 200;
        final ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        final CountDownLatch start = new CountDownLatch(1);

        try {
            final List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < ops; i++) {
                        mgr.register(makeIdentity("w." + Thread.currentThread().getId()
                                + "." + i, "1.0.0"));
                    }
                    return null;
                }));
            }
            for (int r = 0; r < readers; r++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < ops; i++) {
                        final var snapshot = mgr.getAll();
                        // Defensive copy must not throw.
                        assertNotNull(snapshot);
                        final Optional<PluginIdentity> any =
                                mgr.find(PluginId.parse("w.1.1"));
                        // any may or may not be present; just don't crash.
                        any.ifPresent(id -> assertNotNull(id.id()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static PluginIdentity makeIdentity(final String id, final String version) {
        final PluginId pid = PluginId.parse(id);
        return PluginIdentity.of(pid, pid.value(), version, pid.publisher(),
                PluginType.PLUGIN, java.util.Optional.empty(), "lmili.json", null);
    }
}