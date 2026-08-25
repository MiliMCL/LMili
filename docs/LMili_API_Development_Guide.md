# LMili API 开发指南

## 概述

LMili 是一个基于 Paper/Folia 的 Minecraft 服务器核心，提供了统一的调度 API（`UnifiedSchedulerAPI`），让插件开发者能够安全、高效地管理异步和同步任务。

## 核心设计理念

1. **统一管理**：所有插件调度任务必须通过 LMili 统一管理，禁止自行创建线程
2. **身份追踪**：每个任务自动绑定提交它的插件 ID
3. **配额控制**：每个插件有独立的资源配额，防止资源耗尽
4. **安全强制**：违规创建线程会被检测并阻止

## 快速开始

### 1. 添加依赖

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.xucy10:lmili-api:1.0.0")
}
```

### 2. 获取调度器

```java
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.api.PluginScheduler;

public class MyPlugin extends JavaPlugin {
    
    private PluginScheduler scheduler;
    
    @Override
    public void onEnable() {
        // 获取当前插件的调度器
        scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
        
        // 验证调度器可用
        if (scheduler == null) {
            getLogger().severe("Failed to initialize LMili scheduler!");
            return;
        }
        
        getLogger().info("LMili scheduler initialized for plugin: " + 
            scheduler.owner().value());
    }
}
```

## API 使用示例

### 异步任务

```java
// 提交一个异步任务
scheduler.runAsync(() -> {
    // 这里是异步逻辑
    getLogger().info("Running async on thread: " + Thread.currentThread().getName());
});

// 带返回值的异步任务
scheduler.runAsync(() -> {
    // 执行耗时操作
    return fetchDataFromDatabase();
}).thenAccept(data -> {
    // 处理结果
    getLogger().info("Data: " + data);
});
```

### 同步任务（快速查询）

```java
import fun.bm.mili.api.SyncTaskResult;
import fun.bm.mili.api.SyncTaskConstraints;
import java.util.concurrent.TimeUnit;

// 默认约束（5ms 超时）
SyncTaskResult<Integer> result = scheduler.runSync(() -> {
    return player.getInventory().getSize();
});

if (result.isSuccess()) {
    getLogger().info("Inventory size: " + result.value());
} else if (result.isTimeout()) {
    getLogger().warning("Task timed out!");
} else {
    getLogger().warning("Failed: " + result.failureReason());
}

// 自定义约束
SyncTaskResult<String> customResult = scheduler.runSync(() -> {
    return player.getDisplayName();
}, SyncTaskConstraints.builder()
    .timeout(10, TimeUnit.MILLISECONDS)
    .description("get-display-name")
    .build()
);

// 使用 Optional 风格
result.valueOpt().ifPresent(value -> 
    getLogger().info("Got: " + value)
);
```

### 位置绑定任务

```java
import org.bukkit.Location;

Location targetLocation = player.getLocation();

// 在目标位置所在的 region 中执行任务
scheduler.runAt(targetLocation, ctx -> {
    // 可以安全访问该位置的区块数据
    getLogger().info("World: " + ctx.worldName());
    getLogger().info("Region ID: " + ctx.regionId());
});

// 位置绑定同步任务
SyncTaskResult<Boolean> chunkResult = scheduler.runAtSync(targetLocation, () -> {
    return targetLocation.getWorld().isChunkLoaded(
        targetLocation.getBlockX() >> 4,
        targetLocation.getBlockZ() >> 4
    );
});
```

### 实体绑定任务

```java
import fun.bm.mili.api.EntityScheduler;
import fun.bm.mili.api.SyncEntityScheduler;

// 异步实体任务
EntityScheduler entityScheduler = scheduler.forEntity(player);
entityScheduler.run(ctx -> {
    if (player.isValid()) {
        // 安全操作实体
        getLogger().info("Entity health: " + player.getHealth());
    }
});

// 延迟执行
entityScheduler.runDelayed(ctx -> {
    if (player.isValid()) {
        // 100 ticks 后执行
    }
}, 100);

// 同步实体任务
SyncEntityScheduler syncEntityScheduler = scheduler.forEntitySync(player);
SyncTaskResult<Location> locResult = syncEntityScheduler.run(() -> {
    return player.getLocation();
});

