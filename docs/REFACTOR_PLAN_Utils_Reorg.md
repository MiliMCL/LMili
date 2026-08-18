# Utils 包重组方案

## 现状
`fun.bm.mili.utils` 包包含 25 个功能各异的工具类，变成了"无所不包"的垃圾袋。

## 分类方案

### utils.performance（性能监控与优化）
| 类名 | 功能 |
|------|------|
| AdaptiveTPSManager | TPS 自适应管理 |
| TPSTracker | TPS 追踪 |
| PerformanceCollector | 性能指标收集 |
| MemoryOptimizer | 内存优化 |
| LagRemover | 滞后清理（实体清理） |

### utils.network（网络优化）
| 类名 | 功能 |
|------|------|
| AsyncKeepaliveManager | 异步 Keepalive |
| NetworkOptimizer | 网络优化 |

### utils.entity（实体相关）
| 类名 | 功能 |
|------|------|
| EntitiesCounterUtil | 实体计数 |
| EntityDensityTracker | 实体密度追踪 |
| EntityDirtyTracker | 实体脏状态追踪 |
| AsyncPathfinder | 异步寻路 |

### utils.chunk（区块相关）
| 类名 | 功能 |
|------|------|
| ChunkDeltaCompressor | 区块增量压缩 |
| FertilizableCoral | 珊瑚施肥（世界生成相关） |

### utils.region（区域/负载均衡）
| 类名 | 功能 |
|------|------|
| RegionBalancer | 区域均衡 |
| RegionLoadMonitor | 区域负载监控 |
| SmartRegionManager | 智能区域管理 |
| RegionTaskIdRegistry | 区域任务 ID 注册 |
| DynamicViewDistanceManager | 动态视距 |

### utils.player（玩家相关）
| 类名 | 功能 |
|------|------|
| PlayerHeatmap | 玩家热力图 |
| ServerI18nUtil | 服务器国际化 |
| RandomProfilePool | 随机玩家资料池（Replay API 用） |

### utils.portal（传送门/跨维度）
| 类名 | 功能 |
|------|------|
| CrossDimensionTeleportQueue | 跨维度传送队列 |
| ReturnPortalManager | 返回传送门管理 |

### utils.misc（其他杂项）
| 类名 | 功能 |
|------|------|
| CrossRegionHelper | 跨区域辅助 |
| LightCallbackManager | 光照回调管理 |
| SaveAllUtil | SaveAll 命令工具 |

## 实施步骤

1. 创建子目录 + 移动文件（仅改 package 声明）
2. 全局替换 import（58+ 文件）
3. 验证编译

## 风险评估
- 低风险：纯重组，不改逻辑
- 注意：CrossRegionHelper、SmartRegionManager 有较多引用，需全部更新
