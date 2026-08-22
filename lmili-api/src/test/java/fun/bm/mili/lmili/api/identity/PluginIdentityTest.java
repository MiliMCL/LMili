package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidAddonParentException;
import fun.bm.mili.lmili.api.identity.exception.InvalidPluginMetadataException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PluginIdentityTest {

    @Test
    void pluginIdentityIsImmutable() {
        final PluginIdentity id = makePlugin("xucy.mili", "1.0.0");
        final PluginIdentity id2 = id.withStatus(PluginStatus.ACTIVE);
        assertNotSame(id, id2);
        assertEquals(PluginStatus.DISCOVERED, id.status());
        assertEquals(PluginStatus.ACTIVE, id2.status());
        // type/publisher/parent must not change
        assertEquals(id.type(), id2.type());
        assertEquals(id.publisher(), id2.publisher());
        assertEquals(id.parentId(), id2.parentId());
    }

    @Test
    void pluginStatusTransitionsAreImmutable() {
        final PluginIdentity base = makePlugin("xucy.mili", "1.0.0");
        final PluginIdentity active = base.withStatus(PluginStatus.ACTIVE);
        final PluginIdentity observe = active.withStatus(PluginStatus.OBSERVE);
        assertNotSame(active, observe);
        assertEquals(PluginStatus.ACTIVE,   active.status());
        assertEquals(PluginStatus.OBSERVE,  observe.status());
    }

    @Test
    void addonRequiresMatchingParent() {
        final PluginIdentity addon = makeAddon("xucy.mili.market", "xucy.mili");
        assertEquals(PluginType.ADDON, addon.type());
        assertEquals(Optional.of(PluginId.parse("xucy.mili")), addon.parentId());
    }

    @Test
    void addonRejectsMismatchedParent() {
        // id has parent "xucy.mili" but meta says "xucy.unknown".
        assertThrows(InvalidAddonParentException.class,
                () -> PluginIdentity.of(
                        PluginId.parse("xucy.mili.market"),
                        "Market", "1.0.0", "xucy",
                        PluginType.ADDON, Optional.of(PluginId.parse("xucy.unknown")),
                        "lmili.json", null));
    }

    @Test
    void addonRejectsTopLevelIdWithParent() {
        // xucy.mili is a 2-segment id (top-level). Declaring type=addon is invalid
        // because there's no parent segment in the id.
        assertThrows(InvalidAddonParentException.class,
                () -> PluginIdentity.of(
                        PluginId.parse("xucy.mili"),
                        "xucy.mili", "1.0.0", "xucy",
                        PluginType.ADDON, Optional.of(PluginId.parse("xucy.unknown")),
                        "lmili.json", null));
    }

    @Test
    void addonRejectsEmptyParent() {
        assertThrows(InvalidPluginMetadataException.class,
                () -> PluginIdentity.of(
                        PluginId.parse("xucy.mili.market"),
                        "Market", "1.0.0", "xucy",
                        PluginType.ADDON, Optional.empty(),
                        "lmili.json", null));
    }

    @Test
    void nonAddonRejectsParentDeclaration() {
        assertThrows(InvalidPluginMetadataException.class,
                () -> PluginIdentity.of(
                        PluginId.parse("xucy.mili"),
                        "xucy.mili", "1.0.0", "xucy",
                        PluginType.PLUGIN, Optional.of(PluginId.parse("xucy.parent")),
                        "lmili.json", null));
    }

    @Test
    void pluginStatusReflects8StateMachine() {
        assertEquals("DISCOVERED", PluginStatus.DISCOVERED.name());
        assertEquals("LOADING",    PluginStatus.LOADING.name());
        assertEquals("ACTIVE",     PluginStatus.ACTIVE.name());
        assertEquals("OBSERVE",    PluginStatus.OBSERVE.name());
        assertEquals("CONFLICT",   PluginStatus.CONFLICT.name());
        assertEquals("DISABLED",   PluginStatus.DISABLED.name());
        assertEquals("FAILED",     PluginStatus.FAILED.name());
        assertEquals("UNLOADED",   PluginStatus.UNLOADED.name());
        // Sanity: only ACTIVE is both running and schedulable by default.
        assertTrue(PluginStatus.ACTIVE.isRunning());
        assertTrue(PluginStatus.ACTIVE.canSchedule());
        assertFalse(PluginStatus.CONFLICT.isRunning());
        assertFalse(PluginStatus.CONFLICT.canSchedule());
    }

    // ---- helpers --------------------------------------------------------

    private static PluginIdentity makePlugin(final String id, final String version) {
        final PluginId pid = PluginId.parse(id);
        return PluginIdentity.of(pid, pid.value(), version, pid.publisher(),
                PluginType.PLUGIN, Optional.empty(), "lmili.json", null);
    }

    private static PluginIdentity makeAddon(final String id, final String parentId) {
        final PluginId pid = PluginId.parse(id);
        return PluginIdentity.of(pid, pid.value(), "1.0.0", pid.publisher(),
                PluginType.ADDON, Optional.of(PluginId.parse(parentId)),
                "lmili.json", null);
    }
}