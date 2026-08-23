# LMili（米粒）核心插件开发指南

> 版本 26.2-R0.1 | JDK 25+ | GitHub: [MiliMCL/LMili](https://github.com/MiliMCL/LMili)
>
> 基于 Folia 的高性能 Minecraft 服务端核心

---

## 目录

- [1. 项目概述](#1-项目概述)
  - [1.1 Mili 与 LMili 的命名关系](#11-mili-与-lmili-的命名关系)
  - [1.2 与 Folia 的核心差异](#12-与-folia-的核心差异)
  - [1.3 开发环境要求](#13-开发环境要求)
- [2. 插件项目搭建](#2-插件项目搭建)
  - [2.1 添加依赖](#21-添加依赖)
  - [2.2 在 plugin.yml 中声明 Mili 支持](#22-在-pluginyml-中声明-mili-支持)
  - [2.3 代码中检查 Mili 是否可用](#23-代码中检查-mili-是否可用)
- [3. Mili 调度器 API（核心特性）](#3-mili-调度器-api核心特性)
  - [3.1 设计对比：Folia 双回调 vs LMili 挂起式](#31-设计对比folia-双回调-vs-lmili-挂起式)
  - [3.2 核心类与接口](#32-核心类与接口)
  - [3.3 Mili 入口 API](#33-mili-入口-api)
  - [3.4 Scheduler 接口 — 调度器主入口](#34-scheduler-接口--调度器主入口)
  - [3.5 EntityScheduler 接口 — 实体绑定调度](#35-entityscheduler-接口--实体绑定调度)
  - [3.6 EntityTaskContext — 任务上下文](#36-entitytaskcontext--任务上下文)
  - [3.7 完整示例](#37-完整示例)
- [4. Tick Regions API](#4-tick-regions-api)
  - [4.1 核心接口](#41-核心接口)
  - [4.2 使用方式](#42-使用方式)
- [5. Bytebuf — 自定义数据包 API](#5-bytebuf--自定义数据包-api)
  - [5.1 Bytebuf 接口](#51-bytebuf-接口)
  - [5.2 PacketType — 数据包类型标识](#52-packettype--数据包类型标识)
  - [5.3 PacketEvent / PacketInEvent / PacketOutEvent](#53-packevent--packetinevent--packetoutevent)
  - [5.4 示例：监听并修改数据包](#54-示例监听并修改数据包)
- [6. 事件 API](#6-事件-api)
  - [6.1 传送与门户事件](#61-传送与门户事件)
  - [6.2 玩家事件](#62-玩家事件)
  - [6.3 事件示例](#63-事件示例)
- [7. Photographer API — 录像与回放](#7-photographer-api--录像与回放)
  - [7.1 核心接口](#71-核心接口)
  - [7.2 使用示例](#72-使用示例)
- [8. 服务器运行时特性与插件开发注意事项](#8-服务器运行时特性与插件开发注意事项)
  - [8.1 RegionTickPool — 独立 tick 调度增强](#81-regiontickpool--独立-tick-调度增强)
  - [8.2 异常安全性差异](#82-异常安全性差异)
  - [8.3 全局任务 ID 注册（RegionTaskIdRegistry）](#83-全局任务-id-注册regiontaskidregistry)
  - [8.4 虚拟线程（Virtual Thread）行为](#84-虚拟线程virtual-thread行为)
  - [8.5 配置系统](#85-配置系统)
  - [8.6 MiliPlugin 工具类](#86-miliplugin-工具类)
- [9. 关键配置项速查](#9-关键配置项速查)
- [10. 插件开发最佳实践](#10-插件开发最佳实践)
- [附录 A：包名参考与项目结构](#附录-a包名参考与项目结构)
- [附录 B：构建与运行](#附录-b构建与运行)

---

## 1. 项目概述

LMili（也简称 Mili，中文"米粒"）是一个基于 Folia 的高性能 Minecraft 服务端核心（版本 26.2），使用纯 Java 25 构建。它不提供任何客户端协议魔改或生电/红石机制修改，而是专注于在 Folia 区域多线程调度模型之上提供更多 API、稳定性修复与性能优化。

继承链：Minecraft → Paper → Folia → LMili

### 1.1 Mili 与 LMili 的命名关系

本项目存在"Mili"与"LMili"两套命名，二者指向同一产品：

| 层面 | 名称 | 说明 |
|------|------|------|
| 品牌 / 产品名 | Mili | 用户-facing 的产品简称 |
| GitHub 仓库 | `MiliMCL/LMili` | 代码托管地址 |
| 服务端核心 JAR | `lmili-26.2-paperclip.jar` | 可运行的服务端产物 |
| Maven 发布坐标 | `io.github.xucy10:lmili-api:26.2-R0.1` | 插件开发引用坐标 |
| API 模块 | `lmili-api` | Gradle 模块名（`settings.gradle.kts`） |
| 服务端模块 | `lmili-server` | Gradle 模块名，包含 NMS 补丁 |
| 公共 API 包 | `fun.bm.mili.api` | 插件开发 import 的包名（保持 Mili 命名） |
| 内部 API 包 | `fun.bm.mili.lmili.api` | 区域操作、内部事件等 |
| 内部实现包 | `fun.bm.mili.lmili.*` | 服务端核心实现（调度器、配置等） |
| 配置文件 | `lmili_config.toml` | 服务端主配置文件 |
| 补丁命名 | `015-Rebrand-to-Miki.patch`、`018-Rename-package-me-earthme-luminol-to-fun-bm-mili-lmili.patch` | 重命名补丁（Luminol → LMili） |

> **历史背景**：LMili 前身包含 Luminol 品牌代码。执行 rebrand 后，模块名从 `mili-server` 改为 `lmili-server`，API 包装层与公共 API 模块从 `mili-api` 改为 `lmili-api`。底层包名从 `me.luminolmc.*` 迁移至 `fun.bm.mili.lmili.*`。

### 1.2 与 Folia 的核心差异

LMili 在 Folia 的基础上做出了以下关键变更，直接影响插件开发方式：

- **调度器增强（RegionTickPool）**：将 Folia "每区域独占一线程" 的模型替换为共享 worker 池 + 优先级权衡，显著减少空闲线程的 CPU 占用。
- **异常安全加固**：所有调度器和线程池的 catch 块由 `catch(Exception)` 改为 `catch(Throwable)`，防止 `OutOfMemoryError` / `StackOverflowError` 导致 tick 线程静默死亡。
- **跨区任务 ID 全局注册**：`RegionTaskIdRegistry` 使用全局 UUID 防止跨区块任务 ID 碰撞导致崩溃。
- **虚拟线程支持（JDK 25）**：RegionTickPool 支持 virtual thread 作为 worker，调度器 API 以 virtual thread 为后端，以同步风格编写异步逻辑。
- **丰富的 API 扩展**：Tick Regions API、Bytebuf 数据包 API、传送门事件 API、Photographer 录像 API 等。
- **纯 Java 配置系统（night-config TOML）**：替代 Folia 的配置方式，且插件可通过一致方式监听配置变更。

### 1.3 开发环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 25+ | 构建工具链，不是 JDK 21 |
| Git | 2.x | 需启用长路径支持（Windows） |
| Minecraft | 26.2 | 目标服务端版本 |
| Folia | 57f643f | 直接上游 ref |
| lmili-api | 26.2-R0.1 | Maven 发布坐标 |

---

## 2. 插件项目搭建

### 2.1 添加依赖

LMili 的 API 坐标发布至 Maven Central（坐标：`io.github.xucy10:lmili-api:26.2-R0.1`），在项目中添加依赖：

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.xucy10:lmili-api:26.2-R0.1")
}
```

**Gradle (Groovy)**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    compileOnly 'io.github.xucy10:lmili-api:26.2-R0.1'
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.xucy10</groupId>
    <artifactId>lmili-api</artifactId>
    <version>26.2-R0.1</version>
    <scope>provided</scope>
</dependency>
```

### 2.2 在 plugin.yml 中声明 Mili 支持

在 plugin.yml 中添加 `mili-supported: true` 声明，这是一个弱约束的标记，用于告知 LMili 服务端和用户此插件已适配 Mili API。声明此属性不影响插件正常加载。

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.MyPlugin
api-version: '1.21'
mili-supported: true
```

### 2.3 代码中检查 Mili 是否可用

建议在 `onEnable()` 中检查 Mili 是否正在运行，以提供优雅降级：

```java
import fun.bm.mili.api.Mili;

@Override
public void onEnable() {
    if (Mili.isSupported()) {
        getLogger().info("Mili scheduler v" + Mili.version() + " detected, enhancing performance");
    } else {
        getLogger().warning("Mili not detected, falling back to standard scheduler");
    }
}
```

---

## 3. Mili 调度器 API（核心特性）

LMili 引入了基于 virtual thread 的"挂起式"调度器 API（挂起-yield 而非阻塞），以替代 Folia 传统的"双回调"模式。你可以用同步风格编写异步代码，让任务在实体所属区域的 tick 线程上安全执行。

### 3.1 设计对比：Folia 双回调 vs LMili 挂起式

| Folia（双回调） | LMili（挂起式） |
|-----------------|---------------|
| `entity.getScheduler().run(plugin, task, retired)` | `Mili.scheduler().forEntity(e).run(ctx -> {...})` |
| 必须写 retired 回调处理实体消失 | 框架自动检测并抛 `EntityOrphanedException` |
| 两个回调可能在不同线程执行 | 单回调在 virtual thread 上，上下文一致 |
| 容易遗漏 retired 分支导致任务泄漏 | 异常驱动模式，遗漏处理会快速暴露问题 |

### 3.2 核心类与接口

| 类/接口 | 包路径 | 说明 |
|---------|--------|------|
| `Mili` | `fun.bm.mili.api` | 入口类，静态方法获取调度器、检查支持状态 |
| `Scheduler` | `fun.bm.mili.api` | 顶级调度器，forEntity / runAt / runAsync |
| `EntityScheduler` | `fun.bm.mili.api` | 实体绑定调度器，run / runDelayed |
| `EntityTaskContext` | `fun.bm.mili.api` | 任务执行上下文，getEntity / withEntity / awaitCrossRegion |
| `EntityOrphanedException` | `fun.bm.mili.api` | 实体消失时抛出的受检异常 |
| `MiliPlugin` | `fun.bm.mili.api` | plugin.yml 元数据检查、使用追踪 |
| `MiliUsageTracker` | `fun.bm.mili.api` | 使用追踪统计 |

### 3.3 Mili 入口 API

`Mili` 类提供三个静态方法：

- `Mili.scheduler()` —— 获取全局 Scheduler 实例。如果 RegionTickPool 未启用，返回一个 no-op 调度器（所有调用不执行也不抛异常）。
- `Mili.isSupported()` —— 检查 RegionTickPool 是否已初始化，用于运行时检测。
- `Mili.version()` —— 返回调度器版本字符串（如 "4.0.0-public"），未初始化返回 "unknown"。

### 3.4 Scheduler 接口 — 调度器主入口

- `forEntity(Entity)` —— 获取绑定了指定实体的 EntityScheduler。任务会在实体所在区域的 tick 线程中执行。
- `runAt(Location, Consumer<EntityTaskContext>)` —— 在指定坐标（必须已加载）的 tick 线程上执行任务。如果目标 region 未加载则忽略。
- `runAsync(Runnable)` —— 在独立 virtual thread 上异步执行任务，适用于 IO 密集或阻塞操作（数据库查询、HTTP 请求等）。

### 3.5 EntityScheduler 接口 —— 实体绑定调度

- `run(Consumer<EntityTaskContext>)` —— 在当前 tick 立即提交任务。如果实体已死亡或消失，抛 `EntityOrphanedException`。
- `runDelayed(Consumer<EntityTaskContext>, long delayTicks)` —— 延迟指定 tick 数执行。延迟基于服务器 tick（20 TPS = 50ms/tick），受区域 tick 频率影响。

### 3.6 EntityTaskContext — 任务上下文

- `getEntity()` —— 获取当前绑定的实体（Bukkit Entity），失效则抛 `EntityOrphanedException`。
- `withEntity(EntityFunction<Entity, T>)` —— 安全执行实体操作，自动检查存活状态。
- `checkAlive()` —— 快速检查实体是否仍存活，否则抛异常。
- `awaitCrossRegion(Location, Computation)` —— 在目标位置执行计算并等待返回。同区域直接执行；跨区域则调度到目标 region 并挂起 virtual thread 等待结果。这是 LMili 的核心创新——用同步风格处理跨区逻辑。
- `uniqueId()` / `worldName()` —— 获取绑定实体的 UUID 和世界名。

### 3.7 完整示例

**示例 1：基础实体调度**

```java
import fun.bm.mili.api.Mili;
import fun.bm.mili.api.EntityTaskContext;
import fun.bm.mili.api.EntityOrphanedException;

// 绑定到实体，在当前 tick 执行
Mili.scheduler().forEntity(player).run(ctx -> {
    try {
        var entity = ctx.getEntity();
        entity.sendMessage("Hello from Mili scheduler!");
    } catch (EntityOrphanedException e) {
        // 玩家已离线或死亡，安全忽略
    }
});
```

**示例 2：跨区操作（awaitCrossRegion）**

```java
import fun.bm.mili.api.Mili;
import fun.bm.mili.api.EntityTaskContext;
import fun.bm.mili.api.EntityOrphanedException;

// 在目标位置获取方块状态，即使跨 region 也能安全挂起等待
Mili.scheduler().forEntity(player).run(ctx -> {
    try {
        Location target = new Location(world, 100, 64, 200);
        Block block = ctx.awaitCrossRegion(target, c -> {
            // 这个 lambda 在目标 region 的 tick 线程中执行
            return target.getBlock().getType();
        });
        player.sendMessage("Target block: " + block);
    } catch (EntityOrphanedException e) {
        // 玩家消失，操作被中断
    }
});
```

**示例 3：异步任务**

```java
// 执行阻塞 IO 操作而不卡 tick 线程
Mili.scheduler().runAsync(() -> {
    var data = database.queryPlayerStats(uuid);
    // 注意：runAsync 中无法操作 Bukkit API，需回到 region 线程
    Mili.scheduler().runAt(loc, ctx -> {
        player.sendMessage("Stats: " + data);
    });
});
```

---

## 4. Tick Regions API

LMili 允许插件查询 tick 区域的实时信息。此 API 用于性能监控、区域负载查询、动态调优等场景。

### 4.1 核心接口

| 接口 | 包路径 | 说明 |
|------|--------|------|
| `ThreadedRegionizer` | `fun.bm.mili.lmili.api` | 全局 tick 区域管理器，可获取所有 tick 区域或按坐标查询 |
| `ThreadedRegion` | `fun.bm.mili.lmili.api` | 单个 tick 区域，获取 ID、世界、dead section 等 |
| `TickRegionData` | `fun.bm.mili.lmili.api` | 区域运行时数据：世界、当前 tick 计数、区域统计 |
| `RegionStats` | `fun.bm.mili.lmili.api` | 区域统计：实体数、玩家数、区块数 |

### 4.2 使用方式

Tick Regions API 是 LMili 内部 NMS 代码的镜像，通常通过服务器内部的 NMS 实例获取。下面给出在插件中使用的推荐方式：

```java
import fun.bm.mili.lmili.api.ThreadedRegionizer;
import fun.bm.mili.lmili.api.ThreadedRegion;
import fun.bm.mili.lmili.api.RegionStats;
import fun.bm.mili.lmili.api.TickRegionData;

// 获取 ThreadedRegionizer 实例（在 lmili-server 内部实现中）
ThreadedRegionizer regionizer = MiliInternal.getThreadedRegionizer();

// 按坐标获取 tick 区域（同步）
ThreadedRegion region = regionizer.getAtSynchronized(location);

// 按坐标获取（无锁，读取最新可能值）
ThreadedRegion regionFast = regionizer.getAtUnSynchronized(location);

// 获取区域信息
if (region != null) {
    World world = region.getWorld();
    long id = region.getId();
    double deadPct = region.getDeadSectionPercent();

    TickRegionData data = region.getTickRegionData();
    RegionStats stats = data.getRegionStats();

    int entityCount = stats.getEntityCount();
    int playerCount = stats.getPlayerCount();
    int chunkCount = stats.getChunkCount();
    long tickCount = data.getCurrentTickCount();
}
```

> **注意**：`getAtSynchronized` 需要在区域 tick 线程上调用或加锁；`getAtUnSynchronized` 是无锁读取，可能拿到稍旧的数据但不会阻塞。

---

## 5. Bytebuf — 自定义数据包 API

LMili 提供了 Bytebuf API 供插件读写自定义数据包。它提供了一套简洁的 buffer 操作接口，类似于 Netty 的 ByteBuf，但独立于 NMS 的 FriendlyByteBuf，便于跨版本使用。

### 5.1 Bytebuf 接口

`org.leavesmc.leaves.bytebuf.Bytebuf` 提供以下能力：

- 静态工厂：`Bytebuf.buf(int size)`、`Bytebuf.buf()`（默认 128 字节）、`Bytebuf.of(byte[])`
- 基本类型读写：`writeByte/readByte`、`writeShort/readShort`、`writeInt/readInt`、`writeLong/readLong`
- 变长编码：`writeVarInt/readVarInt`、`writeVarLong/readVarLong`
- 浮点：`writeFloat/readFloat`、`writeDouble/readDouble`
- 布尔：`writeBoolean/readBoolean`
- UUID：`writeUUID/readUUID`
- 字符串：`writeUTFString/readUTFString`（UTF-8 字符串）、`writeComponentPlain/readComponentPlain`（纯文本组件）、`writeComponentJson/readComponentJson`（JSON 组件，返回 JsonElement）
- 物品：`writeItemStack/readItemStack`、`writeItemStackList/readItemStackList`
- 枚举：`writeEnum/readEnum`
- 索引操作：`readerIndex/writerIndex/resetReaderIndex/resetWriterIndex/skipBytes/clear`
- 内存管理：`retain/release`（引用计数）、`copy`（深拷贝）、`toArray`（导出 `byte[]`）

### 5.2 PacketType — 数据包类型标识

`PacketType` 定义了所有数据包类型的枚举，包含 `Clientbound`（服务端→客户端）和 `Serverbound`（客户端→服务端）两个嵌套枚举。每个数据包有 `id()` 和 `bound()` 属性。

### 5.3 PacketEvent / PacketInEvent / PacketOutEvent

插件可以监听自定义数据包的收发事件：

- `PacketInEvent` —— 服务端收到客户端数据包时触发（serverbound），可取消。
- `PacketOutEvent` —— 服务端发送给客户端数据包前触发（clientbound），可取消。
- 两事件都继承自 `PacketEvent`，包含：`getPacketType()`、`getAudience()`、`getBytebuf()`、`setBytebuf()`。
- `PacketAudience` 接口提供 `getPlayer()`、`getName()`、`getChannel()` 以及 `send(PacketType, Bytebuf)` 重发数据包。

### 5.4 示例：监听并修改数据包

```java
import org.leavesmc.leaves.bytebuf.*;
import org.leavesmc.leaves.event.bytebuf.PacketOutEvent;

@EventHandler
public void onPacketOut(PacketOutEvent event) {
    PacketType type = event.getPacketType();
    // 过滤目标数据包
    if (type == PacketType.Clientbound.SET_TITLE_TEXT) {
        Bytebuf buf = event.getBytebuf();
        // 读取/修改 buffer 内容
        String original = buf.readComponentPlain();
        // ...
    }
}
```

---

## 6. 事件 API

LMili 在 Folia/Paper 事件体系之上扩展了新的事件，用于补充 Folia 中缺失的实体传送、玩家重生等回调。

### 6.1 传送与门户事件

| 事件类 | 包路径 | 触发时机 |
|--------|--------|----------|
| `EntityTeleportAsyncEvent` | `fun.bm.mili.lmili.api.entity` | 调用 teleportAsync 时触发（含 NMS 内部调用） |
| `PreEntityPortalEvent` | `fun.bm.mili.lmili.api.entity` | 实体门户传送前（可取消），附带实体/传送门坐标/目标世界 |
| `PostEntityPortalEvent` | `fun.bm.mili.lmili.api.entity` | 实体门户传送完成后触发 |
| `PortalLocateEvent` | `fun.bm.mili.lmili.api.portal` | 传送门正在定位目标位置时触发，可修改 destination |
| `EndPlatformCreateEvent` | `fun.bm.mili.lmili.api.portal` | 末地平台创建时触发（可取消），注意跨区位置不会触发 |

### 6.2 玩家事件

- `PostPlayerRespawnEvent` —— 玩家重生流程完成后触发，获取重生后的 Player 对象。
- `PlayerOperationLimitEvent` —— 玩家操作被服务端限制（如防爆/防放置），包含 `Operation.MINE` / `Operation.PLACE` 和受操作的 Block。

### 6.3 事件示例

```java
@EventHandler
public void onPortal(PreEntityPortalEvent event) {
    Entity e = event.getEntity();
    Location portal = event.getPortalPos();
    World dest = event.getDestination();
    // 阻止非玩家进入末地
    if (dest.getEnvironment() == World.Environment.THE_END
            && !(e instanceof Player)) {
        event.setCancelled(true);
    }
}
```

---

## 7. Photographer API — 录像与回放

LMili 提供了 ReplayMod 兼容的 Photographer 实体，插件可在服务端创建虚拟摄影师并录制游戏过程，生成 ReplayMod 回放文件。

### 7.1 核心接口

- `org.leavesmc.leaves.entity.photographer.Photographer` —— 摄影师实体，继承 Player 接口，额外提供 `getId()`、`setRecordFile(File)`、`stopRecording()`、`pauseRecording()`、`resumeRecording()`、`setFollowPlayer(Player)`。
- `org.leavesmc.leaves.entity.photographer.PhotographerManager` —— 摄影师管理器，提供创建/查找/移除摄影师。
- `org.leavesmc.leaves.replay.BukkitRecorderOption` —— 录像选项，包含 `serverName`、`forceWeather`、`forceDayTime`、`ignoreChat` 等。

### 7.2 使用示例

```java
import org.leavesmc.leaves.entity.photographer.*;
import org.leavesmc.leaves.replay.BukkitRecorderOption;

// 获取 PhotographerManager（通过 Bukkit.getServicesManager() 或 NMS 内部获取）
PhotographerManager manager = ...;

// 创建摄影师
BukkitRecorderOption option = new BukkitRecorderOption();
option.serverName = "MyServer";
Photographer photographer = manager.createPhotographer("rec-001", location, option);

// 设置录制文件并开始
photographer.setRecordFile(new File("replays/replay-001.mcpr"));

// 跟随玩家
photographer.setFollowPlayer(targetPlayer);

// 停止录制
photographer.stopRecording(true, true); // async=true, save=true

// 清理
manager.removePhotographer("rec-001");
```

---

## 8. 服务器运行时特性与插件开发注意事项

### 8.1 RegionTickPool — 独立 tick 调度增强

LMili 允许通过 `lmili_config.toml` 中的 `experiment.region_tick_pool.enabled` 启用 RegionTickPool。该功能将 Folia 的"每区域独占一个 tick 线程"改为"共享 worker 池 + 优先级调度"，显著降低大量空闲 region 时的 CPU 占用。

启用 RegionTickPool 后，Mili 调度器 API（Scheduler / EntityScheduler）才会生效。如果未启用或使用 no-op 调度器，所有 `Mili.scheduler()` 调用都是空操作。

RegionTickPool 启用后会禁用 RegionBalancer —— 互不共存。

### 8.2 异常安全性差异

LMili 将调度器线程和线程池中所有 `catch(Exception)` 改为 `catch(Throwable)`，这意味着：

- `OutOfMemoryError` 不再导致 tick 线程静默死亡 —— 错误会被捕获并日志输出，线程继续处理下一个任务。
- `StackOverflowError` 同理 —— 插件递归溢出不会拖垮整个区域 tick。
- 插件开发建议：仍应避免上述错误，但意外发生时 LMili 提供更优的容错。

### 8.3 全局任务 ID 注册（RegionTaskIdRegistry）

LMili 内部所有跨区任务都通过 RegionTaskIdRegistry 分配全局唯一 UUID，防止 Folia 中已知的任务 ID 碰撞导致崩溃的问题。此机制对插件透明，插件无需关心，但了解其存在有助于理解某些调试日志。

### 8.4 虚拟线程（Virtual Thread）行为

LMili 调度器在 JDK 25 + RegionTickPool 模式下使用 virtual thread 作为执行 backend。对插件开发者而言：

- virtual thread 支持 synchronized 块而不 carrier pinning (JDK 24+ JEP 491)，无需为避开 synchronized 而使用 ReentrantLock。
- 长时间阻塞操作（>1ms 的 IO）应使用 `Mili.scheduler().runAsync()` 而非直接在实体调度任务中阻塞，以避免影响同一 carrier 上的其他任务。
- `EntityTaskContext.awaitCrossRegion()` 在跨区域内部使用 virtual thread 的 join 挂起 —— 不会阻塞平台线程。

### 8.5 配置系统

LMili 使用纯 Java 的 night-config TOML 配置文件系统（文件：`lmili_config.toml`）。配置按类别分为 function、experiment、optimizations、fixes、misc 五大模块，每个模块由多个 `ConfigModule` 实现类组成，通过反射自动加载。

插件开发者可以通过 `lmiconfig` 命令在运行时查看/修改配置，或通过代码访问 `ConfigManager`。详细配置项参考 `lmili_config.toml` 文件中的注释。

### 8.6 MiliPlugin 工具类

`MiliPlugin` 工具类提供了插件元数据检查功能：

- `MiliPlugin.of(plugin)` —— 获取插件对应的 MiliPlugin 助手。
- `MiliPlugin.isMiliSupported(plugin)` —— 检查插件是否在 plugin.yml 中声明了 `mili-supported: true`（使用反射读取原始 YAML 映射，也兼容 `getProvides()` 中的 mili/mili-supported）。
- `miliPlugin.isMiliInUse()` —— 检查此插件是否已经通过 `Mili.scheduler()` 提交过任务（运行时追踪）。
- `MiliPlugin.invalidateCache(name)` —— 清除缓存（用于插件重载场景）。

---

## 9. 关键配置项速查

以下是影响插件运行的关键配置项（`lmili_config.toml`）：

| 配置键 | 默认值 | 热重载 | 说明 |
|--------|--------|--------|------|
| `experiment.region_tick_pool.enabled` | false | NO | 启用 RegionTickPool 调度器增强 |
| `experiment.region_tick_pool.use_virtual_threads` | true | YES | 使用 virtual thread 作为 worker |
| `experiment.region_tick_pool.worker_count` | CPU-1 | NO | worker 线程总数 |
| `experiment.cross_region_helper.enabled` | true | YES | 启用跨区事件队列（实体伤害/方块通知等） |
| `optimizations.villager.lobotomize.enabled` | false | YES | 村民发呆优化（减少路径计算） |
| `optimizations.entity_data.dirty_tracking` | true | YES | 实体数据脏追踪（按需发送数据包） |
| `function.replay_api.enabled` | true | YES | Photographer 录像 API 开关 |
| `function.protocol.bytebuf_protocol` | true | YES | Bytebuf 自定义数据包协议开关 |
| `misc.bstats.enabled` | true | YES | bStats 匿名统计 |

---

## 10. 插件开发最佳实践

1. 始终检查 `Mili.isSupported()` —— 在调用 `Mili.scheduler()` 前检查，提供降级方案。
2. 捕获 `EntityOrphanedException` —— 所有使用 `ctx.getEntity()` / `ctx.withEntity()` / `ctx.awaitCrossRegion()` 的地方都必须处理此受检异常。
3. 避免在实体调度任务中长时间阻塞 —— 超过 1ms 的阻塞操作（数据库、HTTP、文件 IO）应使用 `Mili.scheduler().runAsync()`。
4. 跨区操作使用 `awaitCrossRegion` —— 不要手动在不同 region 间切换任务，让 LMili 调度器处理。
5. 不要在异步任务中直接调用 Bukkit API —— `runAsync` 中的代码不在任何 region tick 线程上，需通过 `runAt` 回到正确的线程。
6. 声明 `mili-supported: true` —— 在 plugin.yml 中声明，便于 LMili 服务端识别和统计。
7. 使用 `MiliPlugin.isMiliInUse()` 追踪插件使用情况 —— 用于调试和监控。
8. 配置热重载 —— 大部分配置支持运行时修改（标记为 YES 的），但 worker-count 等基础设置需要重启。
9. 避免在 virtual thread 中使用 `Thread.sleep` —— 虽然不会 pin carrier，但会浪费调度资源；应使用 `runDelayed` 替代。
10. 跨维度传送使用 teleportAsync —— LMili 会触发 EntityTeleportAsyncEvent，比同步传送更安全。

---

## 附录 A：包名参考与项目结构

### A.1 完整包名参考

| 包名 | 说明 |
|------|------|
| `fun.bm.mili.api` | Mili 公共 API（调度器、上下文、异常） |
| `fun.bm.mili.lmili.api` | Mili 内部 API（区域、统计、事件） |
| `fun.bm.mili.lmili.api.entity` | 实体事件（传送、门户） |
| `fun.bm.mili.lmili.api.portal` | 传送门事件（平台创建、定位） |
| `fun.bm.mili.lmili.api.entity.player` | 玩家事件（重生） |
| `org.leavesmc.leaves.bytebuf` | Bytebuf 数据包 API |
| `org.leavesmc.leaves.event.bytebuf` | 数据包收发事件 |
| `org.leavesmc.leaves.event.player` | 玩家事件（操作限制） |
| `org.leavesmc.leaves.entity.photographer` | Photographer 录像 API |
| `org.leavesmc.leaves.replay` | 录像选项 |
| `fun.bm.mili.config.modules` | 配置模块（lmili_config.toml 对应） |
| `fun.bm.mili.lmili.config` | 配置管理 |
| `fun.bm.mili.lmili.thread.regiontick` | 调度器实现 |
| `fun.bm.mili.lmili.data` | 区块存储格式（O_LINEAR 等） |
| `fun.bm.mili.lmili.commands` | 服务端命令 |

### A.2 项目目录结构

```
Mili/
├── lmili-api/                  # Mili API 模块（插件开发者编译时依赖）
│   ├── src/main/java/
│   │   ├── fun/bm/mili/api/         # 公共 API
│   │   ├── fun/bm/mili/lmili/api/   # 内部 API（区域、事件）
│   │   ├── org/leavesmc/leaves/     # Bytebuf、Photographer、事件
│   │   └── gg/pufferfish/           # SIMD 检测
│   └── build.gradle.kts              # 发布坐标 io.github.xucy10:lmili-api:26.2-R0.1
├── lmili-server/               # Mili 服务端核心（可运行产物）
│   ├── minecraft-patches/     # 97 个特征补丁（features/）
│   ├── paper-patches/         # Paper API/Server 层补丁
│   └── src/main/java/fun/bm/mili/
│       ├── bridge/            # 区块-区域桥接
│       ├── chunk/             # 区块系统
│       ├── command/           # 命令系统
│       ├── config/            # 配置模块（TOML，纯 Java 实现）
│       ├── metrics/           # bStats 统计
│       ├── portal/            # 传送门管理
│       ├── utils/             # 工具类（区域调度、网络优化等）
│       ├── villager/          # 村民优化器
│       └── lmili/             # 核心实现子树
│           ├── thread/regiontick/   # RegionTickPool / 调度器
│           ├── config/             # ConfigManager
│           ├── data/               # O_LINEAR 区块存储
│           └── commands/           # /lmiconfig、/lmibar 等
├── paper-server/              # Paper 服务器（补丁应用目标）
├── paper-api/                 # Paper API（补丁应用目标）
├── leaves-api/                # Paper API 包装层
├── folia-server/              # Folia 子模块（上游）
├── docs/                      # 文档
├── build.gradle.kts           # 根构建脚本
└── gradle.properties          # group=io.github.xucy10
```

---

## 附录 B：构建与运行

### B.1 服务端构建

```bash
# 1. 克隆仓库
git clone https://github.com/MiliMCL/LMili
cd LMili

# 2. Windows 需启用长路径
git config --global core.longpaths true

# 3. 首次构建：应用补丁
./gradlew applyAllPatches --no-configuration-cache --no-build-cache

# 4. 注入 Kotlin 支持
python scripts/inject_kotlin.py

# 5. 构建 Paperclip JAR
./gradlew :lmili-server:createPaperclipJar

# 产物位置
# lmili-server/build/libs/lmili-26.2-paperclip.jar
```

### B.2 编译验证（Windows）

```powershell
cd "E:\Program Files\Tencent\AndrowsData\Mili"
$env:JAVA_HOME = "C:\Users\Administrator\Downloads\jdk-25_windows-x64_bin\jdk-25.0.4"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :lmili-server:compileJava --no-daemon
```

### B.3 API 独立编译验证

```powershell
.\gradlew.bat :lmili-api:compileJava --no-daemon
```

> **许可证**：GPL-3.0
