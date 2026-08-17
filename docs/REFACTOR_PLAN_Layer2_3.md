# Mili Layer 2 + Layer 3 重构方案

## 1. 现状分析

### 1.1 当前架构概览

```
RegionTickDispatcher (调度入口)
    ├── dispatchTick() → 根据 chunk 数量选择执行模式
    │   ├── dispatchSingleSlice() → 小 region 同步执行
    │   ├── dispatchParallelVirtual() → Virtual Thread 并行
    │   └── dispatchParallelPlatform() → Platform Thread 并行
    ├── dispatchEntityTick() → 实体 tick（同步）
    └── DagBasedTickExecutor → DAG 系统调度
        ├── RegionDag (DAG 数据结构)
        ├── ConflictDetector (冲突检测)
        ├── SystemProfile (系统声明)
        └── RegionDagExecutor (DAG 执行引擎)

VirtualThreadScheduler (虚拟线程管理)
    ├── submitVirtual() → 提交虚拟线程任务
    ├── computeBlocking() → 阻塞操作隔离
    ├── StructuredScope → 结构化并发
    └── EntitySchedulerImpl → 实体调度
```

### 1.2 识别的性能问题

#### Layer 2 (DAG 调度) 问题

| 问题 | 位置 | 影响 |
|------|------|------|
| CountDownLatch 阻塞 | RegionDagExecutor.executeDag() | region tick 线程被阻塞等待所有节点完成，无法做有用工作 |
| O(n²) 冲突矩阵 | ConflictDetector.buildConflictMatrix() | 内存浪费，系统数量多时构建缓慢 |
| 每 tick 新建执行器 | DagBasedTickExecutor.executeSystemsInternal() | GC 压力，每次 tick 创建 RegionDagExecutor + HashMap |
| int[] 邻接表 | RegionDag.adjacencyList | 稀疏图内存浪费，动态扩容开销 |
| ForkJoinPool 混用 | SHARED_DAG_POOL | 与 Virtual Thread 模型不匹配，无法利用 VT 轻量级优势 |
| 缓存失效过激 | DagBasedTickExecutor.getOrBuildDag() | 每次注册/注销系统都清空缓存 |

#### Layer 3 (Virtual Thread) 问题

| 问题 | 位置 | 影响 |
|------|------|------|
| 单例模式 | VirtualThreadScheduler.INSTANCE | 无法测试，无法多实例隔离 |
| CallerRunsPolicy | BLOCKING_TASK_POOL | 可能导致 virtual thread 执行阻塞操作，引发 carrier pinning |
| 嵌套提交 | StructuredScope.forkWithTimeout() | 浪费一个线程等待超时，线程资源翻倍 |
| 固定超时 | StructuredScope.close() | 100ms 硬编码，不适应不同场景 |
| 无 work-stealing | 整体设计 | 各 region 独立调度，无法平衡负载 |
| regionId 捕获 | EntitySchedulerImpl 构造函数 | 实体移动后 regionId 过时 |

#### 跨层问题

| 问题 | 影响 |
|------|------|
| RegionTickDispatcher 职责过重 | 同时处理 chunk tick、entity tick、DAG 系统、Async Catcher |
| Async Catcher 引用计数脆弱 | 同步块 + 原子计数，异常路径容易遗漏 |
| 调度策略与执行机制耦合 | 无法独立替换调度算法或执行器实现 |

---

## 2. 重构目标

### 2.1 性能目标

- **零阻塞调度**：region tick 线程永不阻塞等待子任务完成
- **无锁热路径**：tick 分发和任务提交无锁（或最小锁）
- **分配友好**：热路径零分配或对象池复用
- **负载均衡**：支持 work-stealing，自动平衡各 region 负载

### 2.2 设计目标

- **关注点分离**：调度策略、执行机制、资源管理独立模块
- **可测试性**：所有组件可独立单元测试
- **可观测性**：内置性能指标和诊断接口
- **渐进式替换**：新旧实现可共存，逐步迁移

---

## 3. 新架构设计

### 3.1 核心接口定义

```
┌─────────────────────────────────────────────────────────────────┐
│                     MiliScheduler (Public API)                    │
├─────────────────────────────────────────────────────────────────┤
│  + submit(RegionTask) -> TaskHandle                              │
│  + submitBatch(List<RegionTask>) -> BatchHandle                  │
│  + scheduleDelayed(task, delay, unit) -> ScheduledHandle         │
│  + forEntity(entity) -> EntityScheduler                          │
│  + shutdown()                                                     │
└─────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐   ┌─────────────────┐   ┌──────────────────┐
│  RegionQueue   │   │  WorkStealing   │   │  VirtualThread   │
│  (per-region)  │   │  Coordinator    │   │  Pool            │
│                │   │                 │   │  (carrier mgmt)  │
└───────────────┘   └─────────────────┘   └──────────────────┘
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DagExecutionEngine                             │
├─────────────────────────────────────────────────────────────────┤
│  + compile(systemGraph) -> CompiledDag                           │
│  + execute(compiledDag, context) -> TaskHandle                   │
│  + detectConflicts(systemGraph) -> ConflictGraph                 │
└─────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐   ┌─────────────────┐   ┌──────────────────┐
│  ConflictGraph │   │  CompiledDag    │   │  ExecutionContext│
│  (sparse)      │   │  (immutable)    │   │  (per-tick pool) │
└───────────────┘   └─────────────────┘   └──────────────────┘
```

