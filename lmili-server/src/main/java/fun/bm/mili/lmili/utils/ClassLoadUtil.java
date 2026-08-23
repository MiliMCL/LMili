package fun.bm.mili.lmili.utils;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类加载工具 —— 在指定包内查找类。
 *
 * <h3>RISK-06 修复</h3>
 * <p>所有 {@code Class.forName} 调用第二个参数都设为 {@code false}，
 * <b>避免触发 static initializer</b>（即只读 Class 对象、不执行 static 块）。
 *
 * <h3>RISK-07 修复</h3>
 * <p>单 class 加载异常（{@link ClassNotFoundException}、
 * {@link LinkageError}、{@link NoClassDefFoundError}、
 * {@link ExceptionInInitializerError}、{@link SecurityException}）
 * 不会击穿整个扫描流程，而是被记录并跳过该 class。
 *
 * <h3>扫描范围限制</h3>
 * <p>所有扫描都从 {@code MinecraftServer.class.getClassLoader()} 启动，
 * 但 caller 可通过 {@link #getClasses(String, ClassLoader, Set)} 提供
 * 自有 ClassLoader（插件隔离场景）。</p>
 */
public class ClassLoadUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassLoadUtil.class);

    /**
     * 扫描系统 ClassLoader 下指定包内的所有类。
     *
     * <p><b>注意</b>：扫描结果不会触发任何 static 初始化；任何加载异常都被隔离。</p>
     *
     * @param pack 包名（如 {@code "fun.bm.mili.lmili.thread.scheduler"}）
     * @return 找到的类集合（可能为空）
     */
    public static @NotNull Set<Class<?>> getClasses(final String pack) {
        return getClasses(pack, MinecraftServer.class.getClassLoader(), new LinkedHashSet<>());
    }

    /**
     * RISK-06 / RISK-07 修复：通用扫描入口，支持自定义 ClassLoader。
     *
     * <p>所有 {@code Class.forName} 调用第二个参数均为 {@code false}，
     * 防止扫描阶段执行任何 static initializer。</p>
     *
     * @param pack      包名
     * @param loader    用于解析 Class 的 ClassLoader（null 表示系统 ClassLoader）
     * @param collected 调用方提供的收集 set（递归调用复用，避免重复创建）
     * @return 找到的类集合
     */
    public static @NotNull Set<Class<?>> getClasses(
            final String pack,
            @Nullable final ClassLoader loader,
            @NotNull final Set<Class<?>> collected) {
        final String packageDirName = pack.replace('.', '/');
        final ClassLoader effectiveLoader = loader != null ? loader : MinecraftServer.class.getClassLoader();
        final Enumeration<URL> dirs;
        try {
            dirs = effectiveLoader.getResources(packageDirName);
        } catch (IOException e) {
            LOGGER.warn("[ClassLoadUtil] Failed to enumerate resources for package {}", pack, e);
            return collected;
        }

        while (dirs.hasMoreElements()) {
            final URL url = dirs.nextElement();
            final String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                final String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8);
                findClassesInPackageByFile(pack, filePath, effectiveLoader, collected);
            } else if ("jar".equals(protocol)) {
                try {
                    final JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
                    final Enumeration<JarEntry> entries = jar.entries();
                    findClassesInPackageByJar(pack, entries, packageDirName, effectiveLoader, collected);
                } catch (IOException e) {
                    // RISK-07：隔离异常，仅记日志
                    LOGGER.warn("[ClassLoadUtil] Failed to open jar resource {}", url, e);
                }
            } else {
                LOGGER.debug("[ClassLoadUtil] Skipping unsupported protocol {} for {}", protocol, url);
            }
        }

        return collected;
    }

    /**
     * RISK-06 / RISK-07 修复：file: 协议下扫描目录。
     */
    private static void findClassesInPackageByFile(final String packageName,
                                                     final String packagePath,
                                                     final ClassLoader loader,
                                                     final Set<Class<?>> classes) {
        final File dir = new File(packagePath);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        final File[] dirfiles = dir.listFiles((file) -> file.isDirectory() || file.getName().endsWith(".class"));
        if (dirfiles == null) {
            return;
        }
        for (final File file : dirfiles) {
            if (file.isDirectory()) {
                findClassesInPackageByFile(
                        packageName + "." + file.getName(),
                        file.getAbsolutePath(), loader, classes);
            } else {
                final String className = file.getName().substring(0, file.getName().length() - 6);
                loadClassSafe(packageName + '.' + className, loader, classes);
            }
        }
    }

    /**
     * RISK-06 / RISK-07 修复：jar: 协议下扫描条目。
     */
    private static void findClassesInPackageByJar(final String packageName,
                                                   final Enumeration<JarEntry> entries,
                                                   final String packageDirName,
                                                   final ClassLoader loader,
                                                   final Set<Class<?>> classes) {
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.charAt(0) == '/') {
                name = name.substring(1);
            }
            if (!name.startsWith(packageDirName)) {
                continue;
            }
            int idx = name.lastIndexOf('/');
            String resolvedPackageName = packageName;
            if (idx != -1) {
                resolvedPackageName = name.substring(0, idx).replace('/', '.');
            }
            if (name.endsWith(".class") && !entry.isDirectory()) {
                final String className = name.substring(resolvedPackageName.length() + 1, name.length() - 6);
                loadClassSafe(resolvedPackageName + '.' + className, loader, classes);
            }
        }
    }

    /**
     * RISK-06 / RISK-07 修复：安全加载类。
     *
     * <ul>
     *   <li>{@code Class.forName(name, false, loader)} 第二个参数 false，
     *       <b>不触发 static initializer</b></li>
     *   <li>捕获 {@link ClassNotFoundException} / {@link LinkageError} /
     *       {@link NoClassDefFoundError} / {@link ExceptionInInitializerError} /
     *       {@link SecurityException} 等异常，仅记日志跳过该类</li>
     * </ul>
     */
    private static void loadClassSafe(final String className,
                                       final ClassLoader loader,
                                       final Set<Class<?>> classes) {
        try {
            // RISK-06 关键：initialize = false
            final Class<?> clazz = Class.forName(className, false, loader);
            classes.add(clazz);
        } catch (ClassNotFoundException | LinkageError | NoClassDefFoundError
                 | ExceptionInInitializerError | SecurityException e) {
            // RISK-07：隔离异常，不抛出去打断扫描流程
            LOGGER.warn("[ClassLoadUtil] Failed to load class {}: {}", className, e.toString());
        } catch (Throwable t) {
            // 兜底：任何其他异常也隔离
            LOGGER.warn("[ClassLoadUtil] Unexpected error loading class {}", className, t);
        }
    }
}