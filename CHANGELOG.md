# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [26.2-R0.1] - 2026-08-20

### 第一个正式版本发布 🎉

Mili 是基于 Paper → Folia fork 链的 Minecraft 服务端核心，专注于在 Folia 区域多线程调度模型之上提供稳定性修复、bug 修复、通用性能优化以及更多 API。

### 核心架构

- **RegionTickPool 调度增强**: 将 Folia "每区域独占一线程" 的模型替换为共享 worker 池 + 优先级调度，显著减少大量空闲 region 时的 CPU 占用
  - Virtual Thread 后端：基于 JDK 25 虚拟线程，以同步风格编写异步逻辑
  - DAG 并行调度：非阻塞回调驱动，稀疏冲突图替代 O(n²) 矩阵
  - Work-Stealing 负载均衡：全局工作窃取，自动平衡各 region 负载
  - 阻塞操作隔离：专用平台线程池 + 信号量限流，防止 carrier pinning
  - 对象池复用：ThreadLocal 池实现热路径零分配
  - 渐进式迁移：通过桥接器灰度切换新旧调度路径

### Folia 稳定性修复

- **Region Balancer**: 共享线程池 + 优先级队列替代 Folia 每区域独占线程，动态负载均衡
- **Region Load Monitor**: 无锁滑动窗口统计区域 tick 耗时
- **Adaptive TPS Manager**: 根据实时负载动态调整 TPS
- **Cross-Region Helper**: 类型化跨区事件队列（实体伤害、方块通知等）
- **RegionTaskIdRegistry**: 全局 UUID 注册中心，防止跨区块任务 ID 碰撞导致崩溃
- **全局实体计数器**: 按区域聚合 mob 数量，避免 O(entities) 扫描
- **线程安全加固**: 全局 `catch(Exception)` → `catch(Throwable)` 修复，防止 OOM/StackOverflow 等 Error 导致调度器线程静默死亡

### Bug 修复

- 玩家重生位置修正
- 实体传送（跨区/末影珍珠/维度切换）一系列竞态修复
- 区域外寻路/拴绳/目标选择防护
- POI 更新延迟、区块重载检测、龙部件同步等修复
- RegionizedTaskQueue 并发引用修正
- RegionizedWorldData 空连接 NPE 修复
- 已移除实体仍添加效果问题修复
- /save-all 区域安全化

### 通用性能优化

来自 Gale / Lithium / Pufferfish / SparklyPaper / Kaiiju / Petal / Krypton / Leaves 等上游的通用优化：

- 噪声生成、AI 属性集合、大脑映射、准则映射等数据结构优化
- 实体移动零位移跳过、可变实体唤醒时长、canSee 检查优化
- 区块加载查找削减、投射物区块加载削减、寻路区域限制
- 网络与协议层优化、区块增量压缩
- 村民 lobotomize（发呆）优化、传感器工作削减
- 异步寻路、动态视距、实体数据脏追踪、CPU 亲和性、SIMD 优化

### API 扩展

- **Mili 调度器 API**: 挂起式调度器（Mili、Scheduler、EntityScheduler、EntityTaskContext），支持 awaitCrossRegion 跨区挂起
- **Tick Regions API**: 查询/操作 tick 区域的 API（ThreadedRegionizer、ThreadedRegion、TickRegionData、RegionStats）
- **ReplayMod 摄影师**: 创建 ReplayMod 摄影师实体进行录像
- **Bytebuf API**: 面向插件的自定义数据包读写 API
- **数据包事件**: PacketInEvent / PacketOutEvent 监听数据包收发
- **实体传送异步事件**: EntityTeleportAsyncEvent、PreEntityPortalEvent、PostEntityPortalEvent
- **传送门事件**: PortalLocateEvent、EndPlatformCreateEvent
- **玩家事件**: PostPlayerRespawnEvent、PlayerOperationLimitEvent
- **Waypoint API**: 实体路径点追踪与恢复 API

### 配置系统

- TOML 配置文件（纯 Java night-config 解析实现）
- 50+ 配置模块，分为 function / experiment / optimizations / fixes / misc 五大类别
- 运行时可通过 /lmiconfig 命令动态修改配置

### Maven 发布

API 坐标：`io.github.xucy10:lmili-api:26.2-R0.1`

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.xucy10:lmili-api:26.2-R0.1")
}
```

### 环境要求

- JDK 25+
- Git 2.x（Windows 需启用长路径支持）

### 构建

```bash
git clone https://github.com/MiliMCL/LMili.git
cd LMili
./gradlew applyAllPatches --no-configuration-cache --no-build-cache
./gradlew :lmili-server:createPaperclipJar
```

[26.2-R0.1]: https://github.com/MiliMCL/LMili/releases/tag/26.2-R0.1
