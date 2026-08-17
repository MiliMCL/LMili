# Mili 补丁维护指南

## 概述

本文档描述了 Mili 项目的补丁管理系统，以及如何维护这些补丁以适应上游（Paper/Folia）的版本更新。

## 补丁结构

### 目录布局

```
lmili-server/
├── minecraft-patches/
│   ├── features/              # 原始补丁（参考用，不再直接使用）
│   └── merged-v3/             # 合并后的补丁（构建使用）
│       ├── 01-rebrand-consolidated.patch
│       ├── 02-config-system-consolidated.patch
│       ├── 03-entity-optimizations-consolidated.patch
│       ├── 04-chunk-region-consolidated.patch
│       └── 06-misc-consolidated.patch
├── paper-patches/
│   ├── features/              # 原始补丁
│   └── merged-v3/             # 合并后的补丁
│       ├── 01-rebrand-consolidated.patch
│       ├── 02-config-system-consolidated.patch
│       ├── 03-entity-optimizations-consolidated.patch
│       ├── 04-chunk-region-consolidated.patch
│       └── 06-misc-consolidated.patch
└── lophine-patches/
    └── features/
        └── 0001-Rebrand-to-Mili.patch  # 独立补丁，不合并

lmili-api/
└── paper-patches/
    ├── features/              # 原始补丁
    └── merged-v3/             # 合并后的补丁
        ├── 01-rebrand-consolidated.patch
        ├── 03-entity-optimizations-consolidated.patch
        ├── 04-chunk-region-consolidated.patch
        └── 06-misc-consolidated.patch
```

### 功能域说明

| 补丁文件 | 功能域 | 说明 |
|---------|--------|------|
| `01-rebrand-consolidated.patch` | 品牌重命名 | Luminol → Mili 的品牌替换、包名修改 |
| `02-config-system-consolidated.patch` | 配置系统 | 所有 `fun.bm.mili.lmili.config` 相关的配置选项 |
| `03-entity-optimizations-consolidated.patch` | 实体优化 | 实体 tick、AI、移动、生成等优化 |
| `04-chunk-region-consolidated.patch` | 区块与区域 | 区块加载、区域调度、网络优化 |
| `05-fixes-consolidated.patch` | 编译修复 | 编译错误修复、API 迁移 |
| `06-misc-consolidated.patch` | 杂项 | 不属于以上分类的其他修改 |

## 工作流程

### 日常开发

1. **修改源码**：在 `lmili-server/src/minecraft/java/` 下修改生成源码
2. **提交修改**：`git commit -m "描述"`
3. **重建补丁**：`./gradlew :mili-server:rebuildAllServerPatches`
4. **提交补丁**：将重新生成的补丁文件提交到 `merged-v3/` 目录

### 添加新功能/配置

1. 确定功能所属的功能域
2. 在 `src/minecraft/java/` 中修改对应文件
3. 运行重建补丁命令
4. 补丁会自动更新到对应的 `merged-v3/` 文件中

### 上游版本升级

当 Paper/Folia 发布新版本时：

1. **更新 `foliaRef`**：修改 `gradle.properties` 中的 `foliaRef` 值为新的 commit hash
2. **清理缓存**：`./gradlew clean`
3. **重新应用补丁**：`./gradlew applyAllPatches --no-configuration-cache --no-build-cache`
4. **解决冲突**：
   - 如果补丁应用失败，会显示冲突文件
   - 手动编辑冲突文件，保留 Mili 的修改
   - 使用 `git add` 标记冲突已解决
5. **验证编译**：`./gradlew :mili-server:compileJava`
6. **重建补丁**：`./gradlew :mili-server:rebuildAllServerPatches`
7. **提交更新**：将更新后的补丁文件提交

### 冲突解决策略

当补丁无法自动应用时：

1. **优先保留 Mili 的修改**：Mili 的核心功能（配置系统、优化）必须保留
2. **检查上游是否已包含**：有些优化可能已被上游采纳，可以移除
3. **调整行号**：如果只是行号偏移，手动调整即可
4. **重构修改**：如果上游改动较大，可能需要重构 Mili 的修改方式

## 补丁合并脚本

### 重新合并补丁

如果需要重新合并补丁（例如添加了大量新补丁后）：

```bash
# 合并 minecraft-patches
python scripts/merge_patches_v3.py \
    lmili-server/minecraft-patches/features \
    lmili-server/minecraft-patches/merged-v3

# 合并 paper-patches
python scripts/merge_patches_v3.py \
    lmili-server/paper-patches/features \
    lmili-server/paper-patches/merged-v3

# 合并 lmili-api patches
python scripts/merge_patches_v3.py \
    lmili-api/paper-patches/features \
    lmili-api/paper-patches/merged-v3
```

### 脚本参数

- `<patch_directory>`：原始补丁目录（`features/`）
- `[output_directory]`：合并后补丁输出目录（默认为 `../merged-v3`）

## 最佳实践

1. **保持补丁整洁**：每个修改应该有清晰的注释标记（`// Mili start` / `// Mili end`）
2. **避免过度修改**：只修改必要的代码，减少冲突概率
3. **及时更新**：上游发布新版本后尽快更新，避免积压
4. **测试验证**：每次补丁更新后运行完整测试套件
5. **文档记录**：重要的架构决策记录在 CODEBUDDY.md 中

## 常见问题

### Q: 补丁应用失败怎么办？

A: 运行 `./gradlew applyAllPatches` 时会显示失败的具体文件和原因。常见原因：
- 行号偏移：手动调整或使用 `git apply --3way` 自动合并
- 上下文不匹配：检查上游是否修改了相同区域
- 文件已删除：上游可能重构了代码结构

### Q: 如何判断某个优化是否已被上游包含？

A: 对比上游源码和 Mili 的修改。如果上游已实现相同功能，可以移除 Mili 的补丁。

### Q: 合并补丁后如何验证正确性？

A:
1. 在干净源码上应用合并补丁：`git apply 01-rebrand-consolidated.patch` 等
2. 编译验证：`./gradlew :mili-server:compileJava`
3. 运行测试：`./gradlew :mili-server:test`

### Q: 如何回滚到原始补丁？

A: 原始补丁保留在 `features/` 目录中。如需回滚：
1. 将 `features/` 目录复制回 `merged-v3/`
2. 重新命名以匹配构建系统期望的格式
