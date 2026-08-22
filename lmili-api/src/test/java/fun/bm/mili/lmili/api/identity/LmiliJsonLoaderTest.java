package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidPluginMetadataException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LmiliJsonLoaderTest {

    @Test
    void parsesMinimalPluginJson() {
        final String json = """
                {
                  "id": "xucy.mili",
                  "name": "Mili",
                  "version": "1.0.0",
                  "publisher": "xucy"
                }
                """;
        final LmiliJsonLoader.Loaded loaded = LmiliJsonLoader.parse(json);
        assertEquals("xucy.mili", loaded.id().value());
        assertEquals("Mili", loaded.name());
        assertEquals("1.0.0", loaded.version());
        assertEquals("xucy.mili", loaded.publisher()); // publisher segment
        assertEquals(PluginType.PLUGIN, loaded.type());
        assertFalse(loaded.hasParent());
    }

    @Test
    void parsesAddonJson() {
        final String json = """
                {
                  "id": "xucy.mili.market",
                  "name": "Mili Market",
                  "version": "1.0.0",
                  "publisher": "xucy",
                  "type": "addon",
                  "parent": "xucy.mili"
                }
                """;
        final LmiliJsonLoader.Loaded loaded = LmiliJsonLoader.parse(json);
        assertEquals(PluginType.ADDON, loaded.type());
        assertTrue(loaded.hasParent());
        assertEquals(Optional.of(PluginId.parse("xucy.mili")), loaded.parentId());
    }

    @Test
    void rejectsAddonWithoutParent() {
        final String json = """
                {
                  "id": "xucy.mili.market",
                  "name": "Market",
                  "version": "1.0.0",
                  "publisher": "xucy",
                  "type": "addon"
                }
                """;
        assertThrows(InvalidPluginMetadataException.class, () -> LmiliJsonLoader.parse(json));
    }

    @Test
    void rejectsAddonWithMismatchedParent() {
        final String json = """
                {
                  "id": "xucy.mili.market",
                  "name": "Market",
                  "version": "1.0.0",
                  "publisher": "xucy",
                  "type": "addon",
                  "parent": "xucy.unknown"
                }
                """;
        // Parser accepts it (it parses), but PluginIdentity.of() would reject on build.
        final LmiliJsonLoader.Loaded loaded = LmiliJsonLoader.parse(json);
        assertEquals(PluginId.parse("xucy.unknown"), loaded.parentId().orElseThrow(AssertionError::new));
        // Constructing the PluginIdentity surfaces the mismatch.
        assertThrows(fun.bm.mili.lmili.api.identity.exception.InvalidAddonParentException.class,
                () -> PluginIdentity.of(
                        loaded.id(), loaded.name(), loaded.version(), loaded.publisher(),
                        loaded.type(), loaded.parentId(), "lmili.json", null));
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(InvalidPluginMetadataException.class,
                () -> LmiliJsonLoader.parse("{\"name\":\"x\",\"version\":\"1\",\"publisher\":\"x\"}"));
        assertThrows(InvalidPluginMetadataException.class,
                () -> LmiliJsonLoader.parse("{\"id\":\"a.b\",\"version\":\"1\",\"publisher\":\"x\"}"));
    }

    @Test
    void rejectsInvalidId() {
        // Mixed case → rejected.
        assertThrows(InvalidPluginMetadataException.class,
                () -> LmiliJsonLoader.parse(
                        "{\"id\":\"Xucy.Mili\",\"name\":\"x\",\"version\":\"1\",\"publisher\":\"x\"}"));
    }

    @Test
    void rejectsUnknownType() {
        final String json = """
                {
                  "id": "xucy.mili",
                  "name": "x",
                  "version": "1.0.0",
                  "publisher": "xucy",
                  "type": "weird"
                }
                """;
        assertThrows(InvalidPluginMetadataException.class, () -> LmiliJsonLoader.parse(json));
    }

    @Test
    void ignoresFakeVerifiedTrust() {
        // V2: presence of "trust": "VERIFIED" must NOT auto-promote.
        final String json = """
                {
                  "id": "xucy.mili",
                  "name": "x",
                  "version": "1.0.0",
                  "publisher": "xucy",
                  "trust": "VERIFIED"
                }
                """;
        final LmiliJsonLoader.Loaded loaded = LmiliJsonLoader.parse(json);
        assertEquals(PluginTrustLevel.UNKNOWN, loaded.trust());
    }

    @Test
    void resourcePathIsJarRoot() {
        assertEquals("lmili.json", LmiliJsonLoader.RESOURCE_PATH);
        assertFalse(LmiliJsonLoader.RESOURCE_PATH.startsWith("/"));
    }

    @Test
    void loadFromStreamHandlesNull() {
        assertNull(LmiliJsonLoader.load((java.io.InputStream) null));
    }

    @Test
    void loadFromStreamHandlesEmpty() {
        assertNull(LmiliJsonLoader.load(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void loadFromClassLoaderFindsRootFile() {
        final String json = """
                {"id":"test.cl","name":"Test","version":"0.1","publisher":"test"}""";
        final java.util.Map<String, byte[]> resources = new java.util.HashMap<>();
        resources.put("lmili.json", json.getBytes());
        final ClassLoader cl = new ResourceOnlyClassLoader(getClass().getClassLoader(), resources);
        final LmiliJsonLoader.Loaded loaded = LmiliJsonLoader.load(cl);
        assertNotNull(loaded);
        assertEquals("test.cl", loaded.id().value());
    }

    private static final class ResourceOnlyClassLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> resources;

        ResourceOnlyClassLoader(final ClassLoader parent,
                                final java.util.Map<String, byte[]> resources) {
            super(parent);
            this.resources = resources;
        }

        @Override
        public java.io.InputStream getResourceAsStream(final String name) {
            final byte[] bytes = resources.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }
}