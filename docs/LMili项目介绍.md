# LMili 项目介绍

> 基于 Folia 的高性能 Minecraft 服务端核心：更纯粹的区域多线程体验，更多 API，更稳定。

## 项目定位

LMili（米粒）是一个基于 **Paper → Folia** fork 链的 Minecraft 26.2 服务端核心。项目目标是成为一个**纯粹的 Folia**：不引入生电/红石机制修改与客户端协议魔改，专注于在 Folia 区域多线程调度模型之上提供**更多 API、稳定性修复与 bug 修复**，以及通用的性能优化。

**核心架构变更**：LMili **完全移除了 Folia 原有的 EDF/WorkStealing 调度器**，使用自研的 Mili 调度器作为唯一调度后端。并行 chunk tick（RegionTickPool）已永久启用，无需配置开关。

### 继承链

```
Minecraft（原版）
  └── Paper（服务端框架）
        └── Folia（区域多线程调度）
              └── LMili（本项目）
```

LMili 现为直接基于 Folia 的服务端，内部包名为 `fun.bm.mili.lmili`，上游 Folia commit 为 `57f643f`。

### 与 Mili 的区别

LMili 与 Mili 是同一作者（xucy10）的两个不同项目，定位截然不同：

| 维度 | LMili | Mili |
|------|-------|------|
| 定位 | 纯粹的 Folia，专注 API/稳定性/通用优化 | 生电/技术服核心，含红石与 Rust 原生加速 |
| MC 版本 | 26.2 | 26.1.2 |
| Rust 模块 | 无 | 有（`mili-rust`，JNI 桥接，实体视锥剔除/TOML 解析等） |
| 红石兼容 | 不涉及 | 深度支持（更新抑制、即时方块更新器、旧版行为恢复等） |
| 协议魔改 | 不涉及 | Carpet/Leaves 协议兼容层 |
| API 侧重点 | 调度器 API、跨区挂起、数据包事件 | 红石 API、生电功能 API |
| 仓库模块 | `lmili-api` / `lmili-server` | `mili-api` / `mili-server` / `mili-rust` |

> 简言之：**LMili 是"干净的 Folia 增强版"，Mili 是"加了生电和 Rust 的 Folia"**。

### 命名体系

项目存在"Mili"与"LMili"两套命名，指向不同层面：

| 层面 | 名称 | 说明 |
|------|------|------|
| 品牌/产品名 | LMili | 用户面对的产品简称 |
| GitHub 仓库 | `MiliMCL/LMili` | 代码托管地址 |
| 服务端 JAR | `lmili-26.2-paperclip.jar` | 可运行的服务端产物 |
| Maven 坐标 | `io.github.xucy10:lmili-api:26.2-R0.1` | 插件开发引用坐标 |
| Gradle 模块 | `lmili-api` / `lmili-server` | 见 `settings.gradle.kts` |
| 公共 API 包 | `fun.bm.mili.api` | 插件开发 import 的包名 |
| 内部实现包 | `fun.bm.mili.lmili.*` | 服务端核心实现 |
| 配置文件 | `lmili_config.toml` | 服务端主配置文件 |

历史背景：LMili 前身包含 Luminol 品牌代码，执行 rebrand 后底层包名从 `me.luminolmc.*` 迁移至 `fun.bm.mili.lmili.*`。

---

## 构建与环境

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 25+ | 构建工具链（不是 JDK 21） |
| Gradle | 9.4.1 | Kotlin DSL |
| Git | 2.x | Windows 需启用长路径 |
| 补丁系统 | Hyacinthusweight 2.0.15 | 基于 paperweight 的 fork 管理 |

```bash
# 首次构建
git clone https://github.com/MiliMCL/LMili
cd LMili
git config --global core.longpaths true
./gradlew applyAllPatches --no-configuration-cache --no-build-cache
./gradlew :lmili-server:createPaperclipJar
```

构建产物：`lmili-server/build/libs/lmili-26.2-paperclip.jar`

---

