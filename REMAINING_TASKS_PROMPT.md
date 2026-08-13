# Mili 纯净化 Folia 重构 — 剩余任务提示词

> 将此文件完整粘贴给新 AI 会话作为上下文。

---

## 项目背景

项目路径：`E:\Program Files\Tencent\AndrowsData\Mili`
这是一个基于 Folia 的 Minecraft 服务端项目（使用 Hyacinthusweight/Paperweight 补丁系统）。
目标：移除所有生电特性、协议层、Rust 模块、假玩家系统，将 Luminol 品牌重命名为 LMili，转型为"纯 Folia + 更多 API + 稳定性修复"。

**Shell 注意**：用户环境为 PowerShell，不支持 `&&`，请用 `;` 分隔多条命令。

---

## 已完成的工作

### 1. 删除生电/协议/假玩家补丁文件
- 已删除 28 个补丁文件（minecraft-patches + paper-patches + api 层）
- 删除的补丁包括：红石脏追踪、技术MC优化、结构投影、Carpet协议、Leaves协议、假玩家API等

### 2. 删除 Rust 模块
- 已删除 `mili-rust/` 目录
- 已从 `settings.gradle.kts` 移除 `include("mili-rust")`
- 已从 `mili-server/build.gradle.kts` 移除 Rust JNI 依赖
- 已用纯 Java night-config 实现替代 Rust JNI 的 `TomlConfigData`（位于 `mili-server/src/main/java/fun/bm/mili/config/TomlConfigData.java`）
- 已从 minecraft 源码中移除实体剔除（EntityCull）相关代码

### 3. Minecraft 补丁已完全应用
- `mili-server/src/minecraft/java`：97 个补丁 = 97 个提交，全部应用成功
- `file` tag 在基线，HEAD 在 `60e4891 Fix compilation for MC 26.2 API migration`
- 6 个补丁经过手动适配（0070、0089、0102、0112、0118、0119），已通过脚本重新生成补丁文件

### 4. 包名与品牌重命名（部分完成）
- `mili-server/src/main/java` 中的 Java 源码：`me.earthme.luminol` → `fun.bm.mili.lmili` ✅ 已完成
- `luminol-api` 目录 → `lmili-api` ✅ 已完成
- `mili-server/src/main/java` 中无 `me.earthme.luminol` 残留 ✅
- 136 个文件已使用 `fun.bm.mili.lmili` 包引用 ✅
- 构建脚本已更新 ✅

### 5. Paper-server 补丁部分应用
- 已提交 3 个提交（领先 upstream 3 个提交）：
  - `87153295b (tag: file) mili paperServer File Patches`
  - `d2c22052e Leaves: Replay Mod API`（手动适配）
  - `c6f33a7bd Leaves: Bytebuf API`（手动适配）
- Paper-api：干净，无变更

### 6. 文档与 CI 清理（部分完成）
- README.md / README_EN.md 已重写
- SKILL.md 已更新
- build.yml 已移除 Rust 构建链
- carpet-compat-status.md 已删除

---

## ⚠️ 关键遗留问题

### 问题 A：minecraft 源码树中大量 `me.earthme.luminol` 引用未重命名

**这是最关键的遗留问题。** `mili-server/src/minecraft/java`（由补丁生成的工作代码）中仍有大量 `me.earthme.luminol` 引用，涉及约 40+ 个文件。但实际类已经重命名到 `fun.bm.mili.lmili` 包下。

**涉及的典型文件和引用**：
- `ServerLevel.java:690` → `new me.earthme.luminol.utils.FoliaServerWaypointManager(this)`
- `Level.java` → `me.earthme.luminol.utils.EntityMoveOutOfRegionException`
- `ChunkGenerator.java` → `me.earthme.luminol.config.modules.function.SecureSeedConfig`
- `RandomState.java` → 多处 `me.earthme.luminol.config...` 引用
- `RegionFileStorage.java` → `me.earthme.luminol.enums.EnumRegionFormat`
- `EndPlatformFeature.java` → `me.earthme.luminol.config.modules.function.TripwireBehaviorConfig`
- 等等（约 40 个文件，100+ 处引用）

