package fun.bm.mili.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.io.File;

/**
 * Pure Java TOML config storage backed by night-config.
 * Replaces the former Rust (JNI) backed implementation while keeping the same API surface.
 */
// Mili start - fix: implement AutoCloseable to prevent file handle leaks on config reload
public class TomlConfigData implements AutoCloseable {

    private final CommentedFileConfig backing;

    public TomlConfigData(File file) {
        this.backing = CommentedFileConfig.builder(file).build();
    }

    public void load() {
        backing.load();
    }

    public void save() {
        backing.save();
    }

    public boolean contains(String key) {
        return backing.contains(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) backing.get(key);
    }

    public void set(String key, Object value) {
        backing.set(key, value);
    }

    /**
     * Add a value at the given path; falls back to set if the path already exists.
     */
    public void add(String key, Object value) {
        if (backing.contains(key)) {
            backing.set(key, value);
        } else {
            backing.add(key, value);
        }
    }

    public void remove(String key) {
        backing.remove(key);
    }

    public void clear() {
        backing.clear();
    }

    public String getComment(String key) {
        return backing.getComment(key);
    }

    public void setComment(String key, String comment) {
        backing.setComment(key, comment);
    }

    /**
     * Returns the sub-table at the given path, or null if absent or not a table.
     */
    public Object getConfigSection(String key) {
        Object value = backing.get(key);
        return value instanceof CommentedConfig ? value : null;
    }

    // Mili start - fix: close the backing CommentedFileConfig to release file handles
    @Override
    public void close() {
        backing.close();
    }
    // Mili end
}
