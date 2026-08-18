# Villager 优化器模块化方案

## 现状
`VillagerOptimizer` (515行) 承担 6 项职责：
1. 事件监听 (Listener)
2. 村民状态管理 (active/inactive sets)
3. AI 控制 (lobotomize/activate)
4. 交易保护 (inventory events)
5. Chunk 处理 (processChunks)
6. 补货逻辑 (restock)

## 拆分方案

```
villager/
├── VillagerOptimizer.java          # 精简后的协调者 (~100行)
├── VillagerEventHandler.java       # 事件监听器 (~150行)
├── VillagerAIController.java       # AI 状态控制 (~120行)
├── VillagerRestockProcessor.java   # 补货逻辑 (~100行)
├── VillagerActivityPolicy.java     # 保留
├── VillagerState.java              # 保留
├── BlockClassifier.java            # 保留
└── BlockGrid.java                  # 保留
```

### 拆分后职责

**VillagerOptimizer** - 协调者
- 保持原有公共 API (init, shutdown, getInstance, toggleLobotomy)
- 委托事件处理给 EventHandler
- 委托 AI 控制给 AIController
- 委托补货逻辑给 RestockProcessor

**VillagerEventHandler** - 监听 Bukkit 事件
- ChunkLoad/Unload
- BlockBreak/Place (追踪 changed chunks)
- InventoryOpen/Click/Drag (交易保护)
- PlayerInteractEntity (状态显示)

**VillagerAIController** - 控制村民 AI 状态
- addVillager/removeVillager
- activate/lobotomize
- shouldBeActive 判断
- processChunks 处理循环

**VillagerRestockProcessor** - 补货逻辑
- needsToRestock/allowedToRestock
- tryRestock/doRestock
- getProfessionSound

## 向后兼容
VillagerOptimizer 保持所有原有公共方法签名不变。
