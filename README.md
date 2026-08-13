<p align="center">
  <img src="public/image/Mili/mili-logo.png" alt="Mili Logo" width="600">
</p>

<h1 align="center">Mili（米粒）</h1>

<p align="center">
  <strong>基于 Folia 的高性能 Minecraft 服务端核心：更纯粹的区域多线程体验，更多 API，更稳定</strong>
</p>

<p align="center">
  <a href="./README.md">中文</a> | <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.2-green" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/JDK-25+-orange" alt="JDK 25+">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0">
</p>

---

## 项目简介

Mili 是一个基于 **Paper → Folia** fork 链的 Minecraft 服务端核心。项目目标是成为一个**纯粹的 Folia**：不引入生电/红石机制修改与客户端协议魔改，专注于在 Folia 区域多线程调度模型之上提供 **更多 API、稳定性修复与 bug 修复**，以及通用的性能优化。

### 继承链

```
Minecraft（原版）
  └── Paper（服务端框架）
        └── Folia（区域多线程调度）
              └── Mili（本项目）
```

> Mili 现为直接基于 Folia 的服务端，包名 `fun.bm.mili.lmili`。

---

## 核心特性

### Folia 稳定性修复

- **Region Balancer**：共享线程池 + 优先级队列替代 Folia 每区域独占线程，动态负载均衡
- **Region Load Monitor**：无锁滑动窗口统计区域 tick 耗时
- **Adaptive TPS Manager**：根据实时负载动态调整 TPS
- **Cross-Region Helper**：类型化跨区事件队列（实体伤害、方块通知等）
- **RegionTaskIdRegistry**：全局 UUID 注册中心，防止跨区块任务 ID 碰撞导致崩溃
- **全局实体计数器**：按区域聚合 mob 数量，避免 O(entities) 扫描
- **线程安全加固**：全局 `catch(Exception)` → `catch(Throwable)` 修复，防止 OOM/StackOverflow 等 Error 导致调度器线程静默死亡

### Bug 修复

大量针对 Folia 区域线程模型的修复，包括（但不限于）：

- 玩家重生位置修正、实体传送（跨区/末影珍珠/维度切换）一系列竞态修复
- 区域外寻路/拴绳/目标选择防护
- POI 更新延迟、区块重载检测、龙部件同步等修复
- RegionizedTaskQueue 并发引用修正

### 通用性能优化

来自 Gale / Lithium / Pufferfish / SparklyPaper / Kaiiju / Petal / Krypton / Leaves 等上游的通用优化（不涉及生电行为修改），例如：

- 噪声生成、AI 属性集合、大脑映射、准则映射等数据结构优化
- 实体移动零位移跳过、可变实体唤醒时长、canSee 检查优化
- 区块加载查找削减、投射物区块加载削减、寻路区域限制
- 网络与协议层优化、区块增量压缩
- 村民 lobotomize（发呆）优化、传感器工作削减

### API 扩展

在 Folia/Paper API 之上提供更多能力：

| API | 说明 |
|------|------|
| **Tick Regions API** | 查询/操作 tick 区域的 API（`ThreadedRegion`、`RegionStats` 等） |
| **ReplayMod 摄影师** | 创建 ReplayMod 摄影师实体进行录像，`Photographer` / `PhotographerManager` API |
| **Bytebuf API** | 面向插件的自定义数据包读写 API |
| **实体传送异步事件** | `EntityTeleportAsyncEvent`、`PreEntityPortalEvent`、`PostEntityPortalEvent` 等 |
| **Waypoint API** | 实体路径点追踪与恢复 API |
| **ThreadedRegionizer** | 获取全局 `ThreadedRegionizer` 实例的 API |

---

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 25+ | 构建工具链（Mili 26.2 分支需要 Java 25，不是 JDK 21） |
| Git | 2.x | 需启用长路径支持（Windows） |

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/xucy10/Mili.git
cd Mili

# 2. Windows 需启用长路径
git config --global core.longpaths true

# 3. 应用补丁（首次构建必须执行）
./gradlew applyAllPatches --no-configuration-cache --no-build-cache

# 4. 注入 Kotlin 支持
python scripts/inject_kotlin.py

# 5. 构建 Paperclip JAR
./gradlew :mili-server:createPaperclipJar
```

构建产物位于 `mili-server/build/libs/`：
- `mili-26.2-paperclip.jar` — 可直接运行的 Paperclip JAR

---

## API 使用

### Gradle

```kotlin
repositories {
    maven {
        url = "https://repo.menthamc.org/repository/maven-public/"
    }
}