### 3.2 模块划分

```
mili-scheduler/                    # 新模块：调度核心
├── api/                           # 公共 API
│   ├── MiliScheduler.java         # 调度器主接口
│   ├── TaskHandle.java            # 任务句柄
│   ├── EntityScheduler.java       # 实体调度接口
│   └── RegionTask.java            # 区域任务抽象
├── dag/                           # Layer 2: DAG 调度
│   ├── DagExecutionEngine.java    # DAG 执行引擎
│   ├── CompiledDag.java           # 编译后的 DAG（不可变）
│   ├── ConflictGraph.java         # 稀疏冲突图
│   ├── SystemGraph.java           # 系统依赖图
│   ├── SystemProfile.java         # 系统声明（保留优化）
│   └── ConflictDetector.java      # 冲突检测（优化版）
├── execute/                       # Layer 3: 执行层
│   ├── VirtualThreadPool.java     # 虚拟线程池管理
│   ├── WorkStealingCoordinator.java # 工作窃取协调器
│   ├── RegionQueue.java           # 区域任务队列
│   ├── BlockingTaskIsolation.java # 阻塞任务隔离
│   └── StructuredScope.java       # 结构化并发（优化版）
├── internal/                      # 内部实现
│   ├── ObjectPool.java            # 对象池
│   ├── PerformanceMetrics.java    # 性能指标
│   └── DiagnosticCollector.java   # 诊断收集
└── MiliSchedulerBuilder.java      # 构建器
```

---

## 4. 关键设计决策

### 4.1 非阻塞 DAG 执行

**问题**：当前使用 CountDownLatch 阻塞 region tick 线程。

**方案**：使用 CompletableFuture 链式调度 + 回调驱动。

```java
// 新设计：节点完成后自动触发后继，无需阻塞等待
public final class DagExecutionEngine {
    
    public TaskHandle execute(CompiledDag dag, ExecutionContext ctx) {
        // 入度为 0 的节点立即提交
        // 每个节点完成后，通过回调递减后继入度
        // 当入度降为 0 时自动提交
        // 返回 TaskHandle 用于追踪整体完成状态（非阻塞）
        
        TaskHandle handle = ctx.acquireTaskHandle(dag.nodeCount());
        
        for (int nodeId : dag.readyNodes()) {
            submitNode(dag, ctx, nodeId, handle);
        }
        
        return handle; // 立即返回，不阻塞
    }
    
    private void submitNode(CompiledDag dag, ExecutionContext ctx, 
                           int nodeId, TaskHandle handle) {
        ctx.executor().execute(() -> {
            try {
                dag.executor(nodeId).accept(ctx);
            } finally {
                // 完成后触发后继检查
                for (int succ : dag.successors(nodeId)) {
                    if (dag.decrementRemaining(succ) == 0) {
                        submitNode(dag, ctx, succ, handle);
                    }
                }
                handle.signalCompletion(nodeId);
            }
        });
    }
}
```

### 4.2 稀疏冲突图

**问题**：当前使用 boolean[n][n] 矩阵，O(n²) 空间。

**方案**：只存储实际冲突的边。

```java
public final class ConflictGraph {
    // 邻接表：只存储有冲突的节点对
    private final Int2ObjectMap<IntSet> adjacency;
    
    public ConflictGraph buildFrom(SystemGraph systems) {
        // 使用空间分区加速：按 chunk 位置分组
        // 只有相同 chunk 上的系统才可能冲突
        // 大幅减少需要检测的配对数量
    }
}
```

### 4.3 Work-Stealing 调度

**问题**：当前各 region 独立调度，无法平衡负载。

**方案**：全局 work-stealing deque + 本地 region queue。

```java
public final class WorkStealingCoordinator {
    // 每个 worker 有自己的本地 deque
    private final WorkStealer[] workers;
    
    // 窃取逻辑：当本地队列为空时，随机选择其他 worker 窃取
    public RegionTask stealWork(WorkStealer thief) {
        int randomIdx = ThreadLocalRandom.current().nextInt(workers.length);
        WorkStealer victim = workers[randomIdx];
        return victim.stealFrom(); // 从 victim 的 deque 顶部窃取
    }
}
```

### 4.4 阻塞操作隔离

**问题**：CallerRunsPolicy 可能导致 virtual thread 执行阻塞操作。

**方案**：专用阻塞线程池 + 信号量限流。