// 检查是否可以执行
if (syncEntityScheduler.canExecute()) {
    SyncTaskResult<Double> healthResult = syncEntityScheduler.run(() -> {
        return player.getHealth();
    });
}
```

### 延迟任务

```java
import java.util.concurrent.TimeUnit;

// 延迟 5 秒执行
scheduler.runDelayed(() -> {
    getLogger().info("Delayed task executed!");
}, 5, TimeUnit.SECONDS);

// 延迟 100 ticks（5 秒）
scheduler.runDelayed(() -> {
    getLogger().info("100 ticks later!");
}, 100 * 50, TimeUnit.MILLISECONDS);
```

### 调度指标

```java
import fun.bm.mili.api.UnifiedSchedulerAPI.SchedulerMetrics;

SchedulerMetrics metrics = scheduler.metrics();
getLogger().info("Tasks submitted: " + metrics.tasksSubmitted());
getLogger().info("Tasks running: " + metrics.tasksRunning());
getLogger().info("Tasks completed: " + metrics.tasksCompleted());
getLogger().info("Average execution: " + metrics.averageExecutionMs() + "ms");
getLogger().info("Success rate: " + (metrics.successRate() * 100) + "%");
```

## 安全约束

### 禁止的行为

| 行为 | 状态 | 替代方案 |
|------|------|----------|
| `new Thread(r).start()` | ❌ 禁止 | `scheduler.runAsync(r)` |
| `Executors.newFixedThreadPool()` | ❌ 禁止 | `scheduler.runAsync()` |
| `ScheduledExecutorService` | ❌ 禁止 | `scheduler.runDelayed()` |
| `Bukkit.getScheduler().runTaskTimer()` | ⚠️ 不推荐 | `scheduler.runAsync()` / `runDelayed()` |
| `CompletableFuture.supplyAsync()` | ❌ 禁止 | `scheduler.runAsync()` |

### 同步任务约束

| 约束 | 说明 | 默认值 |
|------|------|--------|
| **超时** | 最大执行时间，超时返回失败 | 5ms |
| **禁止嵌套** | 禁止在同步任务中提交新的同步任务 | true |
| **实体有效性** | 实体绑定任务会检查实体是否仍然有效 | 自动 |
| **服务器过载** | 服务器负载高时同步任务会被拒绝 | 自动 |
| **中断响应** | 任务应响应 Thread.interrupt() | true |

### 同步任务适用场景

| 适用 ✅ | 不适用 ❌ |
|----------|-----------|
| 快速查询（缓存读取） | ❌ IO 操作（数据库、文件、网络） |
| 不可变数据访问 | ❌ 长时间计算 |
| 简单计算 | ❌ 跨 region 数据访问 |
| 实体状态读取 | ❌ 区块加载/生成 |
| 配置读取 | ❌ 事件触发 |

## 配置安全策略

```java
import fun.bm.mili.api.SecurityPolicy;

// 严格模式（默认）- 禁止一切自行创建
SecurityPolicy strict = SecurityPolicy.strict();

// 宽松模式 - 仅审计
SecurityPolicy permissive = SecurityPolicy.permissive();

// 自定义策略
SecurityPolicy custom = SecurityPolicy.builder()
    .allowPluginThreadCreation(false)      // 禁止创建线程
    .allowPluginExecutorCreation(false)     // 禁止创建执行器
    .enforceMode(true)                      // 强制拦截
    .maxConcurrentTasksPerPlugin(32)        // 每插件最大并发
    .maxQueuedTasksPerPlugin(128)           // 每插件最大排队
    .defaultTaskTimeoutMs(60000)            // 默认超时 60s
    .auditLogging(true)                     // 启用审计日志
    .build();
```

## 高级用法

### DAG 系统（依赖执行）

```java
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;

// 注册系统
ModernDagTickExecutor executor = RegionTickDispatcher.getInstance()
    .getDagExecutor();

executor.registerSystem(
    "my_system",
    SystemProfile.builder()
        .name("my_system")
        .build(),
    Scope.GLOBAL,
    (profile, scope) -> {
        // 系统执行逻辑
    }
);