## 项目结构

```
Mili/
├── lmili-api/                  # API 模块（插件开发编译时依赖）
│   └── src/main/java/
│       ├── fun/bm/mili/api/         # 核心调度器 API
│       ├── fun/bm/mili/lmili/api/   # 区域/传送门/事件 API
│       └── org/leavesmc/leaves/     # Bytebuf、Photographer、数据包事件
├── lmili-server/               # 服务端核心（可运行产物）
│   ├── minecraft-patches/     # 补丁系统
│   │   ├── features-original/ #   97 个原始独立补丁（参考用）
│   │   ├── features/          #   5 个合并后补丁（构建使用）
│   │   └── merged-v3/        #   构建实际使用的合并补丁
│   ├── paper-patches/         # Paper API/Server 层补丁
│   └── src/main/java/fun/bm/mili/
│       ├── bridge/            #   区块-区域桥接
│       ├── chunk/             #   区块系统
│       ├── config/            #   配置模块
│       ├── metrics/           #   bStats 统计
│       ├── portal/            #   传送门管理
│       ├── utils/             #   工具类
│       ├── villager/          #   村民优化器
│       └── lmili/             #   核心实现子树
│           ├── thread/        #     调度器核心（54 个文件）
│           ├── config/        #     配置管理器
│           ├── functions/     #     状态栏功能
│           ├── commands/      #     命令系统
│           └── data/          #     区块存储格式
├── folia-server/              # Folia 子模块（上游，不修改）
├── paper-server/              # Paper 服务器（补丁应用目标）
├── paper-api/                 # Paper API（补丁应用目标）
├── docs/                      # 文档
└── build.gradle.kts           # 根构建脚本
```

---

## 核心特性

### 1. Mili 统一调度器 — 替代 Folia 调度器

LMili **完全移除了 Folia 原有的 EDF/WorkStealing 调度器**，使用自研的 Mili 调度器作为唯一调度后端。这不仅是性能优化，更是架构层面的统一：所有 region tick、chunk tick、entity tick 都通过同一套调度系统执行。

**为什么移除 Folia 调度器**：
- Folia 的 EDF 调度器每个 region 独占一个线程，大量空闲 region 时 CPU 占用高
- Folia 的 WorkStealing 调度器缺乏阻塞任务隔离，carrier thread 可能被阻塞操作 pinning
- 两套调度器并存增加了代码复杂度和维护成本
- Mili 调度器已通过完整审计，具备生产环境稳定性

**技术组成**：

| 组件 | 作用 |
|------|------|
| MiliTickRegionScheduler | Folia API 适配层，将 `scheduleRegion()` 委派给 MiliScheduler |
| MiliScheduler + WorkStealingCoordinator | 统一 Worker Pool，全局工作窃取负载均衡 |
| VirtualThreadPool | 基于 JDK 25 虚拟线程，以同步风格编写异步逻辑 |
| BlockingTaskIsolation | 专用平台线程池 + ExecutionToken 转移，防止 carrier pinning |
| RegionTickDispatcher | 并行 chunk tick 调度器（永久启用） |
| DagExecutionEngine | 非阻塞 DAG 执行引擎，支持系统级并行 |

**执行模式**根据区域 chunk 数量自动选择：
- 小 region（chunk 数 < 阈值）：单线程同步执行（无调度开销）
- Virtual Thread 模式：chunks 拆分为 slices，每个 slice 一个 virtual thread 并行
- Platform Thread 模式：贪心负载均衡分发给固定 worker 队列

**线程模型**：

```
TickRegionScheduler (Folia API 适配)
    └── MiliTickRegionScheduler.scheduleRegion(handle)
            └── MiliScheduler.submit(RegionTask)
                    └── WorkStealingCoordinator.submit(task)
                            └── RegionQueue ──(work-stealing)──▶ SchedulerWorker (MiliTickThread)
                                                                            └── ServerLevel.mili$tickRegion()
                                                                                    ├── RegionTickDispatcher.dispatchTick()  ← 并行 chunk tick
                                                                                    └── EntityTickDispatcher.dispatch()      ← 同步 entity tick
```

