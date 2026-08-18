# Mili API 设计指南

## 1. API 分层

### 1.1 公共 API（lmili-api 模块）
- 位置：`fun.bm.mili.lmili.api`
- 用途：供外部插件开发者使用
- 要求：
  - 所有公共类/方法必须有 Javadoc
  - 使用 `@NotNull`/`@Nullable` 标注参数和返回值
  - 保持向后兼容性（不删除/修改已有方法签名）
  - 新增 API 需要经过审查

### 1.2 内部 API（lmili-server 模块）
- 位置：`fun.bm.mili.lmili.thread.scheduler`
- 用途：Mili 内部调度系统
- 要求：
  - 类级别 Javadoc 说明职责
  - 公共方法需要 Javadoc
  - 可以使用 `@ApiStatus.Internal` 标注内部使用

### 1.3 实现细节
- 位置：`fun.bm.mili.lmili.thread.scheduler.internal`
- 用途：不对外暴露的实现类
- 要求：包级别可见性优先，减少 public 修饰符

## 2. 包结构规范

```
fun.bm.mili
├── api/                    # Bukkit 插件 API（事件、接口）
├── lmili/
│   ├── api/                # Mili 公共 API（调度器接口）
│   ├── thread/
│   │   ├── scheduler/      # 新调度系统
│   │   │   ├── api/        # 调度器公共接口
│   │   │   ├── execute/    # 执行层实现
│   │   │   ├── internal/   # 内部实现
│   │   │   └── tick/       # Tick 相关
│   │   └── regiontick/     # 区域 tick 调度
│   ├── config/             # 配置系统
│   └── utils/              # 工具类（按子包分类）
├── chunk/                  # Chunk 处理
├── villager/               # 村民优化
└── command/                # 命令
```

## 3. Javadoc 规范

### 3.1 类级别 Javadoc
```java
/**
 * 简要描述类的职责。
 *
 * <p>详细说明（可选）
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 代码示例
 * }</pre>
 */
```

### 3.2 方法级别 Javadoc
```java
/**
 * 简要描述方法功能。
 *
 * @param param 参数说明
 * @return 返回值说明
 * @throws ExceptionType 异常条件
 */
```

### 3.3 必须标注的注解
- `@NotNull` - 参数/返回值不能为 null
- `@Nullable` - 参数/返回值可以为 null
- `@ApiStatus.Internal` - 内部 API，不建议外部使用

## 4. 重构后的模块清单

| 模块 | 位置 | 状态 |
|------|------|------|
| Utils 重组 | `utils/` → 8 个子包 | ✅ 完成 |
| Dispatcher 拆分 | `regiontick/` → 5 个组件 | ✅ 完成 |
| Chunk 管线 | `chunk/phase/` | ✅ 完成 |
| Villager 模块化 | `villager/` → 3 个组件 | ✅ 完成 |
| 配置系统统一 | `config/modules/` | ✅ 完成 |
| DAG 调度核心 | `dag/`, `executor/` | ✅ 完成 |

## 5. 待清理事项

- [ ] 删除旧工具类 (`ChunkHotnessUpdater`, `ChunkLifecycleManager`, `ChunkViewDistanceOptimizer`)
- [ ] 移除旧 `lmili/thread/regiontick/api` 包残留
- [ ] 统一 scheduler 模块和 regiontick 模块的职责边界
- [ ] 补充单元测试覆盖核心模块
