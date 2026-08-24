package fun.bm.mili.lmili.runtime.policy;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 不可变追加审计日志（§6.1 规则 3：审计日志文件与主日志分离，只追加）。
 *
 * <p>实现：内存追加（synchronized 尾插）+ 可选的独立文件追加（仅追加模式）。
 * 文件路径经系统属性 {@code lmili.audit.file} 配置；未配置时纯内存（测试友好）。
 * 文件写入失败仅记日志（审计不阻断策略通道）。
 */
public final class PolicyAuditLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<PolicyAuditEntry> entries = new ArrayList<>();
    private final Object lock = new Object();
    @Nullable
    private final Path filePath;

    public PolicyAuditLog() {
        this(readConfiguredPath());
    }

    public PolicyAuditLog(@Nullable Path filePath) {
        this.filePath = filePath;
        if (filePath != null) {
            try {
                final Path parent = filePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(filePath, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.warn("[PolicyAuditLog] Cannot init audit file {} (audit stays in-memory)", filePath, e);
            }
        }
    }

    @Nullable
    private static Path readConfiguredPath() {
        final String configured = System.getProperty("lmili.audit.file");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    /**
     * 追加一条审计（不可变 —— 调用方传入的 map 被复制）。
     */
    public void append(CommandSource source, String actor, CommandAction action,
                       Map<String, String> params, String result,
                       long version, long beforeVersion, long afterVersion) {
        final PolicyAuditEntry entry = new PolicyAuditEntry(
                System.nanoTime(), source, actor, action,
                params == null ? Collections.emptyMap() : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params)),
                result, version, beforeVersion, afterVersion);
        synchronized (lock) {
            entries.add(entry);
        }
        appendToFile(entry);
    }

    /** 追加一条插件 hint 裁决审计（action 域用 null 表示 hint 裁决，params 携带 hint 详情） */
    public void appendHint(CommandSource source, String actor, Map<String, String> params, String result) {
        append(source, actor, null, params, result, -1, -1, -1);
    }

    private void appendToFile(PolicyAuditEntry entry) {
        final Path path = filePath;
        if (path == null) {
            return;
        }
        try {
            Files.writeString(path,
                    entry.tsNanos() + "|" + entry.source() + "|" + entry.actor() + "|" + entry.action()
                            + "|" + entry.params() + "|" + entry.result() + "|v" + entry.version()
                            + "|" + entry.beforeVersion() + "→" + entry.afterVersion() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[PolicyAuditLog] Failed to append audit file (ignored)", e);
        }
    }

    /**
     * 只读快照（最近 limit 条；按时间正序）。
     */
    public List<PolicyAuditEntry> entries(int limit) {
        synchronized (lock) {
            final int size = entries.size();
            final int from = Math.max(0, size - Math.max(0, limit));
            return Collections.unmodifiableList(new ArrayList<>(entries.subList(from, size)));
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }
}
