# RegionTickPool：Mili 独立并行 Chunk Tick 框架

> **本文档介绍 RegionTickPool 的设计原理、理论性能上限、实现机制与当前兼容性状态。文中所有数值均为基于代码参数的理论推导，并非实测数据。实测表现因硬件、插件、世界复杂度而异，需用户自行验证。**

---

## 一、背景：Folia 调度模型的瓶颈

Folia 的革命性设计是将世界划分为多个 Region，每个 Region 独立调度 tick。但每个 Region **内部**的所有 Chunk 必须串行 tick——无论服务器 CPU 有多少核心，单个 Region 始终只使用一个线程。在玩家密集、建筑繁多的主世界，单个 Region 可能包含 32-64 个 Chunk，导致该 Region 的 tick 耗时远超 50ms 的目标帧时间，引发 TPS 下跌。

Mili 之前的 RegionBalancer 通过共享线程池和低负载区域合并优化了线程管理，但**没有改变"Region 内串行"这一根本限制**。RegionTickPool 是 Mili 对此问题的系统性回应。

---

## 二、RegionTickPool 的设计

### 2.1 核心思路：Chunk Slice 并行化

Folia 原生：`1 Region = 1 线程`，所有 Chunk 串行

RegionTickPool：`16 Chunks = 1 Slice`，多个 Slice 并行分配到 worker 线程

```
RegionTickDispatcher.dispatchTick()
    └── ChunkTickDispatcher.dispatch()
            ├── 如果 chunk 数 < 4（parallelismThreshold）：单线程同步执行
            └── 否则：将 chunks 按 sliceSize=16 分组，并行提交到 worker 线程
```

