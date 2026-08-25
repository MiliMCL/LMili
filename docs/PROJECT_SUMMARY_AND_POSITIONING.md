# LMili 项目总结与定位

## 一、项目概述

### 1.1 项目身份

| 属性 | 值 |
|------|-----|
| 产品名称 | LMili（米粒） |
| Maven 坐标 | `io.github.xucy10:lmili-api:26.2-R0.1` |
| 当前版本 | 26.2-R0.1 |
| 目标 MC 版本 | 26.2 |
| 最低 JDK | 25+ |
| 许可证 | GPL-3.0 |
| 包名空间 | `fun.bm.mili.*` |

### 1.2 继承关系

```
Minecraft（原版）
  └── Paper（服务端框架）
        └── Folia（区域多线程调度）
              └── LMili（本项目）
```

LMili 是一个**基于 Folia 的高性能 Minecraft 服务端核心**，目标是成为"纯粹的 Folia"——不引入生电/红石机制修改与客户端协议魔改，专注于在 Folia 区域多线程调度模型之上提供更多 API、稳定性修复与 bug 修复，以及通用的性能优化。

---

## 二、模块架构

### 2.1 模块组成

```
Mili/
├── lmili-api/              # API 模块（插件开发者编译时依赖）
├── lmili-server/           # 服务端核心（可运行产物）
├── paper-api/              # Paper API（补丁应用目标）
├── paper-server/           # Paper 服务器（补丁应用目标）
├── folia-server/           # Folia 子模块（上游）
└── docs/                   # 文档
```

### 2.2 lmili-api 模块

**职责**：插件开发者编译时依赖，包含所有公共 API。

```
fun.bm.mili.api/                        # 公共 API
├── UnifiedSchedulerAPI.java            # 统一调度 API 入口
├── PluginScheduler.java                # 插件调度器接口
├── Scheduler.java                      # 基础调度器
├── EntityScheduler.java                # 实体调度器
├── SyncEntityScheduler.java            # 同步实体调度器
├── SyncTaskExecutor.java               # 同步任务执行器
├── SyncTaskResult.java                 # 同步任务结果
├── SyncTaskConstraints.java            # 同步任务约束
├── SchedulerSecurityManager.java       # 安全管理器
├── SecurityPolicy.java                 # 安全策略
├── PluginThreadMonitor.java            # 线程监控器
├── Mili.java                           # Mili 入口
├── MiliPlugin.java                     # Mili 插件基类
└── EntityTaskContext.java              # 实体任务上下文

fun.bm.mili.lmili.api/                  # 内部 API
├── LMili.java                          # LMili 运行时入口
├── identity/                           # 插件身份系统
│   ├── PluginId.java                   # 插件 ID（不可变值对象）
│   ├── PluginIdentity.java             # 插件身份
│   ├── PluginIdentityManager.java      # 身份管理器
│   ├── PluginRuntimeContext.java       # 运行时上下文
│   ├── ResourceQuota.java              # 资源配额
│   ├── PermissionLevel.java            # 权限等级
│   ├── PluginTrustLevel.java           # 信任等级
│   └── SchedulerDelegation.java        # 调度委托
├── threading/                          # 线程 API
│   ├── LmiliRunnable.java              # LMili Runnable 包装
│   ├── PluginExecutor.java             # 插件执行器
│   ├── PluginScheduler.java            # 插件调度器（内部）
│   ├── PluginThreadSpec.java           # 线程规格
│   └── ThreadingBackend.java           # 线程后端
├── observability/                      # 可观测性
│   ├── MetricsRegistry.java            # 指标注册表
│   ├── PluginSchedulerCapture.java     # 调度捕获
│   └── SchedulerMetrics.java           # 调度指标
├── event/                              # 事件总线
├── entity/                             # 实体事件
└── portal/                             # 传送门事件
```

### 2.3 lmili-server 模块

**职责**：服务端核心实现，包含所有运行时组件。

