# LMili 统一调度 API

## 概述

LMili 统一调度 API 要求**所有插件的调度任务必须由 LMili 统一管理**，禁止插件自行创建线程、ExecutorService 或 ScheduledExecutor。

## 核心设计原则

1. **统一管理**：所有异步任务、延迟任务、实体绑定任务通过 LMili 调度器提交
2. **身份追踪**：每个任务自动绑定提交它的插件 ID
3. **配额控制**：每个插件有独立的资源配额，防止资源耗尽
4. **安全强制**：违规创建线程会被检测并阻止（强制模式）

## 快速开始

### 1. 获取调度器

```java
import fun.bm.mili.api.UnifiedSchedulerAPI;
import fun.bm.mili.api.PluginScheduler;

// 获取当前插件的调度器（自动识别调用方）
PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();
```

### 2. 提交异步任务

```java
// 提交一个异步任务（在 LMili 管理的线程池中执行）
scheduler.runAsync(() -> {
    // 这里是异步逻辑
    System.out.println("Running async on thread: " + Thread.currentThread().getName());
});
```

### 3. 在指定位置执行任务

```java
import org.bukkit.Location;
import fun.bm.mili.api.EntityTaskContext;

Location targetLocation = player.getLocation();

// 在目标位置所在的 region 中执行任务
scheduler.runAt(targetLocation, (EntityTaskContext ctx) -> {
    // 可以安全访问该位置的区块数据
    System.out.println("Running at location: " + ctx.worldName());
});
```

### 4. 实体绑定任务

```java
import org.bukkit.entity.Entity;

Entity targetEntity = player;

// 提交绑定到实体的任务（在实体所在 region 中顺序执行）
scheduler.forEntity(targetEntity).run((EntityTaskContext ctx) -> {
    // 可以安全访问实体数据
    if (targetEntity.isValid()) {
        // 操作实体
    }
});

// 延迟执行
scheduler.forEntity(targetEntity).runDelayed((EntityTaskContext ctx) -> {
    // 100 ticks 后执行
}, 100);
```

### 5. 获取调度指标

```java
import fun.bm.mili.api.UnifiedSchedulerAPI.SchedulerMetrics;

SchedulerMetrics metrics = scheduler.metrics();
System.out.println("Tasks submitted: " + metrics.tasksSubmitted());
System.out.println("Tasks running: " + metrics.tasksRunning());
System.out.println("Tasks completed: " + metrics.tasksCompleted());
System.out.println("Average execution: " + metrics.averageExecutionMs() + "ms");
System.out.println("Success rate: " + (metrics.successRate() * 100) + "%");
```

### 6. 同步任务（快速查询场景）

```java
import fun.bm.mili.api.SyncTaskResult;
import fun.bm.mili.api.SyncTaskConstraints;

// 默认约束（5ms 超时）- 适用于快速查询
SyncTaskResult<Integer> result = scheduler.runSync(() -> {
    // 快速计算，不要做 IO 操作
    return player.getInventory().getSize();
});

if (result.isSuccess()) {
    System.out.println("Inventory size: " + result.value());
} else if (result.isTimeout()) {
    System.out.println("Task timed out!");
} else {
    System.out.println("Failed: " + result.failureReason());
}

// 自定义约束 - 更宽松的超时
SyncTaskResult<String> customResult = scheduler.runSync(() -> {
    return player.getDisplayName();
}, SyncTaskConstraints.builder()
    .timeout(10, TimeUnit.MILLISECONDS)
    .description("get-display-name")
    .build()
);

// 使用 Optional 风格
result.valueOpt().ifPresent(value -> System.out.println("Got: " + value));
```

### 7. 位置绑定同步任务

```java
import org.bukkit.Location;

Location targetLocation = player.getLocation();

// 在目标位置所在的 region 中同步执行
SyncTaskResult<Boolean> chunkResult = scheduler.runAtSync(targetLocation, () -> {
    // 可以安全访问该位置的区块数据
    return targetLocation.getWorld().isChunkLoaded(
        targetLocation.getBlockX() >> 4,
        targetLocation.getBlockZ() >> 4
    );
});
```

### 8. 实体绑定同步任务