```java
public final class BlockingTaskIsolation {
    // 专用平台线程池，大小固定
    private final Semaphore blockingPermits;
    
    public <T> CompletableFuture<T> submitBlocking(Callable<T> task) {
        if (Thread.currentThread().isVirtual()) {
            // Virtual thread 路径：获取 permit 后在平台线程执行
            return CompletableFuture.supplyAsync(() -> {
                blockingPermits.acquire();
                try {
                    return task.call();
                } finally {
                    blockingPermits.release();
                }
            }, platformExecutor);
        } else {
            // 已在平台线程，直接执行
            return CompletableFuture.completedFuture(task.call());
        }
    }
}
```

### 4.5 对象池复用

**问题**：每 tick 创建大量临时对象（RegionDagExecutor、HashMap、CompletableFuture 数组）。

**方案**：ThreadLocal 对象池 + 可重用 ExecutionContext。

```java
public final class ExecutionContext {
    // ThreadLocal 池，避免跨线程同步
    private static final ThreadLocal<Pool> POOL = ThreadLocal.withInitial(Pool::new);
    
    // 可重用的执行上下文
    private final List<CompletableFuture<Void>> scratchList;
    private final Map<String, Object> scratchMap;
    
    public static ExecutionContext acquire() {
        return POOL.get().acquire();
    }
    
    public void release() {
        // 清理并归还池中
        scratchList.clear();
        scratchMap.clear();
        POOL.get().release(this);
    }
}
```

---

## 5. 实施计划

### Phase 1: 基础设施（1-2 天）

1. 创建 `mili-scheduler` 模块骨架
2. 定义核心接口（MiliScheduler, TaskHandle, RegionTask）
3. 实现 ObjectPool 和 ExecutionContext
4. 实现 PerformanceMetrics 和 DiagnosticCollector

### Phase 2: Layer 2 核心（2-3 天）✅ 已完成

1. ✅ 实现 ConflictGraph（稀疏冲突图）— `ConflictGraph.java`
2. ✅ 实现 CompiledDag（不可变 DAG）— `CompiledDag.java`
3. ✅ 实现 DagExecutionEngine（非阻塞执行）— `DagExecutionEngine.java`
4. ✅ 实现 SystemGraph（系统依赖图）— `SystemGraph.java`
5. ✅ 保留 SystemProfile 和 ResourceType（已有实现良好）
6. ✅ 创建 ModernDagTickExecutor 适配器 — `ModernDagTickExecutor.java`

**实现文件：**
- `lmili-server/src/main/java/fun/bm/mili/lmili/thread/regiontick/dag/ConflictGraph.java`
- `lmili-server/src/main/java/fun/bm/mili/lmili/thread/regiontick/dag/CompiledDag.java`
- `lmili-server/src/main/java/fun/bm/mili/lmili/thread/regiontick/dag/SystemGraph.java`
- `lmili-server/src/main/java/fun/bm/mili/lmili/thread/regiontick/executor/DagExecutionEngine.java`
- `lmili-server/src/main/java/fun/bm/mili/lmili/thread/regiontick/executor/ModernDagTickExecutor.java`

### Phase 3: Layer 3 核心（2-3 天）

1. 实现 VirtualThreadPool（替代单例）
2. 实现 WorkStealingCoordinator
3. 实现 RegionQueue
4. 实现 BlockingTaskIsolation
5. 优化 StructuredScope

### Phase 4: 集成与迁移（1-2 天）

1. 实现 MiliSchedulerBuilder
2. 创建适配器连接现有 RegionTickDispatcher
3. 灰度切换：配置开关选择新旧实现
4. 性能基准测试

### Phase 5: 清理（1 天）

1. 移除旧实现
2. 更新文档
3. 清理废弃代码

---

## 6. 性能基准

### 6.1 关键指标

| 指标 | 当前 | 目标 |
|------|------|------|
| DAG 构建时间（10 系统） | ~50μs | ~10μs |
| DAG 执行调度延迟 | ~100μs（CountDownLatch 阻塞） | ~10μs（回调驱动） |
| 每 tick 分配量 | ~5KB | ~500B（对象池复用） |
| 阻塞操作隔离延迟 | ~1ms（CallerRuns 排队） | ~100μs（专用池） |
| 负载均衡效率 | 无（固定分配） | work-stealing 自动均衡 |

### 6.2 测试方法

1. **微基准**：JMH 测试 DAG 构建/执行延迟
2. **集成基准**：模拟 100+ region 并发 tick
3. **压力测试**：1000+ 实体场景下的 tick 稳定性

---

## 7. 风险与缓解

| 风险 | 概率 | 缓解措施 |
|------|------|----------|
| Virtual Thread 调度器行为变化 | 中 | 保留 Platform Thread 回退路径 |
| Work-stealing 开销超过收益 | 低 | 可配置开关，监控窃取成功率 |
| 对象池泄漏 | 低 | 使用 PhantomReference 追踪 |
| 与现有补丁系统冲突 | 中 | 新模块独立，通过 API 交互 |