**调度器核心源码结构**（54 个文件）：

```
lmili/thread/
├── regiontick/                    # Region Tick 系统（并行 chunk tick）
│   ├── RegionTickBootstrap.java   # 启动入口
│   ├── RegionTickDispatcher.java  # region tick 分发器
│   ├── RegionTickExecutor.java    # 执行器接口
│   ├── ChunkTickDispatcher.java   # chunk tick 分派（3 种模式）
│   ├── EntityTickDispatcher.java  # entity tick 分派
│   ├── dag/                       # DAG 依赖图
│   │   ├── SystemGraph.java       # 系统依赖图
│   │   ├── CompiledDag.java       # 编译后 DAG（不可变）
│   │   ├── ConflictGraph.java     # 稀疏冲突图
│   │   └── DagExecutionState.java # 执行状态
│   ├── executor/                  # 执行器
│   │   ├── ModernDagTickExecutor.java  # DAG Tick 执行器
│   │   ├── DagExecutionEngine.java     # DAG 执行引擎
│   │   └── FoliaTickExecutor.java      # Folia 兼容执行器
│   └── suspend/                   # 挂起支持
│       └── MiliThreadFactory.java # 虚拟线程工厂
└── scheduler/                     # 调度器系统
    ├── MiliTickRegionScheduler.java # Folia API 适配器
    ├── MiliSchedulerImpl.java     # 主实现
    ├── MiliSchedulerBuilder.java  # Builder 构建
    ├── api/                       # 调度器接口
    │   ├── MiliScheduler.java
    │   ├── RegionTask.java
    │   ├── EntityTask.java
    │   └── TaskHandle.java
    ├── execute/                   # 执行层
    │   ├── WorkStealingCoordinator.java  # 工作窃取
    │   ├── VirtualThreadPool.java        # 虚拟线程池
    │   ├── RegionQueue.java              # region 队列
    │   └── BlockingTaskIsolation.java    # 阻塞隔离
    └── internal/                  # 内部组件
        ├── ExecutionContext.java  # 执行上下文
        ├── ObjectPool.java        # 对象池
        └── PerformanceMetrics.java # 性能指标
```

### 2. Folia 稳定性修复

| 修复项 | 说明 |
|--------|------|
| Region Balancer | 共享线程池 + 优先级队列（已废弃，被 RegionTickPool 完全替代） |
| Region Load Monitor | 无锁滑动窗口统计区域 tick 耗时 |
| Adaptive TPS Manager | 根据实时负载动态调整 TPS |
| Cross-Region Helper | 类型化跨区事件队列（红石信号、实体伤害、方块通知等） |
| RegionTaskIdRegistry | 全局 UUID 注册中心，防止跨区块任务 ID 碰撞导致崩溃 |
| 全局实体计数器 | 按区域聚合 mob 数量，避免 O(entities) 扫描 |
| 线程安全加固 | 全局 `catch(Exception)` → `catch(Throwable)`，防止 OOM/StackOverflow 导致调度器线程静默死亡 |

### 3. Bug 修复

大量针对 Folia 区域线程模型的修复：
- 玩家重生位置修正、实体传送（跨区/末影珍珠/维度切换）竞态修复
- 区域外寻路/拴绳/目标选择防护
- POI 更新延迟、区块重载检测、龙部件同步修复
- RegionizedTaskQueue 并发引用修正
- `RegionizedWorldData` 空连接 NPE、已移除实体仍添加效果、`/save-all` 区域安全化

### 4. 通用性能优化

来自 Gale / Lithium / Pufferfish / SparklyPaper / Kaiiju / Petal / Krypton / Leaves 等上游的通用优化（不涉及生电行为修改）：
- 噪声生成、AI 属性集合、大脑映射、准则映射等数据结构优化
- 实体移动零位移跳过、可变实体唤醒时长、canSee 检查优化
- 区块加载查找削减、投射物区块加载削减、寻路区域限制
- 网络与协议层优化、区块增量压缩
- 村民 lobotomize（发呆）优化、传感器工作削减
- 异步寻路、动态视距、实体数据脏追踪、CPU 亲和性、SIMD 优化

