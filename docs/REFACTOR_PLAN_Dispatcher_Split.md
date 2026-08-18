# RegionTickDispatcher 上帝类拆分方案

## 现状
`RegionTickDispatcher` (546行) 同时承担 6 项职责：
1. Worker Pool 生命周期管理
2. Region 注册/注销
3. Chunk Tick 分派（3 种模式）
4. Entity Tick 分派
5. Async Catcher 引用计数
6. 统计与诊断

## 拆分方案

### 拆分后的组件

```
regiontick/
├── RegionTickDispatcher.java          # 精简后的协调者 (~150行)
├── WorkerPoolManager.java             # Worker 池生命周期管理
├── ChunkTickDispatcher.java           # Chunk Tick 分派策略
├── EntityTickDispatcher.java          # Entity Tick 分派
├── AsyncCatcherManager.java           # Async Catcher 引用计数
└── RegionDiagnostics.java             # 统计与诊断
```

### 1. WorkerPoolManager（~80行）
**职责**：管理 virtual/platform 线程池生命周期

```java
public final class WorkerPoolManager {
    private final ExecutorService workerPool;
    private final boolean useVirtualThreads;
    private final RegionTickWorker[] workers;
    
    public WorkerPoolManager(int workerCount, boolean useVirtualThreads) { ... }
    public ExecutorService getExecutor() { return workerPool; }
    public boolean isVirtualThreadMode() { return useVirtualThreads; }
    public void shutdown() { ... }
}
```

### 2. ChunkTickDispatcher（~200行）
**职责**：Chunk tick 分派策略（单线程/virtual/platform）

```java
public final class ChunkTickDispatcher {
    private final WorkerPoolManager poolManager;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkFutures;
    
    public ChunkTickDispatcher(WorkerPoolManager poolManager, int threshold, int sliceSize) { ... }
    public void dispatch(RegionTickContext context, long tickCount) { ... }
    private void dispatchSingleSlice(...) { ... }
    private void dispatchParallelVirtual(...) { ... }
    private void dispatchParallelPlatform(...) { ... }
    public void unregister(long regionId) { ... }
}
```

### 3. EntityTickDispatcher（~100行）
**职责**：Entity tick 分派 + 慢实体诊断

```java
public final class EntityTickDispatcher {
    private final ConcurrentLinkedQueue<String> slowEntities = new ConcurrentLinkedQueue<>();
    
    public void dispatch(long regionId, RegionTickContext context, 
                         ServerLevel level, RegionizedWorldData data) { ... }
    private void recordSlowEntity(...) { ... }
    private void trimSlowEntityLog() { ... }
}
```

### 4. AsyncCatcherManager（~60行）
**职责**：跨 region 的 async catcher 引用计数

```java
public final class AsyncCatcherManager {
    private final AtomicInteger refCount = new AtomicInteger(0);
    private final AtomicBoolean baseline = new AtomicBoolean(false);
    
    public void acquire() { ... }  // 计数+1，首次记录基线并强制启用
    public void release() { ... }   // 计数-1，归零恢复基线
    public boolean isEnabled() { ... }
}
```

### 5. RegionDiagnostics（~80行）
**职责**：统计与诊断信息收集

```java
public final class RegionDiagnostics {
    private final LongAdder totalTicks = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final AtomicLong maxTickDuration = new AtomicLong(0);
    
    public void recordTick(long durationNanos) { ... }
    public void recordError() { ... }
    public Map<String, Object> getStats() { ... }
}
```

### 精简后的 RegionTickDispatcher（~150行）
**职责**：协调各组件，对外保持原有接口

```java
public final class RegionTickDispatcher {
    private final WorkerPoolManager poolManager;
    private final ChunkTickDispatcher chunkDispatcher;
    private final EntityTickDispatcher entityDispatcher;
    private final AsyncCatcherManager asyncCatcherManager;
    private final RegionDiagnostics diagnostics;
    private final ModernDagTickExecutor dagExecutor;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts;
    
    // 保持原有外部接口不变
    public void dispatchTick(RegionTickContext ctx, long tick) { chunkDispatcher.dispatch(ctx, tick); }
    public void dispatchEntityTick(...) { entityDispatcher.dispatch(...); }
    public Map<String, Object> getStats() { ... }
}
```

## 实施步骤

1. 创建 4 个新类 + 迁移逻辑
2. 精简 RegionTickDispatcher 为协调者
3. 保持外部接口向后兼容
4. 更新 RegionTickBootstrap 引用
5. 验证编译

## 向后兼容
精简后的 RegionTickDispatcher 保持所有原有公共方法签名不变，内部委托给子组件。
