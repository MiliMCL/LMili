#!/usr/bin/env python3
"""
Mili Patch Merger - 将多个小补丁合并为功能域大补丁

策略：
1. 按功能域分组（配置、优化、修复、品牌、API等）
2. 同一文件上的多个补丁合并为一个 diff
3. 确保合并后的补丁能干净应用
4. 生成清晰的命名便于未来维护

合并规则：
- 同文件不同位置的修改 → 合并到同一个文件 diff 中
- 同文件相邻位置的修改 → 扩展 context 后合并
- 功能相关的补丁 → 归入同一个大补丁文件
"""

import os
import re
import sys
from pathlib import Path
from dataclasses import dataclass, field
from typing import List, Dict, Tuple, Optional
from collections import defaultdict


@dataclass
class Hunk:
    """表示一个 diff hunk"""
    old_start: int
    old_count: int
    new_start: int
    new_count: int
    lines: List[str] = field(default_factory=list)
    context_before: List[str] = field(default_factory=list)
    context_after: List[str] = field(default_factory=list)


@dataclass
class FileDiff:
    """表示一个文件的完整 diff"""
    old_path: str
    new_path: str
    hunks: List[Hunk] = field(default_factory=list)
    is_new: bool = False
    is_delete: bool = False
    old_mode: str = ""
    new_mode: str = ""
    similarity: int = 0


@dataclass
class Patch:
    """表示一个完整的补丁文件"""
    filename: str
    subject: str
    author: str
    date: str
    file_diffs: List[FileDiff] = field(default_factory=list)
    header_lines: List[str] = field(default_factory=list)