### 5. API 扩展

在 Folia/Paper API 之上提供更多能力：

| API | 说明 |
|------|------|
| Mili 调度器 API | 挂起式调度器（`Mili`、`Scheduler`、`EntityScheduler`、`EntityTaskContext`），virtual thread 后端，支持 `awaitCrossRegion` 跨区挂起 |
| Tick Regions API | 查询/操作 tick 区域（`ThreadedRegionizer`、`ThreadedRegion`、`TickRegionData`、`RegionStats`） |
| ReplayMod 摄影师 | 创建 ReplayMod 摄影师实体录像（`Photographer` / `PhotographerManager`） |
| Bytebuf API | 自定义数据包读写（Netty 风格，独立于 NMS） |
| 数据包事件 | `PacketInEvent` / `PacketOutEvent` 监听数据包收发，可取消 |
| 实体传送异步事件 | `EntityTeleportAsyncEvent`、`PreEntityPortalEvent`、`PostEntityPortalEvent` |
| 传送门事件 | `PortalLocateEvent`（可修改目标）、`EndPlatformCreateEvent`（可取消） |
| 玩家事件 | `PostPlayerRespawnEvent`、`PlayerOperationLimitEvent` |
| Waypoint API | 实体路径点追踪与恢复 |

**调度器 API 使用示例**：

```java
// 获取调度器
Scheduler scheduler = Mili.scheduler();

// 绑定实体执行任务（虚拟线程，看起来同步但非阻塞）
scheduler.forEntity(entity).run(ctx -> {
    Entity e = ctx.getEntity();  // 实体消失时抛 EntityOrphanedException

    // 跨 region 计算并等待结果（虚拟线程挂起，不阻塞 carrier）
    Location target = new Location(world, x, y, z);
    String result = ctx.awaitCrossRegion(target, targetCtx -> {
        // 在目标 region 执行，拿到结果后自动回到原 region 继续
        return doSomethingAt(targetCtx.getEntity());
    });

    // 继续在原 region 执行
    e.sendMessage("结果: " + result);
});

// 延迟执行
scheduler.forEntity(entity).runDelayed(ctx -> {
    ctx.getEntity().sendMessage("3 秒后触发");
}, 60L);  // 60 tick = 3 秒

// 异步执行
scheduler.runAsync(() -> {
    // 在虚拟线程上运行
});
```

---

## 配置系统

LMili 使用 TOML 配置文件（纯 Java night-config 解析实现），主配置文件为 `lmili_config.toml`。

配置共 **73 个配置类**，按功能域分为 6 大类：

| 类别 | 数量 | 说明 | 代表模块 |
|------|------|------|----------|
| `function` | 17 | 游戏机制与实用功能 | `TpsBarConfig`、`RegionBarConfig`、`ReplayAPIConfig`、`BytebufProtocolConfig`、`VillagerTradeConfig`、`PortalRateLimiterConfig` |
| `experiment` | 8 | 实验性性能/并发功能 | **`RegionTickPoolConfig`**（核心调度增强）、`RegionBalancerConfig`、`CrossRegionHelperConfig`、`GlobalEntitiesCounter` |
| `optimizations` | 22 | 性能优化 | `NetworkOptimizerConfig`、`ChunkSystemConfig`、`AsyncPathfindingConfig`、`DynamicViewDistanceConfig`、`SIMDConfig`、`CpuAffinityConfig` |
| `fixes` | 11 | 崩溃/行为修复 | `CollisionBehaviorConfig`、`PortalLinkFixConfig`、`PathfindingFixesConfig`、`POIRangeFixes`、`FoliaEntityMovingFixConfig` |
| `misc` | 14 | 杂项 | `AutoUpdateConfig`、`BStatsConfig`、`FoliaWatchdogConfig`、`SentryConfig`、`InorderChatConfig` |
| `unsupported` | 1 | 不支持的配置 | `DisableCheckForFoliaSupported` |