dependencies {
    compileOnly("fun.bm.mili:mili-api:26.2-R0.1-SNAPSHOT")
}
```

### Maven

```xml
<repositories>
  <repository>
    <id>menthamc</id>
    <url>https://repo.menthamc.org/repository/maven-public/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>fun.bm.mili</groupId>
    <artifactId>mili-api</artifactId>
    <version>26.2-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

---

## 项目结构

```
Mili/
├── mili-api/                  # Mili API 模块
│   └── src/main/java/         #   事件 API、Photographer、Bytebuf
├── mili-server/               # Mili 服务端核心
│   ├── minecraft-patches/     #   97 个特征补丁（features/）
│   ├── paper-patches/         #   Paper API/Server 层补丁
│   └── src/main/
│       └── java/fun/bm/mili/  #   Java 源码
│           ├── bridge/        #     区块-区域桥接
│           ├── chunk/         #     区块系统
│           ├── command/       #     命令系统
│           ├── config/        #     配置模块（TOML，纯 Java 实现）
│           ├── metrics/       #     bStats 统计
│           ├── portal/        #     传送门管理
│           ├── utils/         #     工具类（区域调度、网络优化、内存管理等）
│           └── villager/      #     村民优化器
├── lmili-api/                 # LMili 附加 API 源（包名 fun.bm.mili.lmili）
├── folia-server/              # Folia 子模块（上游，不直接修改）
├── paper-server/              # Paper 服务器（补丁应用目标）
├── paper-api/                 # Paper API（补丁应用目标）
├── docs/                      # 文档
├── build.gradle.kts           # 根构建脚本
└── gradle.properties          # 版本与上游 ref 配置
```

---

## 配置系统

Mili 提供 TOML 配置文件（纯 Java 解析实现）：

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `mili_config.toml` | `fun.bm.mili.config.modules` | 主配置，涵盖功能开关、实验功能、修复与优化开关 |

配置分类：

| 类别 | 说明 | 代表模块 |
|------|------|----------|
| `function` | 游戏机制与实用功能 | `LanguageConfig`、`TpsBarConfig`、`RegionBarConfig` |
| `experiment` | 实验性性能/并发功能 | `RegionBalancerConfig`、`CrossDimensionTeleportQueueConfig` |
| `optimizations` | 性能优化 | `NetworkOptimizerConfig`、`MmapRegionStorageConfig`、`VillagerOptimizerConfig` |
| `fixes` | 崩溃/行为修复 | `PortalLinkFixConfig`、`CollisionBehaviorConfig` |
| `misc` | 杂项 | `AutoUpdateConfig`、`BStatsConfig`、`ServerModNameConfig` |

---

## 补丁工作流

Mili 使用 **Hyacinthusweight**（基于 paperweight）补丁系统管理 feature 补丁：

1. 在 `mili-server/src/minecraft/java/` 中修改代码
2. 提交变更：`git commit -m "描述"`
3. 重建补丁：`./gradlew :mili-server:rebuildAllServerPatches`
4. 提交补丁文件并推送

修改 `mili-server/src/minecraft/java/` 下的生成文件会被 `applyAllPatches` 覆盖，必须通过 `minecraft-patches/features/` 下的补丁文件修改。

详细流程见 [贡献指南](docs/CONTRIBUTING.md)。

---

## 贡献

欢迎 Pull Requests 与 Issue！请先阅读：

- [贡献指南（中文）](docs/CONTRIBUTING.md) | [Contributing (EN)](docs/CONTRIBUTING_EN.md)
- 报告问题时请提供完整日志、环境信息与复现步骤

---

## 相关链接

| 项目 | 链接 |
|------|------|
| Folia（直接上游） | https://github.com/PaperMC/Folia |
| Paper | https://github.com/PaperMC/Paper |
| Luminol（大量代码移植出处） | https://github.com/LuminolMC/Luminol 已删库 |

---

## 社区

<!-- [Discord](https://discord.com/invite/BSa67dbvVf)  -->

QQ 群：（待添加）

## 感谢

感谢所有贡献者与赞助方对项目的持续支持。若项目对您有帮助，请在 GitHub 上给我们一个 star

## 许可证

本项目遵循 GPL-3.0 许可证。
