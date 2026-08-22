# LMili 插件身份系统 — 开发文档 (V2)

> 基于 `LMili_Plugin_ID_System_Design_v2.md` 实现

## 一、目标

LMili 让 Minecraft 插件从普通的 jar 升级成由 Runtime 管理的"应用单元"。每个插件有自己的身份、上下文、调度域、权限、配额和可观测性记录。

本阶段**不**做：数字签名、远程 Registry、Marketplace、强制联网验证。

## 二、模块结构

```
lmili-api  ─┬─ identity/PluginId                命名规则值对象
            ├─ identity/PluginStatus             8 状态枚举
            ├─ identity/PluginType              3 类型枚举
            ├─ identity/PluginTrustLevel        4 信任等级
            ├─ identity/PluginIdentity          不可变身份记录
            ├─ identity/PluginIdentityManager   interface
            ├─ identity/DefaultPluginIdentityManager  默认实现
            ├─ identity/PluginRuntimeContext    运行时上下文
            ├─ identity/SchedulerDomain         调度域
            ├─ identity/ResourceQuota           资源配额
            ├─ identity/PermissionContext      权限
            ├─ identity/ObservabilityContext    可观测性
            ├─ identity/LifecycleState          生命周期
            ├─ identity/LmiliJsonLoader         lmili.json 解析
            ├─ identity/PluginIdentityFallback  legacy.* 兜底
            ├─ identity/MiliIdentity            插件作者入口
            ├─ identity/conflict/PluginIdentityConflict  冲突记录
            ├─ identity/conflict/ConflictReason          冲突原因
            ├─ identity/exception/*             4 个异常类
            └─ LMili                             顶层 API 入口

lmili-server ─┬─ identity/PluginIdentityBootstrap     生命周期钩子
              ├─ config/.../PluginIdentitySystemConfig 配置开关
              └─ command/PluginsCommand                操作员命令
```

## 三、命名规则

格式：`publisher.plugin[.addon[.addon...]]`

字符集（V2 严格）：每段必须匹配 `[a-z0-9][a-z0-9._-]*`。

- 至少 2 段
- 总长 ≤ 255 字节（UTF-8）
- 严格小写——`Xucy.Mili` 直接拒绝，不自动转换
- 禁止：空格、中文、大写、`/`、`\`、`:`、`;`、`@`、`#`、`$`

### 兼容存量插件的"自动小写"

存量 Bukkit 插件的 `plugin.yml` 名字可能含大写或下划线。运行时通过 `PluginId.normalize(raw)` 桥接（lower-case + 验证），仅在 legacy 兜底路径使用，**不会影响 `lmili.json` 中的 id**。

### Addon 父子关系

addon 必须声明 `parent`，且必须匹配 id 推导出的 parent，否则构建期抛 `InvalidAddonParentException`。addon 自动共享父插件的 SchedulerDomain。

## 四、lmili.json

放在 jar **根目录**，与 plugin.yml 同级。

主插件：

```json
{
  "id":        "xucy.mili",
  "name":      "Mili",
  "version":   "1.0.0",
  "publisher": "xucy"
}
```

Addon：