**修复方案**：
1. 在 `mili-server/src/minecraft/java` 中执行全局替换：`me.earthme.luminol` → `fun.bm.mili.lmili`
2.  amend 到对应的提交中（或创建新的修复提交）
3. 同步更新 `mili-server/minecraft-patches/features/` 中对应的补丁文件（用 `git diff` 重新生成）
4. 注意：`mili-server/src/main/java` 已经完成重命名，不要重复处理

### 问题 B：paper-server 补丁 0024 未完成

`mili-server/paper-patches/features/0024-Fix-compilation-for-MC-26.2-API-migration.patch` 的 git am 操作中断：

**当前 paper-server 工作树状态**：
- 已修改未提交：`CraftServer.java`（realPlayers() → realPlayers 字段访问）
- 已修改未提交：`LazyPlayerSet.java`（realPlayers() → realPlayers 字段访问）
- 未跟踪：`CraftLivingEntity.java.rej`（拒绝的 hunk）

**0024 补丁的 3 个 hunk**：
1. `CraftServer.java`：`realPlayers()` → `realPlayers` — ✅ 已在工作树中应用（未提交）
2. `CraftLivingEntity.java`：添加 `FoliaServerWaypointManager` 的类型转换 — ❌ 被拒绝
3. `LazyPlayerSet.java`：`realPlayers()` → `realPlayers` — ✅ 已在工作树中应用（未提交）

**CraftLivingEntity.java.rej 内容**：
```diff
     private void updateWaypoint() {
-        me.earthme.luminol.utils.FoliaServerWaypointManager manager = ((ServerLevel) getHandle().level()).getWaypointManager(); // Luminol - Restore waypoints
+        me.earthme.luminol.utils.FoliaServerWaypointManager manager = (me.earthme.luminol.utils.FoliaServerWaypointManager) ((ServerLevel) getHandle().level()).getWaypointManager(); // Luminol - Restore waypoints
         manager.untrackWaypoint(getHandle());
         manager.trackWaypoint(getHandle());
     }
```

**但当前 CraftLivingEntity.java 实际状态**（第 1154-1158 行）：
```java
    private void updateWaypoint() {
        ServerWaypointManager manager = ((ServerLevel) getHandle().level()).getWaypointManager();
        manager.untrackWaypoint(getHandle());
        manager.trackWaypoint(getHandle());
    }
```
- 使用 `net.minecraft.server.waypoints.ServerWaypointManager`（Paper 原版类型），不是 `FoliaServerWaypointManager`
- 0024 补丁的 CraftLivingEntity hunk 引用了旧的 `me.earthme.luminol` 包名，已失效

**修复方案**：
- 由于当前代码使用 `ServerWaypointManager` 接口类型，而 `ServerLevel.java:690` 实际创建的是 `FoliaServerWaypointManager` 实例，需要判断是否需要添加向下转型的 cast
- 如果 `getWaypointManager()` 返回类型是 `ServerWaypointManager`，而 `untrackWaypoint/trackWaypoint` 方法在 `FoliaServerWaypointManager` 上定义，则需要 cast
- 将 `me.earthme.luminol` 替换为 `fun.bm.mili.lmili`

---

## 剩余任务清单（按顺序执行）

### 任务 1：minecraft 源码树全局重命名 `me.earthme.luminol` → `fun.bm.mili.lmili`

```powershell
# 在 mili-server/src/minecraft/java 中
# 1. 找到所有包含 me.earthme.luminol 的 .java 文件
# 2. 全局替换 me.earthme.luminol → fun.bm.mili.lmili
# 3. git add 并 git commit --amend（追加到最新的 Fix compilation 提交中）
# 4. 重新生成受影响的补丁文件
```

**注意**：替换后需要确认 `FoliaServerWaypointManager.java` 的实际位置在 `fun/bm/mili/lmili/utils/FoliaServerWaypointManager.java`（已确认存在于此位置）。

### 任务 2：完成 paper-server 补丁 0024

```powershell
cd "E:\Program Files\Tencent\AndrowsData\Mili\paper-server"
# 1. 修复 CraftLivingEntity.java 的 updateWaypoint 方法：
#    - 将 ServerWaypointManager 替换为 fun.bm.mili.lmili.utils.FoliaServerWaypointManager
#    - 添加向下转型的 cast
# 2. 删除 CraftLivingEntity.java.rej
# 3. git add -A
# 4. git commit -m "Fix compilation for MC 26.2 API migration"
# 5. 更新对应的 0024 补丁文件（将 me.earthme.luminol 替换为 fun.bm.mili.lmili）
```