# 功能域分类规则（按优先级匹配）
FUNCTIONAL_DOMAINS = [
    ("01-rebrand", [
        r"rebrand", r"rename.*package", r"brand.*name", r"luminol.*mili",
        r"lophine", r"server.*mod.*name"
    ]),
    ("02-config-system", [
        r"add.*config.*for", r"config.*for",
        r"cpu.*affinity", r"offline.*mode", r"out.*of.*order.*chat",
        r"server.*mod.*name", r"unsafe.*teleport", r"username.*check",
        r"vanilla.*random", r"watchdog.*timeout", r"heightmap.*warning",
        r"packet.*limiter", r"tripwire.*behavior", r"signature.*only",
        r"command.*block.*execution", r"data.*command.*enabled",
        r"waypoint.*restoration", r"portal.*ticket", r"region.*format",
        r"language.*config", r"container.*expansion", r"redstone.*config",
        r"replay.*config", r"performance.*monitor", r"player.*heatmap",
        r"villager.*trade", r"async.*keepalive", r"cross.*region.*helper",
        r"entity.*damage.*source", r"global.*entities.*config", r"region.*balancer",
        r"region.*tick.*pool", r"disable.*async.*catcher", r"disable.*entity.*catch",
        r"folia.*supported", r"membar", r"region.*bar.*config",
        r"secure.*seed.*config", r"collision.*config", r"folia.*entity.*moving",
        r"force.*cleanup.*entity", r"item.*multitask", r"long.*command.*support",
        r"pathfinding.*fixes", r"poi.*range", r"prevent.*incorrect.*teleport",
        r"auto.*update.*config", r"disable.*warning", r"inorder.*chat",
        r"publickey.*verify", r"save.*portal.*tickets", r"sentry.*config",
        r"async.*protocol.*config", r"entity.*goal.*selector", r"gale.*variable",
        r"kaiiju.*entity.*limiter", r"leaves.*sleeping", r"lobotomize.*config",
        r"dragon.*respawn.*config", r"petal.*reduce.*config", r"projectile.*chunk.*config",
        r"disable.*timings", r"force.*disable.*reload", r"pufferfish.*sentry",
        r"pufferfish.*simd", r"chunk.*delta.*config", r"dynamic.*view.*config",
        r"lighting.*callback", r"network.*optimizer", r"entity.*density.*config",
        r"entity.*dirty.*config", r"async.*pathfinding", r"cross.*dimension.*teleport"
    ]),
    ("03-entity-optimizations", [
        r"entity.*move", r"entity.*tick", r"entity.*ai", r"entity.*brain",
        r"entity.*memory", r"entity.*target", r"entity.*riding", r"entity.*portal",
        r"entity.*teleport", r"entity.*pathfind", r"entity.*spawn",
        r"entity.*sensor", r"entity.*goal", r"entity.*wake",
        r"skip.*entity", r"lobotomize", r"villager", r"dragon.*respawn",
        r"sculk.*catalyst", r"moved.*wrongly", r"collision.*behavior",
        r"end.*portal", r"ender.*pearl", r"tamable.*animal",
        r"zero.*movement", r"planar.*movement", r"line.*of.*sight",
        r"block.*goal", r"nearby.*alive", r"criterion.*map",
        r"brain.*map", r"visible.*effects", r"ai.*attribute",
        r"remove.*stream", r"check.*targeting", r"canSee",
        r"distanceToSqr", r"negligible", r"spawn.*event",
        r"zombie.*reinforcement", r"stats.*json", r"creative.*item",
        r"respawn.*place", r"riding.*statistic",
        r"volatile.*reference", r"debug.*subscription", r"chunk.*reload",
        r"projectile.*chunk", r"reduce.*sensor", r"throttle.*goal",
        r"reduce.*chunk.*loading", r"reduce.*projectile",
        r"do.*not.*pathfind", r"do.*not.*setTarget", r"do.*not.*fire",
        r"do.*not.*enable", r"do.*not.*load", r"fix.*entity.*memory",
        r"fix.*riding", r"fix.*chunk.*reload", r"fix.*sculk",
        r"fix.*dragon", r"fix.*ender.*pearl", r"fix.*off.*region",
        r"fix.*tamable", r"fix.*misbehaved", r"fix.*possible.*delay",
        r"fix.*unpatched", r"fix.*creative", r"fix.*portal.*speed",
        r"prevent.*teleport", r"sync.*dragon", r"correct.*volatile",
        r"correct.*player.*respawn", r"stop.*riding", r"remove.*useless",
        r"replace.*brain", r"replace.*criterion", r"reduce.*worldgen",
        r"reduce.*block.*destruction",
        r"optimize.*nearby", r"optimize.*canSee", r"skip.*distance",
        r"skip.*negligible", r"initialize.*line.*of.*sight",
        r"check.*targeting.*range", r"block.*goal.*chunk",
        r"remove.*stream.*block", r"some.*optimizations.*krypton",
        r"do.*not.*load.*poi", r"global.*entities.*counter",
        r"null.*check.*regionized", r"removed.*check.*addEffect",
        r"fix.*compilation.*PaperHooks", r"fix.*compilation.*MC",
        r"rendering.*optimization", r"long.*command.*support",
        r"async.*protocol"
    ]),
    ("04-chunk-region", [
        r"chunk.*system", r"chunk.*ticket", r"chunk.*loading",
        r"chunk.*send", r"chunk.*format", r"region.*format",
        r"tick.*regions.*api", r"linear.*region",
        r"region.*balancer", r"region.*tick", r"cross.*region",
        r"cross.*dimension", r"async.*chunk", r"dynamic.*view",
        r"chunk.*delta", r"lighting.*callback", r"network.*optimizer",
        r"entity.*density", r"entity.*dirty", r"async.*pathfinding",
        r"portal.*rate", r"waypoint.*restoration", r"save.*portal.*ticket",
        r"threading.*utilities", r"disable.*async.*catcher",
        r"disable.*entity.*catch", r"folia.*supported.*check",
        r"block.*pos.*transform", r"cancel.*task.*by.*task",
        r"add.*missing.*teleportation.*event", r"replay.*mod.*api",
        r"bytebuf.*api", r"photographer", r"packet.*event",
        r"player.*operation.*limit", r"bukkit.*event",
        r"leaves.*event", r"leaves.*bytebuf", r"leaves.*replay",
        r"leaves.*photographer", r"end.*platform.*create",
        r"portal.*locate", r"entity.*teleport.*async",
        r"pre.*entity.*portal", r"post.*entity.*portal",
        r"post.*player.*respawn", r"region.*stats",
        r"threaded.*region", r"tick.*region.*data",
        r"secure.*seed.*command", r"matter.*seed.*command",
        r"read.*only.*datapack", r"force.*disable.*spark",
        r"server.*health", r"improved.*server"
    ]),
    ("05-fixes", [
        r"fix", r"correct.*player", r"prevent.*teleport",
        r"disable.*warning", r"force.*disable.*spark",
        r"server.*health", r"improved.*server",
        r"disable.*timings", r"force.*disable.*reload",
        r"pufferfish.*sentry", r"pufferfish.*simd",
        r"add.*null.*check", r"add.*removed.*check",
        r"fix.*compilation", r"comment.*out.*protected",
        r"sync.*dragon", r"correct.*volatile"
    ]),
    ("06-features-api", [
        r"tpsbar", r"chunkhot", r"membar", r"regionbar",
        r"secure.*seed", r"matter.*seed", r"data.*command",
        r"read.*only.*datapack", r"command.*block",
        r"replay.*api", r"bytebuf", r"photographer",
        r"packet.*event", r"bukkit.*event", r"leaves.*event",
        r"region.*format", r"linear.*v2", r"tick.*regions.*api",
        r"waypoint.*restoration", r"portal.*rate.*limiter",
        r"global.*entities.*counter", r"cancel.*task.*by.*task",
        r"add.*missing.*teleportation", r"block.*pos.*transform",
        r"barrels.*enderchests", r"skip.*event.*no.*listeners",
        r"rendering.*optimization", r"flightrecorder"
    ]),
]


