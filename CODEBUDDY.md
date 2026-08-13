# CODEBUDDY.md

This file provides guidance to CodeBuddy / AI code assistants when working with code in this repository.

## 项目概述

**Mili** 是直接基于 [Folia](https://github.com/PaperMC/Folia) 的 Minecraft 26.2服务端核心，使用纯 Java 25 构建。目标是在 Folia 并发调度环境下提供更稳定、可配置的服务器运行时。项目不包含任何 Rust 原生模块或生电/协议修改。

**版本**：`26.2-R0.1-SNAPSHOT`
**构建工具**：Gradle 9.4.1（Kotlin DSL）+ Hyacinthusweight 补丁系统（97 个 feature 补丁）
**上游**：Folia `57f643f`（`foliaRef` in `gradle.properties`）
**包名**：`fun.bm.mili`（lmili 子包 `fun.bm.mili.lmili`）

> Mili 原为 Lophine/Luminol 衍生分支，现已直接基于 Folia。原 Rust 模块已移除，配置系统已替换为纯 Java night-config 实现。原 `me.earthme.luminol` 包名已重命名为 `fun.bm.mili.lmili`。

---

## 常用命令

### 构建

```bash
# 首次构建（必须）
./gradlew applyAllPatches --no-configuration-cache --no-build-cache

# 构建 Paperclip JAR
./gradlew :mili-server:createPaperclipJar
```

### 编译验证

```bash
# Java 编译
./gradlew :mili-server:compileJava
```

### 补丁管理

```bash
# 应用全部补丁
./gradlew applyAllPatches

# 修改源文件后重建补丁
./gradlew :mili-server:rebuildAllServerPatches
```

---

## 代码架构

### 模块结构

```
Mili/
├── mili-api/          # 对外公开 API（Photographer、Bytebuf、事件）
├── mili-server/       # 服务器核心
│   ├── minecraft-patches/features/   # 97 个特征补丁文件
│   └── src/main/java/fun/bm/mili/   # Java 源码
├── lmili-api/         # LMili 附加 API（原 luminol-api，包 fun.bm.mili.lmili）
├── folia-server/      # Folia 子模块（上游，不修改）
├── folia-api/         # Folia API（不修改）
├── paper-server/      # Paper 服务器（补丁应用目标）
├── paper-api/         # Paper API（补丁应用目标）
└── docs/              # 文档
```

### Java 包结构（`fun.bm.mili`）

| 包 | 说明 |
|---|------|
| `bridge` | 区块-区域桥接（ChunkRegionBridge） |
| `chunk` | 区块系统（生命周期管理、异步处理、热度追踪、视距优化） |
| `command` | 命令系统（`/counter`、`/mili` 等） |
| `config` | 配置模块（function/experiment/optimizations/fixes/misc，纯 Java TOML） |
| `metrics` | bStats 统计 |
| `portal` | 传送门管理（配对、原子写入、NPE 防护） |
| `utils` | 工具类（区域调度、网络优化、内存管理、村民、并发数据结构等） |
| `villager` | 村民优化器 |
| `lithium` | Leaves Lithium 移植（漏斗、方块实体、实体追踪优化） |
| `threadedregions` | Folia 区域调度相关辅助类 |
| `lmili` | Luminol 遗留功能（包名仍保留历史引用至 lmili 子包） |

### 继承链

```
Minecraft（原版）
  └── Paper（服务端框架）
        └── Folia（区域多线程调度）
              └── Mili（本项目）
```

---

## 关键配置文件

| 文件 | 说明 |
|------|------|
| `gradle.properties` | 项目版本 `26.2-R0.1-SNAPSHOT`、MC 版本 `26.2`、`foliaRef=57f643f`、`weightVersion=2.0.15`、`clipVersion=3.0.18` |
| `mili-server/build.gradle.kts` | 服务器构建核心（Java toolchain 25、lithium 依赖等） |
| `mili_config.toml` | 运行时主配置（TOML 格式） |

---

## 修复标记约定

所有代码修复使用 `// Mili start - fix:` / `// Mili end` 注释标记（如果有 Rust 侧同理，但本项目已无 Rust 代码）。

---

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 25+ | 构建工具链 |
| Git | 2.x | Windows 需启用长路径支持 |

构建时建议分配 >= 4GB 内存。

---

## 补丁工作流

1. 修改 `mili-server/src/minecraft/java/` 下的生成源码
2. 提交：`git commit -m "描述"`
3. 重建补丁：`./gradlew :mili-server:rebuildAllServerPatches`
4. 提交补丁文件（`minecraft-patches/features/*.patch`）

**重要**：直接编辑 `mili-server/src/minecraft/java/` 下的文件会被下次 `applyAllPatches` 覆盖。如果需要让修改持久化，必须通过补丁文件。

---

## 包名映射

| 旧包名（已弃用） | 新包名 |
|-----------------|--------|
| `me.earthme.luminol` | `fun.bm.mili.lmili` |
| `me.earthme.luminol.api` | `fun.bm.mili.lmili.api` |
| `me.earthme.luminol.config` | `fun.bm.mili.lmili.config` |
| `me.earthme.luminol.utils` | `fun.bm.mili.lmili.utils` |

注意：`mili-server/src/main/java/` 下的源码使用 `fun.bm.mili.*` 包名（不带 `lmili` 子包），而 `lmili-api/` 和 `mili-server/src/minecraft/java/` 中的部分文件使用 `fun.bm.mili.lmili.*`。