```
fun.bm.mili/                            # 服务端核心
├── lmili/                              # 核心实现子树
│   ├── thread/                         # 调度器核心
│   │   ├── regiontick/                 # RegionTick 调度实现
│   │   │   ├── dag/                    # DAG 依赖图
│   │   │   │   ├── SystemGraph.java    # 系统图
│   │   │   │   ├── ConflictGraph.java  # 冲突图
│   │   │   │   ├── CompiledDag.java    # 编译后 DAG
│   │   │   │   ├── TickDag.java        # Tick DAG
│   │   │   │   └── TickDagNode.java    # DAG 节点
│   │   │   ├── executor/               # 执行器
│   │   │   │   ├── DagExecutionEngine.java      # DAG 执行引擎
│   │   │   │   ├── ModernDagTickExecutor.java   # 现代 DAG Tick 执行器
│   │   │   │   ├── LMiliTickExecutor.java       # LMili Tick 执行器
│   │   │   │   └── LMiliRegionNodeScheduler.java # 跨 Region 节点调度器
│   │   │   ├── RegionTickDispatcher.java # 调度分发器
│   │   │   ├── RegionTickExecutor.java   # 执行器
│   │   │   ├── RegionTickWorker.java     # Worker
│   │   │   └── WorkerPoolManager.java    # Worker 池管理器
│   │   ├── scheduler/                  # 新调度系统
│   │   │   ├── api/                    # 公共接口
│   │   │   │   ├── MiliScheduler.java  # 调度器主接口
│   │   │   │   ├── TaskHandle.java     # 任务句柄
│   │   │   │   ├── BatchHandle.java    # 批量句柄
│   │   │   │   ├── EntityScheduler.java # 实体调度器
│   │   │   │   └── RegionTask.java     # 区域任务
│   │   │   ├── execute/                # 执行组件
│   │   │   │   ├── VirtualThreadPool.java       # 虚拟线程池
│   │   │   │   ├── RegionQueue.java             # 区域队列
│   │   │   │   ├── WorkStealingCoordinator.java # 工作窃取协调器
│   │   │   │   ├── BlockingTaskIsolation.java   # 阻塞任务隔离
│   │   │   │   └── SchedulerWorker.java         # 调度 Worker
│   │   │   ├── internal/               # 内部实现
│   │   │   │   ├── ExecutionContext.java        # 执行上下文
│   │   │   │   ├── ObjectPool.java              # 对象池
│   │   │   │   └── PerformanceMetrics.java      # 性能指标
│   │   │   └── tick/                   # Tick 相关
│   │   │       ├── TickBarrier.java    # Tick 屏障
│   │   │       ├── TickContext.java    # Tick 上下文
│   │   │       └── TickState.java      # Tick 状态
│   │   └── runtime/                    # Parallel Simulation Runtime
│   │       ├── UnifiedRuntime.java     # 统一运行时
│   │       ├── DeadlineScheduler.java  # 截止期调度器
│   │       ├── AdaptiveSlicer.java     # 自适应切片器
│   │       ├── SliceCostCalculator.java # 切片成本计算器
│   │       ├── WorkStealingDeque.java  # 工作窃取双端队列
│   │       ├── generation/             # Tick Generation 生命周期
│   │       ├── ownership/              # Region 所有权系统
│   │       ├── message/                # 跨 Region 消息
│   │       ├── conflict/               # 并行冲突检测
│   │       ├── metrics/                # 运行时指标
│   │       └── entity/                 # Entity 并行模拟
│   ├── config/                         # 配置系统
│   │   ├── ConfigManager.java          # 配置管理器
│   │   └── modules/                    # 配置模块（50+）
│   │       ├── experiment/             # 实验功能
│   │       ├── fixes/                  # 修复配置
│   │       ├── function/               # 功能配置
│   │       ├── misc/                   # 杂项配置
│   │       ├── optimizations/          # 优化配置
│   │       └── unsupported/            # 不支持的配置
│   ├── chunk/                          # 区块系统
│   ├── command/                        # 命令系统
│   ├── identity/                       # 身份系统
│   ├── observability/                  # 可观测性
│   ├── utils/                          # 工具类
│   └── villager/                       # 村民优化器
```

---

## 三、核心 API 体系

### 3.1 API 分层

LMili 提供三套 API，面向不同层次的开发者：

| API 层次 | 包路径 | 目标用户 | 说明 |
|----------|--------|----------|------|
| **BukkitAPI** | `org.bukkit.*` | 所有插件开发者 | 标准 Bukkit API，完全兼容 |
| **PaperAPI** | `io.papermc.paper.*` | 高级插件开发者 | Paper 扩展 API，包含 Folia 兼容层 |
| **LMiliAPI** | `fun.bm.mili.api.*` | LMili 专属插件 | 统一调度 API、身份系统、可观测性 |

### 3.2 LMiliAPI 核心接口

#### UnifiedSchedulerAPI（统一调度 API）