def classify_patch(subject: str) -> str:
    """根据补丁主题分类到功能域"""
    subject_lower = subject.lower()
    for domain_name, patterns in FUNCTIONAL_DOMAINS:
        for pattern in patterns:
            if re.search(pattern, subject_lower):
                return domain_name
    return "07-misc"


def parse_patch(filepath: str) -> Optional[Patch]:
    """解析一个 .patch 文件"""
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
    except Exception as e:
        print(f"  [WARN] Failed to read {filepath}: {e}")
        return None

    lines = content.split('\n')
    patch = Patch(
        filename=os.path.basename(filepath),
        subject="",
        author="",
        date=""
    )

    # 解析头部
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('From: '):
            patch.author = line[6:].strip()
        elif line.startswith('Subject: '):
            patch.subject = line[9:].strip()
            # 去掉 [PATCH] 前缀
            patch.subject = re.sub(r'^\[PATCH\]\s*', '', patch.subject)
        elif line.startswith('Date: '):
            patch.date = line[6:].strip()
        elif line.startswith('diff --git'):
            break
        i += 1

    patch.header_lines = lines[:i]

    # 解析 diff 部分
    current_diff = None
    current_hunk = None

    while i < len(lines):
        line = lines[i]

        if line.startswith('diff --git'):
            # 保存前一个 diff
            if current_diff is not None:
                if current_hunk is not None:
                    current_diff.hunks.append(current_hunk)
                patch.file_diffs.append(current_diff)

            # 解析新 diff 头
            # diff --git a/path b/path
            match = re.match(r'diff --git a/(.+) b/(.+)', line)
            if match:
                current_diff = FileDiff(
                    old_path=match.group(1),
                    new_path=match.group(2)
                )
            current_hunk = None

        elif line.startswith('new file mode'):
            if current_diff:
                current_diff.is_new = True
                current_diff.new_mode = line[14:].strip()

        elif line.startswith('deleted file mode'):
            if current_diff:
                current_diff.is_delete = True
                current_diff.old_mode = line[18:].strip()

        elif line.startswith('old mode') or line.startswith('new mode'):
            pass

        elif line.startswith('similarity index'):
            if current_diff:
                match = re.search(r'(\d+)%', line)
                if match:
                    current_diff.similarity = int(match.group(1))

        elif line.startswith('index '):
            pass

        elif line.startswith('--- '):
            pass

        elif line.startswith('+++ '):
            pass

        elif line.startswith('@@'):
            # 保存前一个 hunk
            if current_hunk is not None and current_diff is not None:
                current_diff.hunks.append(current_hunk)

            # 解析 @@ -old_start,old_count +new_start,new_count @@
            match = re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', line)
            if match and current_diff is not None:
                current_hunk = Hunk(
                    old_start=int(match.group(1)),
                    old_count=int(match.group(2)) if match.group(2) else 1,
                    new_start=int(match.group(3)),
                    new_count=int(match.group(4)) if match.group(4) else 1,
                    lines=[line]
                )

        elif current_hunk is not None:
            current_hunk.lines.append(line)

        i += 1

    # 保存最后一个 diff/hunk
    if current_diff is not None:
        if current_hunk is not None:
            current_diff.hunks.append(current_hunk)
        patch.file_diffs.append(current_diff)

    return patch


