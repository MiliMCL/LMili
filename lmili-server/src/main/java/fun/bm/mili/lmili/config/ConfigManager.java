package fun.bm.mili.lmili.config;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.lmili.commands.CommandRegister;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

public class ConfigManager {
    private static boolean initialized = false;
    // Mili start - fix: use ConcurrentHashMap instead of HashMap for thread safety
    private static final ConcurrentMap<String, ConfigsInstance> configfiles = new ConcurrentHashMap<>();
    private static final Collection<Runnable> runnableBeforeFinalLoad = new ConcurrentLinkedQueue<>();
    private static final Map<TransformedConfig, String[]> needTransformedConfigs = new ConcurrentHashMap<>();
    // String[]:
    // 0 -> origin key
    // 1 -> target key
    // 2 -> origin full path
    // 3 -> target full path

    // 修复：配置加载超时时间，防止无限阻塞启动
    private static final long CONFIG_LOAD_TIMEOUT_SECONDS = 30;

    public static void initConfigs() {
        configfiles.put("lmili", ConfigsInstance.of(
                new java.io.File("lmili_config"),
                "lmili",
                "lmili_config.toml",
                "lmiliconfig",
                "fun.bm.mili.config.modules"
        ));
        preLoad();
    }

    public static void preLoad() {
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(() -> {
                    try {
                        config.preLoadConfig();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to preload config", e);
                    }
                }))
                .toArray(CompletableFuture[]::new);
        // 修复：使用带超时的 join，防止无限阻塞启动
        try {
            CompletableFuture.allOf(futures).get(CONFIG_LOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Config preLoad timed out after " + CONFIG_LOAD_TIMEOUT_SECONDS + "s", e);
        } catch (Exception e) {
            throw new RuntimeException("Config preLoad failed", e);
        }
        acceptTransformedConfigs();
    }

    public static void loadConfigFiles() {
        runTaskBeforeFinalLoad();
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(config::finalizeLoadConfig))
                .toArray(CompletableFuture[]::new);
        // 修复：使用带超时的 join，防止无限阻塞启动
        try {
            CompletableFuture.allOf(futures).get(CONFIG_LOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Config load timed out after " + CONFIG_LOAD_TIMEOUT_SECONDS + "s", e);
        } catch (Exception e) {
            throw new RuntimeException("Config load failed", e);
        }
        CommandRegister.register(); // register command after config loaded to enable some command didn't depend on config files
        initialized = true;
    }

    public static void registerRunnableBeforeFinalLoad(Runnable runnable) {
        if (initialized) return;
        runnableBeforeFinalLoad.add(runnable);
    }

    public static void registerTransformedConfig(@NotNull String origin, @NotNull String target, @NotNull String originKey, @NotNull String targetKey, TransformedConfig transformedConfig) {
        if (initialized) return;
        needTransformedConfigs.put(transformedConfig, new String[]{origin, target, originKey, targetKey});
    }

    public static ConfigsInstance getConfigs(String name) {
        return configfiles.get(name);
    }

    private static void runTaskBeforeFinalLoad() {
        runnableBeforeFinalLoad.forEach(Runnable::run);
        runnableBeforeFinalLoad.clear();
    }

    private static void acceptTransformedConfigs() {
        Set<ConfigsInstance> toReload = new HashSet<>();
        for (Map.Entry<TransformedConfig, String[]> entry : needTransformedConfigs.entrySet()) {
            String[] config = entry.getValue();
            TransformedConfig transformedConfig = entry.getKey();
            ConfigsInstance origin = getConfigs(config[0]);
            ConfigsInstance target = getConfigs(config[1]);
            if (origin == null || target == null) continue;
            TomlConfigData originConfig = origin.getFileInstance();
            TomlConfigData targetConfig = target.getFileInstance();

            final String oldConfigKeyName = config[2];
            final String newConfigKeyName = config[3];
            Object oldValue = originConfig.get(oldConfigKeyName);
            if (oldValue != null) {
                boolean success = true;
                if (transformedConfig.transform()) {
                    try {
                        for (Class<? extends DefaultTransformLogic> logic : transformedConfig.transformLogic()) {
                            oldValue = logic.getDeclaredConstructor().newInstance().transform(oldValue);
                        }
                        oldValue = new DefaultTransformLogic().transform(oldValue);
                        targetConfig.set(newConfigKeyName, oldValue);
                        if (transformedConfig.transformComments()) {
                            targetConfig.setComment(newConfigKeyName, originConfig.getComment(oldConfigKeyName));
                        }
                    } catch (Exception e) {
                        success = false;
                        target.logger.error("Failed to transform removed config {}!", transformedConfig.name());
                    }
                }

                if (success) origin.removeConfig(oldConfigKeyName, transformedConfig.directory());
            }
            toReload.add(target);
            toReload.add(origin);
        }
        toReload.forEach(ConfigsInstance::saveConfigs);
        needTransformedConfigs.clear(); // free space when all done
    }
}