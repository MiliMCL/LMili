package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidPluginIdException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginIdTest {

    @Test
    void parsesLegalTwoSegmentId() {
        final PluginId id = PluginId.parse("xucy.mili");
        assertEquals("xucy.mili", id.value());
        assertFalse(id.isAddon());
        assertTrue(id.parentId().isEmpty());
    }

    @Test
    void parsesLegalAddonId() {
        final PluginId id = PluginId.parse("xucy.mili.market");
        assertTrue(id.isAddon());
        assertTrue(id.parentId().isPresent());
        assertEquals("xucy.mili", id.parentId().get().value());
        assertTrue(id.isChildOf(PluginId.parse("xucy.mili")));
    }

    @Test
    void parsesDeepAddonId() {
        final PluginId id = PluginId.parse("xucy.mili.market.analytics");
        assertEquals("analytics", id.value().substring(id.value().lastIndexOf('.') + 1));
        assertTrue(id.isChildOf(PluginId.parse("xucy.mili")));
        assertTrue(id.isChildOf(PluginId.parse("xucy.mili.market")));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse(""));
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("   "));
    }

    @Test
    void rejectsSingleSegment() {
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("mili"));
    }

    @Test
    void rejectsEmptySegments() {
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy..mili"));
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse(".xucy.mili"));
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy.mili."));
    }

    @Test
    void rejectsUppercasePerV2Spec() {
        // V2: "Don't rewrite Xucy.Mili into xucy.mili" — must reject.
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("Xucy.Mili"));
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy.Mili"));
    }

    @Test
    void rejectsDisallowedCharacters() {
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy mili"));     // space
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy/mili"));     // /
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy\\mili"));    // backslash
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy:mili"));     // :
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy;mili"));     // ;
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy@mili"));     // @
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy#mili"));     // #
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy$mili"));     // $
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse("xucy插件"));        // 中文
    }

    @Test
    void rejectsOverlongIds() {
        final String tooLong = "a".repeat(130) + "." + "c".repeat(130);   // > 255 bytes
        assertThrows(InvalidPluginIdException.class, () -> PluginId.parse(tooLong));
    }

    @Test
    void acceptsDotsHyphensUnderscoresInSegments() {
        assertDoesNotThrow(() -> PluginId.parse("xucy.my-plugin"));
        assertDoesNotThrow(() -> PluginId.parse("xucy.my_plugin"));
        assertDoesNotThrow(() -> PluginId.parse("xucy.plugin_v2"));
    }

    @Test
    void normalizeAcceptsMixedCase() {
        // V2: runtime may lowercase for legacy fallback.
        final PluginId id = PluginId.normalize("Xucy.My_Plugin");
        assertEquals("xucy.my-plugin", id.value());
    }

    @Test
    void normalizeThrowsOnTrulyUnrecoverable() {
        assertThrows(InvalidPluginIdException.class,
                () -> PluginId.normalize("   "));
        assertThrows(InvalidPluginIdException.class,
                () -> PluginId.normalize("\u0000"));
    }

    @Test
    void parseNullableLenient() {
        assertNull(PluginId.parseNullable(null));
        assertNull(PluginId.parseNullable(""));
        assertNull(PluginId.parseNullable("Xucy.Mili"));     // rejected
        assertNotNull(PluginId.parseNullable("xucy.mili"));
    }

    @Test
    void equalityByValue() {
        final PluginId a = PluginId.parse("xucy.mili");
        final PluginId b = PluginId.parse("xucy.mili");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void ordering() {
        assertTrue(PluginId.parse("a.b").compareTo(PluginId.parse("c.d")) < 0);
        assertTrue(PluginId.parse("c.d").compareTo(PluginId.parse("a.b")) > 0);
        assertEquals(0, PluginId.parse("a.b").compareTo(PluginId.parse("a.b")));
    }
}