```java
import fun.bm.mili.api.SyncEntityScheduler;

// 获取实体绑定同步调度器
SyncEntityScheduler entityScheduler = scheduler.forEntitySync(player);

// 同步执行实体操作
SyncTaskResult<Location> locResult = entityScheduler.run(() -> {
    return player.getLocation();
});

// 检查是否可以执行
if (entityScheduler.canExecute()) {
    SyncTaskResult<Double> healthResult = entityScheduler.run(() -> {
        return ((org.bukkit.entity.LivingEntity) player).getHealth();
    });
}
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

同步任务虽然立即执行，但必须遵守以下约束以防止卡顿：

| 约束 | 说明 | 默认值 |
|------|------|--------|
| **超时** | 最大执行时间，超时返回失败 | 5ms |
| **禁止嵌套** | 禁止在同步任务中提交新的同步任务 | true |
| **实体有效性** | 实体绑定任务会检查实体是否仍然有效 | 自动 |
| **服务器过载** | 服务器负载高时同步任务会被拒绝 | 自动 |
| **中断响应** | 任务应响应 Thread.interrupt() | true |

### 同步任务适用场景

| 适用 | 不适用 |
|------|--------|
| 快速查询（缓存读取） | ❌ IO 操作（数据库、文件、网络） |
| 不可变数据访问 | ❌ 长时间计算 |
| 简单计算 | ❌ 跨 region 数据访问 |
| 实体状态读取 | ❌ 区块加载/生成 |
| 配置读取 | ❌ 事件触发 |

### 违规处理

**强制模式（默认）**：
- 违规创建线程会抛出 `SecurityException`
- 任务提交会被拒绝
- 安全事件记录到审计日志

**审计模式**：
- 违规仅记录日志
- 不阻止操作（用于调试/兼容）

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

## 插件注册

插件必须在 `lmili.json` 中声明使用调度 API：

```json
{
  "id": "myplugin",
  "version": "1.0.0",
  "scheduler": {
    "delegation": "lmili_required"
  }
}
```

`delegation` 值说明：
- `lmili_required`（默认）：必须通过 LMili 调度，禁止自行创建
- `lmili_optional`：建议使用 LMili，但不强制
- `bukkit_compatible`：兼容模式，允许使用 Bukkit Scheduler（不推荐）

## FAQ

### Q: 我需要使用第三方库，它内部创建了线程怎么办？

A: 使用 `SafeThread.create()` 创建受控线程，或联系库作者适配 LMili 调度 API。

### Q: 我的插件有实时性要求，需要专用线程怎么办？

A: 提交任务时设置高优先级，或使用 `runAt()` 绑定到特定 region。
如果确实需要专用线程，请联系 LMili 团队评估。

### Q: 如何迁移现有的 Bukkit Scheduler 代码？

| 旧代码 | 新代码 |
|--------|--------|
| `Bukkit.getScheduler().runTask(plugin, task)` | `scheduler.runAsync(task)` |
| `Bukkit.getScheduler().runTaskLater(plugin, task, delay)` | `scheduler.runDelayed(task, delay * 50, TimeUnit.MILLISECONDS)` |
| `Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period)` | 使用 `runDelayed` 循环或 DAG 系统 |
| `Bukkit.getScheduler().runTaskAtLocation(loc, task)` | `scheduler.runAt(loc, ctx -> task.run())` |

### Q: 同步任务会不会造成服务器卡顿？

A: 同步任务有多层防护机制确保不会卡顿：
- **超时控制**：默认 5ms 超时，超时立即返回失败
- **Watchdog 机制**：超时后自动中断任务线程
- **嵌套检测**：禁止嵌套同步任务，防止死锁
- **负载感知**：服务器过载时自动拒绝新的同步任务
- **审计追踪**：记录所有慢任务，便于排查问题

如果你的同步任务经常超时，说明任务不适合同步执行，请改用异步任务：
```java
// 同步任务经常超时
SyncTaskResult<Data> result = scheduler.runSync(() -> loadFromDatabase());
if (result.isTimeout()) {
    // 改用异步
    scheduler.runAsync(() -> {
        Data data = loadFromDatabase();
        // 回到主线程处理结果
        scheduler.runAsync(() -> processResult(data));
    });
}
```