```java
// 获取当前插件的调度器
PluginScheduler scheduler = UnifiedSchedulerAPI.forCurrentPlugin();

// 获取指定插件的调度器
PluginScheduler scheduler = UnifiedSchedulerAPI.forPlugin(pluginId);

// 获取系统级调度器（内部使用）
PluginScheduler systemScheduler = UnifiedSchedulerAPI.forSystem();
```

**设计原则**：
- 所有插件调度任务必须通过 LMili 统一管理
- 禁止插件自行创建线程、ExecutorService、ScheduledExecutor
- 违反安全策略将被记录并拒绝执行

#### PluginScheduler（插件调度器）

```java
// 异步任务
scheduler.runAsync(() -> { ... });

// 位置绑定任务
scheduler.runAt(location, ctx -> { ... });

// 延迟任务
scheduler.runDelayed(() -> { ... }, 5, TimeUnit.SECONDS);

// 同步任务（带超时保护）
SyncTaskResult<Integer> result = scheduler.runSync(() -> {
    return player.getInventory().getSize();
});

// 实体绑定任务
scheduler.forEntity(entity).run(ctx -> { ... });
scheduler.forEntitySync(entity).run(() -> { ... });
```

#### SyncTaskExecutor（同步任务执行器）

**卡顿防护机制**：
1. **超时控制**：每个任务有严格的超时限制（默认 5ms）
2. **死锁检测**：禁止嵌套同步任务、检测循环等待
3. **负载感知**：服务器负载高时缩短超时时间
4. **隔离执行**：任务在隔离上下文中执行
5. **审计追踪**：记录所有同步任务的执行时间和结果

**性能目标**：
- 正常负载下 P99 同步任务延迟 < 5ms
- 高负载下自动降级，任务直接返回失败
- 零死锁风险

---

## 四、线程模型与调度架构

### 4.1 调度器架构总览

```
RegionTickBootstrap.init()
    ├── RegionTickDispatcher.init()         ← 初始化旧版 dispatcher
    ├── MiliSchedulerBuilder.create()       ← 创建新调度器
    │       .build() → MiliSchedulerImpl
    └── Mili.registerScheduler(adapter)    ← 注册公共 API
```

### 4.2 Parallel Simulation Runtime（P0-P4）

| 层级 | 组件 | 说明 |
|------|------|------|
| **P0** | Tick Generation 生命周期 | CREATED → RUNNING → DEADLINE_EXCEEDED → DRAINING → COMPLETED/CANCELLED |
| **P1** | Unified Scheduler Runtime | RuntimeTask 统一抽象、Work Stealing、Deadline Scheduler、Dynamic Slice Cost |
| **P2** | Dependency-aware Tick DAG | Region 所有权、跨 Region 消息、并行冲突检测 |
| **P3** | Entity Parallel Simulation | 分阶段 Entity Tick、Entity 依赖图、拓扑排序执行 |
| **P4** | 运行时全指标 | TPS、MSPT、P50/P95/P99、Queue Size、Worker Utilization |

### 4.3 线程模型

```
RegionTickDispatcher
    └── MiliScheduler.submit(RegionTask)
            └── WorkStealingCoordinator.submit(task)
                    └── RegionQueue ──(work-stealing)──▶ VirtualThreadPool (virtual threads)
                                                                    └── Carrier Threads → CPU Cores
```

**关键特性**：
- **Virtual Thread 后端**：基于 JDK 25 虚拟线程，以同步风格编写异步逻辑
- **Work-Stealing 负载均衡**：LIFO 本地执行 + FIFO 窃取
- **阻塞操作隔离**：专用平台线程池 + 信号量限流，防止 carrier pinning
- **对象池复用**：ExecutionContext + ObjectPool ThreadLocal 池，热路径零分配

### 4.4 RegionTickPool 执行模式

根据区域 chunk 数量自动选择：

| 模式 | 触发条件 | 说明 |
|------|----------|------|
| 单线程同步 | chunk 数 < 阈值 | 无调度开销 |
| Virtual Thread | 中等负载 | chunks 拆分为 slices，每个 slice 一个 virtual thread |
| Platform Thread | 高负载 | 贪心负载均衡分发给固定 worker 队列 |

---

## 五、插件身份与安全系统

### 5.1 PluginId（插件 ID）

**格式规范**：`publisher.plugin` 或 `publisher.plugin.addon`