```json
{
  "id":        "xucy.mili.market",
  "name":      "Mili Market",
  "version":   "1.0.0",
  "publisher": "xucy",
  "type":      "addon",
  "parent":    "xucy.mili"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | ✅ | 规范 Plugin ID |
| `name` | ✅ | 显示名 |
| `version` | ✅ | SemVer 推荐 |
| `publisher` | ✅ | 本阶段只是元数据 |
| `type` | ❌ | `plugin`（默认）/ `addon` |
| `parent` | addon 必填 | 必须等于 id.parentId() |
| `trust` | ❌ | 声明信任等级——**运行时不会自动提升到 VERIFIED** |

## 五、状态机（8 状态）

```
正常路径    DISCOVERED → LOADING → ACTIVE
失败路径    LOADING → FAILED
冲突路径    DISCOVERED → CONFLICT → OBSERVE
运维操作    OBSERVE → ACTIVE / DISABLED
卸载路径    ACTIVE → UNLOADED
```

| 状态 | onEnable 跑 | 调度器 | 默认权限 | 是否可被提升 |
|------|------------|--------|----------|--------------|
| DISCOVERED | ❌ | 拒绝 | READ_ONLY | ❌ |
| LOADING | ❌ | 拒绝 | READ_ONLY | ❌ |
| ACTIVE | ✅ | 接受 | SCHEDULER | - |
| OBSERVE | ✅ | 接受（低优先级）| READ_ONLY | ✅ |
| CONFLICT | ❌ | 接受（最多 1 并发）| NONE | ❌ |
| DISABLED | ❌ | 拒绝 | NONE | ❌ |
| FAILED | ❌ | 拒绝 | NONE | ❌ |
| UNLOADED | ❌ | 拒绝 | READ_ONLY | ❌ |

## 六、冲突处理

```
Thread A -> register(xucy.mili, 1.0.0)
Thread B -> register(xucy.mili, 2.0.0)
```

- 第一个 `xucy.mili@1.0.0` → ACTIVE
- 第二个 `xucy.mili@2.0.0` → 被拒绝
- 冲突记录 `PluginIdentityConflict(reason=DUPLICATE_ID, existing, incoming)` 写入 manager
- 第二个插件进 CONFLICT（V2 §14 明确禁止随机选择、后加载覆盖、让插件自行决定）

**禁止的 API**：`forceReplace`、`replaceIdentity`、`changeOwner`、任何字符串→id 的自动重写。

## 七、Runtime Context

每个插件拥有一个 `PluginRuntimeContext`，包含：

- `PluginIdentity` —— 我是谁？
- `SchedulerDomain` —— 我什么时候可以跑？
- `PermissionContext` —— 我能干什么？
- `ResourceQuota` —— 我能用多少资源？
- `LifecycleState` —— 我在哪个加载阶段？
- `ObservabilityContext` —— 我正在做什么？

**插件不能**自行创建或替换 Context。

## 八、并发安全（V2 §11）

`if (!contains(id)) register(id);` 这种 check-then-act 模式被 V2 显式禁止。

`DefaultPluginIdentityManager.register()` 使用 `AtomicReference<Slot>` + per-slot `synchronized`，保证 100 线程并发注册同一个 id 只有一个能成为 ACTIVE。

## 九、Legacy 兼容

没有 `lmili.json` 的传统 Bukkit 插件：

```
My_Plugin       → legacy.my-plugin
CoolPlugin      → legacy.coolplugin
有非法字符的     → legacy.<sanitized>
空名            → legacy.unknown-plugin
```

统一在 `legacy.*` 命名空间，**不会**冒用正式 LMili 命名空间。类型 `LEGACY`，信任 `UNKNOWN`。

## 十、Scheduler Owner（V2 §18）

每个任务都携带 `PluginId owner`：

```java
LMili.bindCurrentOwner(myPlugin.id());
try {
    Mili.scheduler().runAt(loc, ctx -> { ... });
} finally {
    LMili.clearCurrentOwner();
}
```

LMili 内部任务使用 `LMili.SYSTEM_OWNER_ID = "lmili.system"`。

普通插件禁止伪造其他 Plugin ID 作为 owner——`PermissionContext.requireScheduleSlot()` 会拦截。

## 十一、生命周期（V2 §32）

```
Discover → READING → VALIDATING → REGISTERING → CONTEXT_BUILDING → ENABLING → ACTIVE
卸载：   ACTIVE → UNLOADED
```

## 十二、配置

```toml
[function.plugin-identity-system]
enabled = true
```

## 十三、操作员命令

```
/plugins list                    列出所有身份
/plugins info <id>               查看详情
/plugins conflicts               列出冲突（V2 §23 最低要求）
/plugins observe <id>            进入 OBSERVE
/plugins enable <id>             恢复 ACTIVE
/plugins disable <id>            禁用
```

权限：`mili.admin.identity`

## 十四、异常类型

| 异常 | 抛出场景 |
|------|----------|
| `InvalidPluginIdException` | id 字符串不符合规则 |
| `InvalidPluginMetadataException` | lmili.json 字段缺失或非法 |
| `PluginIdentityConflictException` | id 被其他插件占用 |
| `InvalidAddonParentException` | addon parent 不匹配 |

**禁止** `catch (Exception ignored) {}` 静默吞错。

## 十五、安全原则（V2 §30）

1. ❌ 用插件名作为唯一身份
2. ❌ 用 JAR 文件名作为唯一身份
3. ❌ 允许插件覆盖已有 ID
4. ❌ 插件修改自己的 Identity
5. ❌ 插件注册其他插件的 ID
6. ❌ IdentityManager 直接负责所有 Scheduler 逻辑
7. ❌ 为 ID 系统重写整个 Minecraft Scheduler
8. ❌ 引入不必要的大型第三方框架

实现层保证：

| 原则 | 体现 |
|------|------|
| 1-2 | `PluginId.parse()` 严格校验，JAR 名不参与 |
| 3 | `register()` 永不覆盖；返回 existing |
| 4 | `PluginIdentity` 全字段 final；状态用 `withStatus` 产生新对象 |
| 5 | 注册接口只接受 `PluginIdentity`，由内部调用 |
| 6 | IdentityManager 只管身份，不接收 scheduler 调用 |
| 7 | SchedulerDomain 是独立的子系统 |
| 8 | 仅依赖标准库（无第三方） |

## 十六、性能（V2 §31）

- Plugin ID 查询 O(1) — `ConcurrentHashMap.computeIfAbsent`
- Identity 对象轻量 — 不含 task 列表、chunk、entity 数据
- 调度域独立 — addon 共享父域（避免 N 个独立线程池）

## 十七、测试矩阵

按 V2 §33 实现：

| 测试类 | 覆盖点 |
|--------|--------|
| `PluginIdTest` | 合法/非法 ID、normalize、parent、child |
| `LmiliJsonLoaderTest` | 主插件/addon schema、addon 校验、信任陷阱 |
| `PluginIdentityFallbackTest` | legacy 命名空间、规范化、空名兜底 |
| `PluginIdentityTest` | 不可变、addon 父子校验、type 校验 |
| `DefaultPluginIdentityManagerTest` | 注册、冲突、状态切换、并发不崩溃 |
| `PluginIdentityManagerConcurrencyTest` | **100 线程并发**、冲突启动、并发读写 |
| `PluginRuntimeContextTest` | 8 状态默认配置、addon 共享父域、配额计数器 |

## 十八、API 速查

```java
// 顶层入口
LMili.getPluginIdentityManager()
LMili.getIdentity(pluginId)
LMili.getRuntimeContext(pluginId)
LMili.bindCurrentOwner(pluginId)
LMili.clearCurrentOwner()