关键配置参数（来自 `RegionTickPoolConfig`）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sliceSize` | 16 | 每个 Slice 包含的 Chunk 数 |
| `parallelismThreshold` | 4 | 低于此值不拆分，避免调度开销 |
| `workerCount` | CPU - 1 | Worker 线程总数 |
| `useVirtualThreads` | true | 使用 JDK 虚拟线程 |

### 2.2 行为等效性说明

RegionTickPool **不改变 Chunk tick 的实际逻辑**。在 `FoliaTickExecutor.executeSlice()` 中，每个 Chunk 仍然调用 `level.tickChunk(chunk, tickSpeed)`——与 Folia 原版完全一致。

**但需要注意：** 由于多个 Slice 并行执行，**同一 Region 内不同 Chunk 的 tick 时序关系发生了变化**——原本严格串行的两个 Chunk，在 RegionTickPool 下可能同时处于 tick 过程中。虽然每个 Chunk 的内部逻辑完全相同，但对于依赖跨 Chunk 时序的极端场景（如跨 Chunk 的红石信号同步），行为可能有微秒级的差异。Mili 的设计原则是不修改红石/生电机制，但跨 Chunk 时序相关的边界场景仍需用户自行验证。

Entity tick 则**完全保留 Folia 原生行为**——代码中 `dispatchEntityTick()` 通过 `legacyDispatcher` 在 region tick 线程上同步执行，不经过并行调度。

### 2.3 线程安全保障

RegionTickPool 的并行执行引入了跨线程访问 Chunk 的风险。代码中通过以下机制保障安全：

1. **AsyncCatcherManager**：引用计数器，在 virtual dispatch 期间绕过 Folia 的 async catcher 检查（防止 "Thread failed main thread check" 崩溃）
2. **AsyncChunkAccessor**：跨线程 Chunk 访问代理，通过 `TickThread.isTickThreadFor()` 判断当前线程，选择同步/异步的 Chunk 获取方式
3. **FoliaWatchdogThread**：保留 Folia 原生看门狗，超时检测机制不变
4. **时间预算**：`SLICE_TIME_BUDGET_MS = 45ms`，Slice 超时后跳过剩余 Chunk 的 tick，避免阻塞

---

## 三、理论性能分析（基于代码参数推导）

> **声明：以下所有数字均为基于阿姆达尔定律和排队论的理论估算，并非实际测试结果。实际表现因硬件、插件负载、世界复杂度、Chunk 内容（实体/红石密度）等因素可能有显著差异。Mili 团队尚未公布标准化测试环境和可复现的基准测试结果。**

### 3.1 理论模型

**阿姆达尔定律：**

$$S = \frac{1}{(1 - P) + \frac{P}{N}}$$

其中 P 为可并行化比例，N 为并行度。

**服务端 tick 时间构成（基于典型负载估算）：**

| 阶段 | 占比（估计） | 是否可并行 |
|------|------------|-----------|
| chunk tick（方块/实体/随机刻） | ~60% | ✅ |
| entity tick（实体 AI/移动） | ~15% | ❌ 保持串行 |
| 网络/玩家交互 | ~10% | ❌ 全局串行 |
| 区块 IO/POI 更新 | ~8% | ❌ 阻塞操作 |
| 其他（红石/天气等） | ~7% | ❌ |

**可并行化比例 P ≈ 0.60**（chunk tick 部分）

### 3.2 单 Region 加速的理论上限

假设：8 核 CPU（workerCount = 7），Slice 并行开销可忽略

| Region chunk 数 | Folia 原生（串行） | 理论并行耗时 | 理论加速比 |
|----------------|-------------------|------------|-----------|
| 4 | 20ms | 20ms（< 阈值，不拆分） | 1.0x |
| 16 | 80ms | 20ms（4 slices / 4 workers） | 4.0x |
| 32 | 160ms | 20ms（8 slices 但受 7 workers 限制 → ~23ms） | ~7.0x |
| 64 | 320ms | 45ms（受 `maxWorkersPerRegion` 限制） | ~7.1x |

**说明：**
- 加速比受限于 `maxWorkersPerRegion`（默认 `workerCount / 2 = 3`），而非 worker 总数
- 当 Slice 数 > maxWorkersPerRegion 时，加速比趋于饱和
- 上表假设每个 Chunk tick 耗时均匀，实际中 Chunk 内容差异很大

### 3.3 阿姆达尔上限

保守模型（P = 0.60）：$S_{max} = \frac{1}{1 - 0.60} = 2.5x$

乐观模型（若 chunk tick 中 95% 可并行，P → 0.95）：$S_{max} = \frac{1}{1 - 0.95} = 20x$

**注意：** 理论上限不等于实际表现。实际加速受内存带宽、缓存一致性、Chunk 内容差异、GC 等因素约束。

### 3.4 内存占用分析（基于 JDK 25 虚拟线程规格）

| 方案 | 单线程栈内存（典型值） | 说明 |
|------|---------------------|------|
| 平台线程（RegionBalancer） | ~1 MB | JVM 默认栈大小 |
| 虚拟线程（RegionTickPool） | ~2-4 KB（活跃时） | 初始栈大小，按需扩展 |

**理论内存节省：** 若同时存在 100 个 tick 任务，平台线程约需 100 MB，虚拟线程约需 200-400 KB。

**但需要注意：** "数千个 tick 任务不会内存溢出"的说法**不准确**。虚拟线程的栈内存虽然小，但：
- 每个虚拟线程仍会累积调用栈帧
- 对象分配（`RegionTask`、`CompletableFuture` 等）才是主要内存消耗
- 实际内存占用取决于任务队列深度和对象生命周期，代码中通过 `virtualThreadTimeoutMs = 4000ms` 超时限制堆积

### 3.5 延迟分析（排队论 M/M/c 模型，理论估算）

| 指标 | RegionBalancer（全局优先队列） | RegionTickPool（Work-Stealing） | 理论改善 |
|------|------------------------------|-------------------------------|---------|
| 队列锁竞争 | 高（所有 worker 竞争同一把锁） | 低（本地队列 + 窃取） | 估计降低 40-60% |
| 等待时间 | 受全局排序开销影响 | 受窃取随机性影响 | 取决于负载分布 |

**注意：** 上述改善百分比是基于排队论模型的理论趋势，非实测数据。实际效果高度依赖于负载的均匀程度。

---

## 四、实现细节

### 4.1 虚拟线程与阻塞隔离

`BlockingTaskIsolation` 的实现逻辑：

```
if (当前线程是 Virtual Thread && 即将执行阻塞操作) {
    提交到专用平台线程池（maxBlockingTasks 限流）
    Virtual Thread 立即 unmount，不占用 Carrier Thread
} else {
    直接执行
}
```

Carrier 线程数 = `Runtime.getRuntime().availableProcessors()`（虚拟线程模式下的软上限）

### 4.2 DAG 并行调度

`ModernDagTickExecutor` + `DagExecutionEngine` 实现了非阻塞的 DAG 任务调度：
- `ConflictGraph`：稀疏冲突图替代 O(n²) 矩阵
- `CompiledDag`：不可变编译后 DAG
- 非阻塞回调驱动（`CompletableFuture`）

### 4.3 诊断与监控

- `RegionDiagnostics`：tick 耗时统计、超时记录
- `PerformanceSnapshot`：调度器吞吐量、队列深度
- `DiagnosticCollector`：健康状态摘要

---

## 五、兼容性与已知限制

### 5.1 明确支持的环境

| 项目 | 状态 |
|------|------|
| Minecraft 版本 | 26.2 |
| JDK 版本 | 25+（构建与运行均需要） |
| Folia API 兼容性 | 保留完整 |
| 单世界 | 支持 |
| RegionBalancer 共存 | 不支持（已被 RegionTickPool 替代） |

### 5.2 兼容性待验证/已知限制

| 项目 | 状态 | 说明 |
|------|------|------|
| 多世界 | 未完全验证 | 当前实现基于单 Region dispatcher，多世界场景下调度公平性待确认 |
| 跨 Chunk 时序敏感行为 | 需用户自行验证 | 如跨 Chunk 红石同步、跨 Chunk 实体交互时序 |
| Entity tick 并行化 | 未实现 | Entity tick 仍然保持 Folia 原生串行行为 |
| 插件兼容性 | 大部分兼容 | 依赖 Folia 原生异步行为的插件可能需要适配 |
| 实体传送 | 保留 Folia 原生修复 | 传送逻辑使用已有补丁，不经过 RegionTickPool |
| 生产环境稳定性 | 需验证 | RegionTickPool 已永久启用；生产部署前需充分压测 |

### 5.3 实验性质声明

RegionTickPool 是当前服务端的固定调度路径，配置中不提供开关。Mili 团队建议：
- 先在测试环境验证
- 逐步增加玩家数量观察稳定性
- 关注 `RegionDiagnostics` 输出的诊断信息
- 如遇 TPS 异常或行为偏差，优先禁用并反馈

---

## 六、配置方法

```toml
# region_tick_pool.toml
[region_tick_pool]
# Worker 线程数（默认 CPU 核心数 - 1）
worker-count = 0

