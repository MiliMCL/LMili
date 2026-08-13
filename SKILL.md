---
title: Mili 项目代码审查与 bug 修复工作流
scope: workspace
owner: "Mili"
summary: |
  面向 Mili Minecraft 服务端核心的系统性代码审查工作流。覆盖 Java 源码并发安全、NPE、整数溢出、时间尺度混淆、线程静默死亡等 bug 排查。
tags: [code-review, bug-fix, concurrency, minecraft]
---

# Mili 代码审查与 bug 修复工作流

## 目标

对 Mili 项目（基于 Folia 的 Minecraft 26.2 服务端核心）进行系统性 bug 排查与修复，覆盖 Java 源码中的并发安全、NPE、整数溢出、时间尺度混淆、线程静默死亡等问题。

## 适用场景

- 全项目代码审查（Java）
- 线程安全问题排查（竞态、死锁、线程静默死亡）
- 区域调度系统稳定性优化
- 网络连接稳定性优化

## 何时触发

- 大规模代码变更后需要全面审查
- 发现服务器线程静默死亡或 OOM 崩溃
- 区域调度系统出现任务 ID 碰撞或任务丢失
- 网络连接不稳定需要优化

## 前提条件

- JDK 25 已安装并配置 `JAVA_HOME`
- 项目已 `applyAllPatches` 并可成功编译
- 构建环境：`./gradlew :mili-server:compileJava`

## 工作流步骤

### 1. 环境准备

```bash
export JAVA_HOME="C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2"
export PATH="$JAVA_HOME/bin:$PATH"
cd "E:/Program Files/Tencent/AndrowsData/Mili"
./gradlew :mili-server:compileJava
```

### 2. Java 代码审查

按严重程度分类排查：

**致命级 — 线程静默死亡**：
- 搜索 `catch(Exception)` 模式，在调度器/线程上下文中改为 `catch(Throwable)`
- 涉及 `ScheduledExecutorService`、`CompletableFuture`、线程池的所有 catch 块

**致命级 — 数据损坏**：
- 检查浮点位操作（如 `SCORE_MASK` 破坏 double 位布局）
- 检查 writeIndex 溢出（`Math.floorMod` 替代 `%`）
- 检查时间尺度混淆（游戏时间 vs 系统时间）

**资源泄漏级**：
- 检查 Map/Queue 无限增长（添加 TTL 清理或上限）
- 检查 `Deflater`/`RandomAccessFile`/`FileChannel` 未在 finally 中关闭
- 检查 UUID 注册后未注销

**并发竞态级**：
- 检查 `volatile boolean` 初始化标志（改为 `AtomicBoolean.compareAndSet`）
- 检查异步遍历 Bukkit 集合（先快照为 ArrayList）
- 检查 `getLocation()` 多次调用竞态（调用一次存入局部变量）
- 检查 `.equals()` 模式 NPE（改为 `Objects.equals()`）

### 3. 验证

```bash
# Java 编译
./gradlew :mili-server:compileJava
```

### 4. 修复标记

所有修复使用 `// Mili start - fix:` / `// Mili end` 注释标记。

## 质量门

- Java `BUILD SUCCESSFUL`

## 异常处理

- 编译错误：检查 JDK 版本是否为 25（不是 21）

## 维护记录

- 版本 0.3 — 移除 Rust/JNI 相关内容（项目已纯净化为 Folia + API + 稳定性修复）
- 版本 0.2 — 适配 Mili 26.2 分支，JDK 25