### 任务 3：全局扫描残留引用

在**所有**源码目录中搜索以下关键词，确保无残留：
- `me.earthme.luminol` — 旧包名
- `Luminol` — 旧品牌名（注释中的可保留历史标记，但 import/类引用必须替换）
- `RustBridge` / `EntityCullHelper` / `TomlConfigData`（Rust JNI 相关）
- `BotAPI` / `FakePlayer` / `ServerBot` — 假玩家相关
- `LeavesProtocol` / `PcaSyncProtocol` / `CarpetProtocol` — 协议相关
- `RedstoneDirtyTracker` / `TechnicalMCOptimizer` / `StructureProjectionManager` — 已删生电工具

### 任务 4：编译验证

```powershell
cd "E:\Program Files\Tencent\AndrowsData\Mili"
.\gradlew.bat :mili-server:compileJava 2>&1
```

修复所有编译错误直到 `compileJava` 通过。常见问题：
- 包名不匹配（`me.earthme.luminol` vs `fun.bm.mili.lmili`）
- 缺失的类引用（已删除的生电/协议类）
- API 签名变更（realPlayers 方法 vs 字段）

### 任务 5：重建所有补丁

```powershell
cd "E:\Program Files\Tencent\AndrowsData\Mili"
.\gradlew.bat rebuildAllServerPatches 2>&1
```

如果 rebuildAllServerPatches 不可用，分别重建：
```powershell
.\gradlew.bat rebuildMinecraftPatches 2>&1
.\gradlew.bat rebuildPaperPatches 2>&1
.\gradlew.bat rebuildPaperApiPatches 2>&1
```

### 任务 6：全量验证

1. 删除所有生成的工作目录（`.gradle/caches/paperweight/` 下的 checkout/filter 目录）
2. 重新运行 `applyAllPatches`
3. 确认所有补丁无冲突地应用
4. 再次运行 `compileJava` 确认编译通过

---

## 关键文件路径参考

| 组件 | Git 仓库路径 | 补丁目录 |
|------|-------------|---------|
| Minecraft 源码 | `mili-server/src/minecraft/java` | `mili-server/minecraft-patches/features/` |
| Paper-Server | `paper-server/` | `mili-server/paper-patches/features/` |
| Paper-API | `paper-api/` | `mili-api/paper-patches/features/` |
| Mili 自有源码 | `mili-server/src/main/java/fun/bm/mili/` | 无需补丁 |
| LMili API | `lmili-api/` | `mili-api/` 下的 build 配置 |

## 补丁系统说明

- 补丁文件格式为标准 git format-patch（From/Subject/diff --git 头）
- `file` tag 标记基线（Folia 上游应用完毕后的起点）
- `base` tag 标记 Folia 原始基线
- 每个补丁文件对应 git 仓库中的一个提交
- 补丁数量必须等于 `file..HEAD` 的提交数量
- 修改源码后需要 amend 提交并重新生成对应补丁文件

## 构建关键命令

```powershell
# 应用所有补丁
.\gradlew.bat applyAllPatches

# 重建所有补丁
.\gradlew.bat rebuildAllServerPatches

# 仅编译
.\gradlew.bat :mili-server:compileJava

# 仅编译 API
.\gradlew.bat :mili-api:compileJava
```

## 当前 Git 状态摘要

### mili-server/src/minecraft/java
- Branch: main, HEAD: 60e4891
- 151 commits (file tag → HEAD), 97 patches
- 工作树干净
- ⚠️ 40+ 文件含 `me.earthme.luminol` 旧引用

### paper-server
- Branch: main, HEAD: c6f33a7bd
- 3 commits ahead of upstream (file tag → HEAD)
- 未提交修改：CraftServer.java, LazyPlayerSet.java
- 未跟踪：CraftLivingEntity.java.rej

### paper-api
- Branch: main, 工作树干净

### mili-server/src/main/java
- 不在 git 管理下（直接源码）
- 包名已完成重命名到 `fun.bm.mili.lmili`
- 无 `me.earthme.luminol` 残留