**验证规则**：
- 每个段匹配 `[a-z0-9][a-z0-9._-]*`
- 至少两个段（publisher + plugin）
- 总长度不超过 255 字节（UTF-8）
- 严格小写，大写字母被拒绝
- 禁止字符：空格、`/`、`\`、`:`、`;`、`@`、`#`、`$` 及非 ASCII

### 5.2 安全策略

| 策略 | 说明 |
|------|------|
| **严格模式** | 禁止一切自行创建（默认） |
| **宽松模式** | 仅审计，不拦截 |
| **自定义策略** | 可配置允许/禁止项 |

**禁止的行为**：
- `new Thread(r).start()` → 使用 `scheduler.runAsync(r)`
- `Executors.newFixedThreadPool()` → 使用 `scheduler.runAsync()`
- `ScheduledExecutorService` → 使用 `scheduler.runDelayed()`
- `CompletableFuture.supplyAsync()` → 使用 `scheduler.runAsync()`

### 5.3 资源配额

每个插件有独立的资源配额：
- `maxConcurrentTasksPerPlugin`：每插件最大并发任务数
- `maxQueuedTasksPerPlugin`：每插件最大排队任务数
- `defaultTaskTimeoutMs`：默认任务超时

---

## 六、配置系统

### 6.1 配置分类

| 类别 | 说明 | 代表模块 |
|------|------|----------|
| `function` | 游戏机制与实用功能 | LanguageConfig、TpsBarConfig、ReplayAPIConfig |
| `experiment` | 并发功能 | RegionBalancerConfig、CrossRegionHelperConfig |
| `optimizations` | 性能优化 | NetworkOptimizerConfig、ChunkSystemConfig、SIMDConfig |
| `fixes` | 崩溃/行为修复 | CollisionBehaviorConfig、PortalLinkFixConfig |
| `misc` | 杂项 | AutoUpdateConfig、BStatsConfig、SentryConfig |
| `unsupported` | 不支持的配置 | DisableCheckForFoliaSupported |

### 6.2 配置文件格式

TOML 格式，纯 Java night-config 解析实现。

---

## 七、补丁系统

### 7.1 补丁结构

| 目录 | 数量 | 说明 |
|------|------|------|
| `minecraft-patches/features-original/` | 97 | 原始独立补丁（参考用） |
| `minecraft-patches/features/` | 5 | 合并后补丁（构建实际使用） |
| `minecraft-patches/merged-v3/` | 5 | 构建使用的合并补丁 |

### 7.2 5 个合并补丁

| 补丁文件 | 功能域 |
|---------|--------|
| `01-rebrand-consolidated.patch` | 品牌重命名（Luminol → Mili） |
| `02-config-system-consolidated.patch` | 配置系统 |
| `03-entity-optimizations-consolidated.patch` | 实体优化 |
| `04-chunk-region-consolidated.patch` | 区块与区域 |
| `06-misc-consolidated.patch` | 杂项 |

### 7.3 补丁工作流

1. 在 `lmili-server/src/minecraft/java/` 中修改代码
2. 提交变更：`git commit -m "描述"`
3. 重建补丁：`./gradlew :lmili-server:rebuildAllServerPatches`
4. 提交补丁文件并推送

---

## 八、API 扩展

在 Folia/Paper API 之上提供更多能力：

| API | 说明 |
|-----|------|
| **Mili 调度器 API** | 挂起式调度器，以 virtual thread 为后端，支持 `awaitCrossRegion` |
| **Tick Regions API** | 查询/操作 tick 区域的 API |
| **ReplayMod 摄影师** | 创建 ReplayMod 摄影师实体进行录像 |
| **Bytebuf API** | 面向插件的自定义数据包读写 API |
| **数据包事件** | `PacketInEvent` / `PacketOutEvent` 监听数据包收发 |
| **实体传送异步事件** | `EntityTeleportAsyncEvent`、`PreEntityPortalEvent`、`PostEntityPortalEvent` |
| **传送门事件** | `PortalLocateEvent`、`EndPlatformCreateEvent` |
| **玩家事件** | `PostPlayerRespawnEvent`、`PlayerOperationLimitEvent` |
| **Waypoint API** | 实体路径点追踪与恢复 API |

---

## 九、性能优化

来自上游的通用优化（不涉及生电行为修改）：

