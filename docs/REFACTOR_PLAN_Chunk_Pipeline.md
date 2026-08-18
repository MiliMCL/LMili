# Chunk 处理管线统一方案

## 现状
`fun.bm.mili.chunk` 包包含 8 个类，职责分散在静态方法中：

| 类 | 职责 | 问题 |
|---|---|---|
| MiliChunkSystem | 主入口 + 所有逻辑 | 静态上帝类 |
| AsyncChunkProcessor | 异步操作队列 | 被 MiliChunkSystem 直接调用 |
| ChunkHotness | 热度评分 | 数据类 |
| ChunkHotnessUpdater | 热度更新 | 静态工具类 |
| ChunkKey | 区块键 | 数据类 |
| ChunkLifecycleManager | 生命周期管理 | 静态工具类 |
| ChunkViewDistanceOptimizer | 视距优化 | 静态工具类 |
| WorldChunkData | 世界区块数据 | 被多个类直接操作 |

## 重构目标
1. 建立清晰的 chunk 生命周期管线
2. 消除静态上帝类
3. 各阶段可独立测试和替换

## 新架构

```
chunk/
├── ChunkPipeline.java              # 管线协调器（替代 MiliChunkSystem 的静态方法）
├── WorldChunkData.java             # 保留，作为数据容器
├── ChunkHotness.java               # 保留，热度数据
├── ChunkKey.java                   # 保留，键工具
├── phase/                          # 生命周期阶段
│   ├── ChunkPhase.java             # 阶段接口
│   ├── HotnessUpdatePhase.java     # 热度更新阶段
│   ├── ViewDistancePhase.java      # 视距优化阶段
│   └── LifecyclePhase.java         # 生命周期（卸载）阶段
├── async/                          # 异步处理
│   ├── AsyncChunkProcessor.java    # 保留，异步处理器
│   └── AsyncChunkOperation.java    # 保留，操作抽象
└── internal/                       # 内部实现
    └── ChunkStats.java             # 统计信息
```

### ChunkPipeline 接口
```java
public interface ChunkPipeline {
    void start();
    void stop();
    void registerWorld(World world);
    void unregisterWorld(World world);
    void tick();  // 每 tick 调用
}
```

### ChunkPhase 接口
```java
public interface ChunkPhase {
    String getName();
    void execute(World world, WorldChunkData data);
}
```

## 实施步骤

1. 创建 `ChunkPhase` 接口
2. 将 `ChunkHotnessUpdater` → `HotnessUpdatePhase`
3. 将 `ChunkViewDistanceOptimizer` → `ViewDistancePhase`
4. 将 `ChunkLifecycleManager` → `LifecyclePhase`
5. 创建 `ChunkPipeline` 实现类
6. 精简 `MiliChunkSystem` 为薄入口
7. 更新 `MiliOptimizations` 引用

## 向后兼容
`MiliChunkSystem.init()` / `shutdown()` 保持为静态入口，内部委托给 `ChunkPipeline` 实例。