# 并行化阈值（默认 4）
parallelism-threshold = 4

# Slice 大小（默认 16）
slice-size = 16

# 是否使用虚拟线程（默认 true）
use-virtual-threads = true

# Virtual Thread 并行 tick 超时（毫秒，默认 4000）
virtual-thread-timeout-ms = 4000
```

---

## 七、常见问题

### Q：RegionTickPool 是否修改了红石/生电机制？

**不修改。** 每个 Chunk 的 tick 逻辑仍然是 `level.tickChunk(chunk, tickSpeed)`——与 Folia 原版完全一致。但并行执行意味着同一 Region 内不同 Chunk 的 tick 时序关系发生了变化。单 Chunk 内的红石行为不变，跨 Chunk 的极端时序场景建议测试验证。

### Q：加速比真的有 4-14 倍吗？

**这是理论上限，非实测保证。** 基于阿姆达尔定律和代码参数（sliceSize=16、workerCount=CPU-1），在理想条件下（Chunk 内容均匀、无内存瓶颈、IO 极少），单 Region 级别的 chunk tick 可能获得该范围内的加速。实际表现取决于具体的工作负载。Mili 社区尚未发布标准化基准测试结果。

### Q：虚拟线程会不会导致内存溢出？

虚拟线程的栈内存远小于平台线程（KB vs MB 级别），但**并非零成本**。代码通过 `virtualThreadTimeoutMs`（默认 4000ms）限制任务堆积，超时后跳过后续 tick。在极端密集负载下，仍需关注整体内存使用。

### Q：是否与其他 Folia 插件兼容？

RegionTickPool 保留了完整的 Folia API 兼容性（`Bukkit`、`Paper`、`Folia` API 调用不受影响）。但依赖 Folia 内部调度行为（如假设 tick 严格串行的插件）可能需要验证。建议在测试环境先行验证。

---

## 八、技术参考

| 文件 | 说明 |
|------|------|
| `RegionTickPoolConfig.java` | 所有配置参数 |
| `RegionTickDispatcher.java` | 调度器主类 |
| `FoliaTickExecutor.java` | Chunk tick 执行器 |
| `AsyncCatcherManager.java` | 异步捕手引用计数 |
| `AsyncChunkAccessor.java` | 跨线程 Chunk 访问代理 |
| `WorkStealingCoordinator.java` | 工作窃取算法 |
| `VirtualThreadPool.java` | 虚拟线程池 |
| `BlockingTaskIsolation.java` | 阻塞操作隔离 |
| `RegionTickBootstrap.java` | 启动入口 |

---

*本文档基于 Mili 项目源码分析撰写，所有性能数字均为理论推导。我们鼓励社区成员在各自环境中进行基准测试并分享结果，以帮助改进 RegionTickPool。*

*— Mili Development Team*
