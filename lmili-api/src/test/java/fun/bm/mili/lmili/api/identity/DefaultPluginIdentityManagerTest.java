package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.conflict.ConflictReason;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPluginIdentityManagerTest {

    private DefaultPluginIdentityManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new DefaultPluginIdentityManager();
    }

    @Test
    void registersFirstTimeIdentity() {
        final PluginIdentity a = identity("xucy.mili", "1.0.0");
        final PluginIdentity result = mgr.register(a);
        assertSame(a, result);
        assertTrue(mgr.contains(PluginId.parse("xucy.mili")));
        assertEquals(1, mgr.size());
        assertTrue(mgr.find(PluginId.parse("xucy.mili")).isPresent());
    }

    @Test
    void duplicateIdDifferentVersionProducesConflict() {
        final PluginIdentity first  = identity("xucy.mili", "1.0.0");
        final PluginIdentity second = identity("xucy.mili", "2.0.0");
        final PluginIdentity r1 = mgr.register(first);
        final PluginIdentity r2 = mgr.register(second);

        // Per V2 §14: incoming rejected, original keeps NORMAL.
        assertSame(first, r1);
        assertSame(first, r2);  // existing wins

        final Collection<PluginIdentityConflict> conflicts =
                mgr.getConflicts(PluginId.parse("xucy.mili"));
        assertEquals(1, conflicts.size());
        final PluginIdentityConflict conflict = conflicts.iterator().next();
        assertEquals(ConflictReason.DUPLICATE_ID, conflict.reason());
        assertEquals(first, conflict.existing());
        assertEquals(second, conflict.incoming());
    }

    @Test
    void sameIdSameVersionIsNotAConflict() {
        final PluginIdentity a = identity("xucy.mili", "1.0.0");
        final PluginIdentity b = identity("xucy.mili", "1.0.0");
        mgr.register(a);
        mgr.register(b);
        assertTrue(mgr.getConflicts().isEmpty());
        assertEquals(1, mgr.size());
    }

    @Test
    void addonsAreIndependent() {
        mgr.register(identity("xucy.mili", "1.0.0"));
        mgr.register(identity("xucy.mili.database", "1.0.0"));
        mgr.register(identity("xucy.mili.market", "1.0.0"));
        assertEquals(3, mgr.size());
    }

    @Test
    void unregisterRemoves() {
        mgr.register(identity("xucy.mili", "1.0.0"));
        mgr.unregister(PluginId.parse("xucy.mili"));
        assertFalse(mgr.contains(PluginId.parse("xucy.mili")));
        assertEquals(0, mgr.size());
    }

    @Test
    void unregisterIsIdempotent() {
        mgr.unregister(PluginId.parse("nope.nothing"));
        mgr.unregister(PluginId.parse("nope.nothing"));
    }

    @Test
    void setStatusUpdatesExistingIdentity() {
        mgr.register(identity("xucy.mili", "1.0.0"));
        final Optional<PluginIdentity> updated = mgr.setStatus(PluginId.parse("xucy.mili"), PluginStatus.OBSERVE);
        assertTrue(updated.isPresent());
        assertEquals(PluginStatus.OBSERVE, updated.get().status());
        assertEquals(PluginStatus.OBSERVE,
                mgr.getStatus(PluginId.parse("xucy.mili")).orElseThrow(AssertionError::new));
    }

    @Test
    void setStatusReturnsEmptyForUnknownId() {
        assertTrue(mgr.setStatus(PluginId.parse("unknown.x"), PluginStatus.ACTIVE).isEmpty());
    }

    @Test
    void getAllReturnsInsertionOrder() {
        mgr.register(identity("a.b", "1"));
        mgr.register(identity("c.d", "1"));
        mgr.register(identity("e.f", "1"));
        final var ids = mgr.getAll().stream().map(p -> p.id().value()).toList();
        assertEquals(java.util.List.of("a.b", "c.d", "e.f"), ids);
    }

    @Test
    void findByBukkitNameWorks() {
        final PluginIdentity a = identityWithBukkit("xucy.mili", "1.0.0", "XucyMili");
        mgr.register(a);
        assertEquals(Optional.of(a), mgr.findByBukkitName("XucyMili"));
    }

    @Test
    void noForceReplaceApi() {
        // Sanity: no such method exists on the interface.
        final Class<?>[] declared = PluginIdentityManager.class.getDeclaredMethods();
        for (final java.lang.reflect.Method m : declared) {
            final String n = m.getName().toLowerCase();
            assertFalse(n.contains("replace"), "forbidden method on PluginIdentityManager: " + m.getName());
            assertFalse(n.contains("force"),  "forbidden method on PluginIdentityManager: " + m.getName());
        }
    }

    // ---- helpers --------------------------------------------------------

    private static PluginIdentity identity(final String id, final String version) {
        return identityWithBukkit(id, version, null);
    }

    private static PluginIdentity identityWithBukkit(final String id, final String version,
                                                     final String bukkitName) {
        final PluginId pid = PluginId.parse(id);
        return PluginIdentity.of(pid, pid.value(), version, pid.publisher(),
                PluginType.PLUGIN, java.util.Optional.empty(), "lmili.json", bukkitName);
    }
}