def merge_file_diffs(diffs: List[FileDiff]) -> FileDiff:
    """合并多个 FileDiff（同一文件）为一个"""
    if not diffs:
        raise ValueError("Cannot merge empty diffs")

    # 使用第一个 diff 作为基础
    base = diffs[0]

    # 收集所有 hunks
    all_hunks = []
    for diff in diffs:
        all_hunks.extend(diff.hunks)

    # 按 old_start 排序 hunks
    all_hunks.sort(key=lambda h: h.old_start)

    # 合并重叠或相邻的 hunks
    merged_hunks = []
    for hunk in all_hunks:
        if not merged_hunks:
            merged_hunks.append(hunk)
            continue

        prev = merged_hunks[-1]
        # 检查是否重叠或相邻（在 3 行 context 范围内）
        prev_end = prev.old_start + prev.old_count
        curr_start = hunk.old_start

        if curr_start <= prev_end + 3:
            # 合并：扩展前一个 hunk 的 context
            # 简单策略：将两个 hunk 的行合并，重新计算范围
            merged = merge_two_hunks(prev, hunk)
            merged_hunks[-1] = merged
        else:
            merged_hunks.append(hunk)

    base.hunks = merged_hunks
    return base


def merge_two_hunks(h1: Hunk, h2: Hunk) -> Hunk:
    """合并两个相邻或重叠的 hunk"""
    # 确定合并后的范围
    min_old_start = min(h1.old_start, h2.old_start)
    max_old_end = max(h1.old_start + h1.old_count, h2.old_start + h2.old_count)
    min_new_start = min(h1.new_start, h2.new_start)
    max_new_end = max(h1.new_start + h1.new_count, h2.new_start + h2.new_count)

    # 合并所有行（去重）
    all_lines = []
    seen = set()
    for line in h1.lines + h2.lines:
        if line not in seen or line.strip() == '':
            all_lines.append(line)
            seen.add(line)

    return Hunk(
        old_start=min_old_start,
        old_count=max_old_end - min_old_start,
        new_start=min_new_start,
        new_count=max_new_end - min_new_start,
        lines=all_lines
    )