// 命名
PluginId id = PluginId.parse("xucy.mili");
Optional<PluginId> parent = id.parentId();
boolean isChild = id.isChildOf(parent);

// 注册表
mgr.register(identity)
mgr.find(id)
mgr.contains(id)
mgr.getAll()
mgr.getStatus(id)
mgr.setStatus(id, status)
mgr.getConflicts()
mgr.getConflicts(id)
mgr.findByBukkitName(pluginName)
mgr.unregister(id)

// 上下文
ctx = PluginRuntimeContext.forIdentity(identity);
ctx.identity()
ctx.schedulerDomain()
ctx.permissionContext()
ctx.resourceQuota()
ctx.observability()
ctx.lifecycleState()
ctx.requirePermission(SCHEDULER)
ctx.requireScheduleSlot()

// Legacy 兜底
PluginIdentity id = PluginIdentityFallback.forBukkit("MyPlugin", "1.0.0");
PluginId id = PluginIdentityFallback.synthesizeLegacyId("My_Plugin");
                                                // -> legacy.my-plugin
```

## 十九、未来扩展

按 V2 §20 / §21 / §22 预留接口：

- ✅ Addon 父子关系（已实现共享域）
- ✅ PermissionContext 保留结构（未来扩 ACL）
- ✅ ResourceQuota 保留字段（未来加 CPU 时间、内存预算）
- ⏳ 签名系统 → `PluginTrustLevel.VERIFIED` 真正可达
- ⏳ 跨服务器 Registry
- ⏳ Marketplace