配置管理器（`fun.bm.mili.lmili.config.ConfigManager`）支持热重载、命令编辑（`/lmiconfig`）和 GUI 编辑（`/lmiconfig open-gui`）。

---

## 补丁系统

LMili 使用 **Hyacinthusweight**（基于 paperweight）补丁系统管理多层 fork。

**原始补丁**：97 个独立 feature 补丁（`features-original/`，参考用）。

**合并补丁**：5 个按功能域合并的补丁（`features/` 和 `merged-v3/`，构建使用）：

| 补丁文件 | 合并数 | 功能域 | 行数 |
|---------|--------|--------|------|
| `01-rebrand-consolidated.patch` | 2 | 品牌重命名（Luminol → Mili） | 99 |
| `02-config-system-consolidated.patch` | 21 | 配置系统 | 1033 |
| `03-entity-optimizations-consolidated.patch` | 66 | 实体优化 | 4268 |
| `04-chunk-region-consolidated.patch` | 6 | 区块与区域 | 981 |
| `06-misc-consolidated.patch` | 2 | 杂项 | 254 |

**补丁工作流**：
1. 在 `lmili-server/src/minecraft/java/` 中修改代码
2. 提交变更：`git commit -m "描述"`
3. 重建补丁：`./gradlew :lmili-server:rebuildAllServerPatches`
4. 提交补丁文件并推送

> 直接编辑 `src/minecraft/java/` 下的文件会被 `applyAllPatches` 覆盖，必须通过补丁文件修改。

---

## API 模块详解（`lmili-api`）

API 模块共 33 个 Java 文件，分属 4 个包：

### `fun.bm.mili.api` — 核心调度器 API

| 文件 | 说明 |
|------|------|
| `Mili.java` | 公共 API 入口，`Mili.scheduler()` 获取调度器实例 |
| `Scheduler.java` | 调度器接口：`forEntity`、`runAt`、`runAsync` |
| `EntityScheduler.java` | 实体调度器：`run`、`runDelayed`，任务绑定到实体归属 region |
| `EntityTaskContext.java` | 任务执行上下文：`getEntity`、`awaitCrossRegion` 跨区挂起 |
| `EntityOrphanedException.java` | 实体不存在时抛出 |
| `MiliPlugin.java` | 插件元数据交互（plugin.yml 声明 `mili-supported: true`） |
| `MiliUsageTracker.java` | 追踪插件 API 使用情况 |

### `fun.bm.mili.lmili.api` — 区域/事件 API

| 文件 | 说明 |
|------|------|
| `ThreadedRegionizer.java` | 区域化管理器 |
| `ThreadedRegion.java` | 单个区域 |
| `TickRegionData.java` | 区域 tick 数据 |
| `RegionStats.java` | 区域统计 |
| `portal/PortalLocateEvent.java` | 传送门定位事件（可修改目标） |
| `portal/EndPlatformCreateEvent.java` | 末地平台创建事件（可取消） |
| `entity/EntityTeleportAsyncEvent.java` | 实体异步传送事件 |
| `entity/PreEntityPortalEvent.java` | 实体传送门前事件 |
| `entity/PostEntityPortalEvent.java` | 实体传送门后事件 |
| `entity/player/PostPlayerRespawnEvent.java` | 玩家重生后事件 |

### `org.leavesmc.leaves` — Leaves 兼容 API

| 文件 | 说明 |
|------|------|
| `bytebuf/Bytebuf.java` | Netty 风格数据包读写 |
| `bytebuf/PacketType.java` | 数据包类型枚举 |
| `event/bytebuf/PacketInEvent.java` | 入站数据包事件 |
| `event/bytebuf/PacketOutEvent.java` | 出站数据包事件 |
| `event/player/PlayerOperationLimitEvent.java` | 玩家操作限流事件 |
| `entity/photographer/Photographer.java` | ReplayMod 摄影师实体 |
| `entity/photographer/PhotographerManager.java` | 摄影师管理器 |