def generate_patch_content(patches: List[Patch], domain_name: str) -> str:
    """生成合并后的补丁内容"""
    output = []

    # 按原始文件名排序，确保稳定输出
    sorted_patches = sorted(patches, key=lambda p: p.filename)

    for patch in sorted_patches:
        # 添加分隔注释
        if output:
            output.append('')
            output.append(f'// === Merged from: {patch.filename} ===')
            output.append(f'// Subject: {patch.subject}')
            output.append(f'// Author: {patch.author}')
            output.append('')

        # 添加 diff 部分
        for diff in patch.file_diffs:
            output.append(f'diff --git a/{diff.old_path} b/{diff.new_path}')

            if diff.is_new:
                output.append('new file mode 100644')
            elif diff.is_delete:
                output.append('deleted file mode 100644')

            if diff.similarity > 0 and diff.similarity < 100:
                output.append(f'similarity index {diff.similarity}%')

            output.append(f'--- a/{diff.old_path}')
            output.append(f'+++ b/{diff.new_path}')

            for hunk in diff.hunks:
                # 重新计算 hunk 头
                output.append(f'@@ -{hunk.old_start},{hunk.old_count} +{hunk.new_start},{hunk.new_count} @@')
                for line in hunk.lines[1:]:  # 跳过原始的 @@ 行
                    if line:  # 跳过空行
                        output.append(line)

            output.append('')

    return '\n'.join(output)


def merge_patches_by_file(patches: List[Patch]) -> Dict[str, List[Patch]]:
    """按文件路径分组补丁"""
    file_groups = defaultdict(list)
    for patch in patches:
        for diff in patch.file_diffs:
            file_groups[diff.old_path].append(patch)
    return file_groups


def main():
    if len(sys.argv) < 2:
        print("Usage: python merge_patches.py <patch_directory> [output_directory]")
        print("Example: python merge_patches.py ../lmili-server/minecraft-patches/features ./merged-patches")
        sys.exit(1)

    patch_dir = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(patch_dir, '..', 'merged')

    if not os.path.isdir(patch_dir):
        print(f"Error: {patch_dir} is not a directory")
        sys.exit(1)

    # 创建输出目录
    os.makedirs(output_dir, exist_ok=True)

    # 解析所有补丁
    print(f"[*] Parsing patches from {patch_dir}...")
    all_patches = []
    patch_files = sorted(Path(patch_dir).glob('*.patch'))

    for patch_file in patch_files:
        patch = parse_patch(str(patch_file))
        if patch and patch.file_diffs:
            all_patches.append(patch)
            print(f"  Parsed: {patch.filename} ({len(patch.file_diffs)} files) - {patch.subject[:60]}")

    print(f"\n[*] Total patches parsed: {len(all_patches)}")

    # 按功能域分类
    print("\n[*] Classifying patches by functional domain...")
    domain_groups = defaultdict(list)
    for patch in all_patches:
        domain = classify_patch(patch.subject)
        domain_groups[domain].append(patch)
        print(f"  {domain}: {patch.filename} - {patch.subject[:50]}")

    # 统计
    print(f"\n[*] Domain summary:")
    for domain in sorted(domain_groups.keys()):
        patches = domain_groups[domain]
        print(f"  {domain}: {len(patches)} patches")

    # 合并每个功能域内的补丁
    print(f"\n[*] Merging patches...")
    merged_count = 0

    for domain in sorted(domain_groups.keys()):
        patches = domain_groups[domain]

        if len(patches) == 1:
            # 只有一个补丁，直接复制
            output_file = os.path.join(output_dir, f"{domain}-{patches[0].filename}")
            with open(output_file, 'w', encoding='utf-8') as f:
                # 重新生成补丁内容
                content = generate_patch_content(patches, domain)
                f.write(content)
            print(f"  {domain}: single patch, copied to {os.path.basename(output_file)}")
        else:
            # 合并多个补丁
            output_file = os.path.join(output_dir, f"{domain}-consolidated.patch")
            content = generate_patch_content(patches, domain)
            with open(output_file, 'w', encoding='utf-8') as f:
                f.write(content)
            print(f"  {domain}: merged {len(patches)} patches into {os.path.basename(output_file)}")
            merged_count += len(patches) - 1

    print(f"\n[*] Done! Reduced from {len(all_patches)} to {len(domain_groups)} patches")
    print(f"[*] Merged {merged_count} redundant patches")
    print(f"[*] Output directory: {output_dir}")


if __name__ == '__main__':
    main()