| 优化领域 | 具体优化 |
|----------|----------|
| **数据结构** | 噪声生成、AI 属性集合、大脑映射、准则映射 |
| **实体** | 实体移动零位移跳过、可变实体唤醒时长、canSee 检查优化 |
| **区块** | 区块加载查找削减、投射物区块加载削减、寻路区域限制 |
| **网络** | 网络与协议层优化、区块增量压缩 |
| **村民** | Lobotomize（发呆）优化、传感器工作削减 |
| **异步** | 异步寻路、动态视距、实体数据脏追踪 |
| **硬件** | CPU 亲和性、SIMD 优化 |

---

## 十、项目定位总结

### 10.1 核心价值

1. **统一调度**：所有插件调度必须通过 LMili 统一管理，禁止自行创建线程
2. **身份追踪**：每个任务自动绑定提交它的插件 ID
3. **配额控制**：每个插件有独立的资源配额，防止资源耗尽
4. **安全强制**：违规创建线程会被检测并阻止
5. **同步任务安全**：带超时保护的同步任务执行，防止卡顿

### 10.2 技术特色

1. **Parallel Simulation Runtime**：从 Folia 并行 Tick 优化核心演进为完整的并行模拟运行时
2. **Virtual Thread 后端**：基于 JDK 25 虚拟线程，以同步风格编写异步逻辑
3. **DAG 依赖调度**：依赖感知的 Tick DAG，最大化并行度
4. **Work-Stealing 负载均衡**：自动平衡各 region 负载
5. **可观测性**：运行时全指标（TPS、MSPT、P50/P95/P99、Queue Size、Worker Utilization）

### 10.3 目标用户

- **插件开发者**：使用 LMiliAPI 开发安全、高效的插件
- **服务器管理员**：使用 LMili 作为高性能服务端核心
- **技术贡献者**：参与 Parallel Simulation Runtime 等前沿技术的开发

### 10.4 与竞品对比

| 特性 | LMili | Folia | Paper | Purpur |
|------|-------|-------|-------|--------|
| 区域多线程 | ✅ | ✅ | ❌ | ❌ |
| 统一调度 API | ✅ | ❌ | ❌ | ❌ |
| 同步任务安全 | ✅ | ❌ | ❌ | ❌ |
| 插件身份系统 | ✅ | ❌ | ❌ | ❌ |
| 可观测性 | ✅ | ❌ | ❌ | ❌ |
| Virtual Thread | ✅ | ❌ | ❌ | ❌ |
| DAG 依赖调度 | ✅ | ❌ | ❌ | ❌ |

---

## 十一、当前状态与限制

### 11.1 已完成

- ✅ 统一调度 API（UnifiedSchedulerAPI）
- ✅ 插件身份系统（PluginId、PluginIdentity）
- ✅ 同步任务执行器（SyncTaskExecutor）
- ✅ 线程监控器（PluginThreadMonitor）
- ✅ 配置系统（50+ 配置模块）
- ✅ 补丁系统（Hyacinthusweight）
- ✅ 性能优化（来自多个上游）
- ✅ API 扩展（事件、调度器、Waypoint 等）

### 11.2 编译状态

- ✅ `lmili-api` 模块编译成功
- ✅ `lmili-server` 模块编译成功
- ⚠️ 测试编译存在预存在问题（与本次重构无关）
- ⚠️ `scanJarForBadCalls` 存在预存在问题（PortalForcer 引用）

### 11.3 向后兼容

- ✅ 完全兼容 Bukkit API
- ✅ 完全兼容 Paper API
- ✅ 兼容 Folia API（通过 LMili 实现）
- ✅ 提供 Folia 向后兼容类（`FoliaServerWaypointManager`、`FoliaEntityMovingFixConfig`）

### 11.4 已知限制

1. **测试编译**：部分测试用例存在预存在问题
2. **scanJarForBadCalls**：PortalForcer 引用问题
3. **文档**：部分 API 文档待完善

---

## 十二、开发建议

### 12.1 插件开发者

1. **使用 UnifiedSchedulerAPI**：所有调度任务通过此 API 提交
2. **避免自行创建线程**：使用 `scheduler.runAsync()` 替代
3. **同步任务慎用**：仅用于快速查询，避免 IO 操作
4. **监控调度指标**：定期检查 `scheduler.metrics()`
5. **配置安全策略**：根据需求选择严格/宽松模式

### 12.2 项目维护者

1. **补丁工作流**：修改代码后及时重建补丁
2. **配置模块**：新增功能时添加配置模块
3. **文档同步**：API 变更时更新开发指南
4. **性能监控**：关注运行时指标，及时发现性能问题

---

*文档生成时间：2026-08-25*
*项目版本：26.2-R0.1*
