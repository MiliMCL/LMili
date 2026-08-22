package fun.bm.mili.lmili.api.identity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PluginIdentityFallbackTest {

    @Test
    void synthesizesLegacyNamespace() {
        final PluginId id = PluginIdentityFallback.synthesizeLegacyId("My_Plugin");
        assertEquals("legacy.my-plugin", id.value());
    }

    @Test
    void collapsesMultipleDashes() {
        assertEquals("legacy.my-plugin", PluginIdentityFallback.synthesizeLegacyId("my___plugin"));
        assertEquals("legacy.my-plugin", PluginIdentityFallback.synthesizeLegacyId("my@@@plugin"));
    }

    @Test
    void stripsLeadingTrailingDashes() {
        assertEquals("legacy.x", PluginIdentityFallback.synthesizeLegacyId("___x___"));
    }

    @Test
    void stripsDots() {
        // plugin.yml names can't normally contain dots but be safe.
        assertEquals("legacy.my-plugin", PluginIdentityFallback.synthesizeLegacyId(".my.plugin."));
    }

    @Test
    void emptyNameBecomesUnknownPlugin() {
        assertEquals("legacy.unknown-plugin",
                PluginIdentityFallback.synthesizeLegacyId(""));
        assertEquals("legacy.unknown-plugin",
                PluginIdentityFallback.synthesizeLegacyId("___"));
    }

    @Test
    void legacyIdentityHasLegacyType() {
        final PluginIdentity id = PluginIdentityFallback.forBukkit("MyPlugin", "1.0");
        assertEquals(PluginType.LEGACY, id.type());
        assertEquals(Optional.empty(), id.parentId());
        assertEquals("legacy.myplugin", id.id().value());
        assertEquals("legacy", id.publisher());
    }

    @Test
    void legacyIdentityUsesOriginalBukkitName() {
        final PluginIdentity id = PluginIdentityFallback.forBukkit("MyPlugin", null);
        assertEquals("MyPlugin", id.name());
        assertEquals("0.0.0", id.version());
    }
}