// 声明依赖
executor.addDependency("my_system", "entity_tick");
```

### 跨 Region 任务

```java
import fun.bm.mili.lmili.thread.regiontick.executor.LMiliRegionNodeScheduler;

// 获取跨 region 调度器
LMiliRegionNodeScheduler crossRegionScheduler = 
    LMiliRegionNodeScheduler.getInstance();

// 调度跨 region 任务
crossRegionScheduler.scheduleCrossRegion(
    nodeId,
    targetRegionId,
    () -> {
        // 在目标 region 中执行的任务
    },
    sourceRegionId
);
```

### 自定义配置

```java
import fun.bm.mili.config.modules.misc.LMiliWatchdogConfig;
import fun.bm.mili.config.modules.fixes.LMiliEntityMovingFixConfig;

// 配置看门狗
LMiliWatchdogConfig.tickRegionTimeOutMs = 10000; // 10 秒超时
LMiliWatchdogConfig.enableWatchdog = true;
LMiliWatchdogConfig.logOnTimeout = true;

// 配置实体移动修复
LMiliEntityMovingFixConfig.enabled = true;
LMiliEntityMovingFixConfig.warnOnDetected = true;
LMiliEntityMovingFixConfig.teleportBack = false;
LMiliEntityMovingFixConfig.maxMoveDistance = 16.0;
```

## 最佳实践

### 1. 使用 try-with-resources 管理任务

```java
SyncTaskResult<Result> result = scheduler.runSync(() -> {
    return computeSomething();
});

// 自动处理结果
result.valueOpt().ifPresent(value -> {
    // 使用结果
});
```

### 2. 避免在同步任务中执行 IO

```java
// ❌ 错误：在同步任务中执行 IO
SyncTaskResult<Data> badResult = scheduler.runSync(() -> {
    return database.query(); // 可能超时！
});

// ✅ 正确：使用异步任务
scheduler.runAsync(() -> {
    Data data = database.query();
    // 回到主线程处理结果
    scheduler.runAsync(() -> processResult(data));
});
```

### 3. 使用实体绑定任务确保线程安全

```java
// ❌ 错误：直接访问实体
scheduler.runAsync(() -> {
    // 可能在错误的线程访问实体！
    player.setHealth(20);
});

// ✅ 正确：使用实体绑定任务
scheduler.forEntity(player).run(ctx -> {
    // 在正确的线程安全访问实体
    player.setHealth(20);
});
```

### 4. 监控调度指标

```java
// 定期检查调度指标
scheduler.runDelayed(() -> {
    SchedulerMetrics metrics = scheduler.metrics();
    if (metrics.successRate() < 0.95) {
        getLogger().warning("Scheduler success rate is low: " + 
            (metrics.successRate() * 100) + "%");
    }
}, 60, TimeUnit.SECONDS);
```

## 故障排除

### 常见错误

| 错误 | 原因 | 解决方案 |
|------|------|----------|
| `SecurityException: Plugin not registered` | 插件未在 LMili 中注册 | 检查 plugin.yml 中的 `mili-supported` |
| `Nested sync task detected` | 在同步任务中调用了同步任务 | 使用异步任务替代 |
| `Server overloaded` | 服务器负载过高 | 等待负载降低或增加配额 |
| `Entity is no longer valid` | 实体已被销毁 | 在执行前检查实体有效性 |

### 调试技巧

```java
// 启用详细日志
System.setProperty("lmili.debug", "true");

// 获取详细统计
SchedulerMetrics metrics = scheduler.metrics();
System.out.println("Scheduler metrics: " + metrics);

// 检查线程状态
PluginThreadMonitor monitor = new PluginThreadMonitor(
    new SchedulerSecurityManager()
);
monitor.startMonitoring();
```

## 参考资源

- [UnifiedSchedulerAPI Javadoc](https://javadoc.io/doc/io.github.xucy10/lmili-api)
- [LMili GitHub](https://github.com/MiliMCL/LMili)
- [示例插件](https://github.com/MiliMCL/LMili/tree/main/examples)