### `gg.pufferfish.pufferfish` — Pufferfish 兼容 API

| 文件 | 说明 |
|------|------|
| `simd/SIMDDetection.java` | SIMD 指令检测 |
| `simd/SIMDChecker.java` | SIMD 可用性检查 |
| `sentry/SentryContext.java` | Sentry 错误追踪上下文 |

---

## 服务端核心模块详解（`lmili-server`）

### 调度器核心（`lmili/thread/`）

54 个文件，是 LMili 最大的子系统。分两大块：

- **`regiontick/`**（28 个文件）：Region Tick 调度实现
  - `dag/`：DAG 依赖图系统（`SystemGraph` 构建依赖、`CompiledDag` 不可变编译结果、`ConflictGraph` 稀疏冲突检测）
  - `executor/`：执行器（`ModernDagTickExecutor` 基于 DAG 的 tick 执行器、`DagExecutionEngine` 非阻塞回调引擎）
  - `suspend/`：虚拟线程工厂

- **`scheduler/`**（26 个文件）：新调度系统
  - `api/`：公共接口（`MiliScheduler`、`RegionTask`、`EntityTask`、`TaskHandle`）
  - `execute/`：执行组件（`VirtualThreadPool`、`WorkStealingCoordinator`、`RegionQueue`、`BlockingTaskIsolation`）
  - `internal/`：内部实现（`ObjectPool`、`PerformanceMetrics`、`ExecutionContext`）
  - `tick/`：Tick 支持（`TickState`、`TickContext`、`TickBarrier`）

### 区块系统（`chunk/`）

12 个文件，管理区块生命周期与热度：
- `ChunkPipeline`：区块处理管线
- `ChunkLifecycleManager`：生命周期管理
- `ChunkHotness` / `ChunkHotnessUpdater`：区块热度追踪
- `ChunkViewDistanceOptimizer`：动态视距优化
- `AsyncChunkProcessor`：异步区块处理

### 配置系统（`config/`）

73 个配置类按 6 个功能域组织，配合 `ConfigManager` 实现热重载和命令编辑。

### 命令系统（`lmili/commands/`）

14 个文件，提供两大命令组：
- `/lmiconfig`：配置管理（set/reset/reload/submit/gui/clean）
- `/lmibar`：状态栏切换（toggle/config）

### 状态栏（`lmili/functions/bars/`）

6 个文件，提供三种实时状态栏：
- `GlobalServerTpsBar`：TPS 监控
- `GlobalServerRegionBar`：Region 负载
- `GlobalServerMemoryBar`：内存使用

### 数据格式（`lmili/data/`）

5 个文件，实现 Linear V2 区块存储格式：
- `BufferedLinearRegionFile`：缓冲线性 region 文件
- `LinearFormatMigrator`：格迁移器
- `ChunkCompressor`：区块压缩

### 村民优化（`villager/`）

9 个文件，实现村民 lobotomize + 智能补货：
- `VillagerOptimizer`：优化器主类
- `VillagerAIController`：AI 控制器
- `VillagerRestockProcessor`：补货处理器
- `VillagerActivityPolicy`：活动策略

---

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.xucy10</groupId>
    <artifactId>lmili-api</artifactId>
    <version>26.2-R0.1</version>
    <scope>provided</scope>
</dependency>
```

Gradle Kotlin DSL：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.xucy10:lmili-api:26.2-R0.1")
}
```

---

## 相关链接

| 项目 | 链接 |
|------|------|
| LMili 仓库 | https://github.com/MiliMCL/LMili |
| Folia（直接上游） | https://github.com/PaperMC/Folia |
| Paper | https://github.com/PaperMC/Paper |
| Luminol（多数代码移植出处，已删库） | https://github.com/LuminolMC/Luminol |

## 许可证

GPL-3.0
