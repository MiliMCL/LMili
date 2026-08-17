#!/usr/bin/env python3
"""
Mili Patch Merger v2 - 智能补丁合并工具

核心改进：
1. 正确处理行号调整（模拟顺序应用补丁）
2. 合并同一文件中相邻或重叠的 hunk
3. 生成可直接应用的合并补丁

算法：
1. 解析所有补丁的 hunks
2. 按文件分组，保持原始顺序
3. 对每个文件的 hunks：
   a. 按原始行号排序
   b. 顺序应用，跟踪行号偏移
   c. 合并相邻/重叠的 hunks
4. 生成合并后的补丁文件
"""

import os
import re
import sys
from pathlib import Path
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple
from collections import defaultdict


@dataclass
class HunkLine:
    """hunk 中的一行"""
    type: str  # ' ' (context), '-' (remove), '+' (add)
    content: str


@dataclass
class Hunk:
    """表示一个 diff hunk"""
    old_start: int
    old_lines: int
    new_start: int
    new_lines: int
    lines: List[HunkLine] = field(default_factory=list)
    source_patch: str = ""  # 来源补丁文件名


@dataclass
class FileDiff:
    """表示一个文件的完整 diff"""
    old_path: str
    new_path: str
    hunks: List[Hunk] = field(default_factory=list)
    is_new: bool = False
    is_delete: bool = False


@dataclass
class Patch:
    """表示一个完整的补丁文件"""
    filename: str
    subject: str
    file_diffs: List[FileDiff] = field(default_factory=list)


def parse_patch(filepath: str) -> Optional[Patch]:
    """解析一个 .patch 文件"""
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
    except Exception:
        return None

    lines = content.split('\n')
    patch = Patch(filename=os.path.basename(filepath), subject="")

    # 提取 subject
    for line in lines:
        if line.startswith('Subject: '):
            patch.subject = re.sub(r'^\[PATCH\]\s*', '', line[9:].strip())
            break

    # 解析 diff 部分
    current_diff = None
    current_hunk = None

    for line in lines:
        if line.startswith('diff --git'):
            if current_diff is not None:
                if current_hunk is not None:
                    current_diff.hunks.append(current_hunk)
                patch.file_diffs.append(current_diff)

            match = re.match(r'diff --git a/(.+) b/(.+)', line)
            if match:
                current_diff = FileDiff(old_path=match.group(1), new_path=match.group(2))
                current_hunk = None

        elif line.startswith('new file mode'):
            if current_diff:
                current_diff.is_new = True

        elif line.startswith('deleted file mode'):
            if current_diff:
                current_diff.is_delete = True

        elif line.startswith('@@') and current_diff is not None:
            if current_hunk is not None:
                current_diff.hunks.append(current_hunk)

            match = re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', line)
            if match:
                current_hunk = Hunk(
                    old_start=int(match.group(1)),
                    old_lines=int(match.group(2)) if match.group(2) else 1,
                    new_start=int(match.group(3)),
                    new_lines=int(match.group(4)) if match.group(4) else 1,
                    source_patch=patch.filename
                )

        elif current_hunk is not None:
            if line.startswith('-'):
                current_hunk.lines.append(HunkLine('-', line[1:]))
            elif line.startswith('+'):
                current_hunk.lines.append(HunkLine('+', line[1:]))
            elif line.startswith(' '):
                current_hunk.lines.append(HunkLine(' ', line[1:]))
            elif line == '\\ No newline at end of file':
                pass  # 忽略

    if current_diff is not None:
        if current_hunk is not None:
            current_diff.hunks.append(current_hunk)
        patch.file_diffs.append(current_diff)

    return patch if patch.file_diffs else None


def adjust_hunks(hunks: List[Hunk]) -> List[Hunk]:
    """
    调整 hunks 的行号，模拟顺序应用
    
    算法：
    1. 按 old_start 排序
    2. 顺序处理每个 hunk
    3. 跟踪由于前面 hunk 导致的行号偏移
    4. 如果 hunk 重叠或相邻（< 3 行 gap），合并它们
    """
    if not hunks:
        return []

    # 按 old_start 排序
    sorted_hunks = sorted(hunks, key=lambda h: h.old_start)

    # 调整行号
    adjusted = []
    line_offset = 0  # 累计行号偏移

    for hunk in sorted_hunks:
        # 调整 old_start（考虑前面 hunk 的影响）
        adjusted_hunk = Hunk(
            old_start=hunk.old_start + line_offset,
            old_lines=hunk.old_lines,
            new_start=hunk.new_start + line_offset,
            new_lines=hunk.new_lines,
            lines=hunk.lines,
            source_patch=hunk.source_patch
        )
        adjusted.append(adjusted_hunk)

        # 更新偏移：新增行数 - 删除行数
        delta = hunk.new_lines - hunk.old_lines
        line_offset += delta

    return adjusted


def merge_adjacent_hunks(hunks: List[Hunk]) -> List[Hunk]:
    """合并相邻或重叠的 hunks"""
    if len(hunks) <= 1:
        return hunks

    merged = [hunks[0]]

    for current in hunks[1:]:
        prev = merged[-1]

        # 检查是否重叠或相邻（gap < 3 行）
        prev_old_end = prev.old_start + prev.old_lines
        curr_old_start = current.old_start

        if curr_old_start <= prev_old_end + 3:
            # 合并两个 hunk
            merged_hunk = merge_two_hunks(prev, current)
            merged[-1] = merged_hunk
        else:
            merged.append(current)

    return merged


def merge_two_hunks(h1: Hunk, h2: Hunk) -> Hunk:
    """合并两个相邻或重叠的 hunk"""
    # 扩展 h1 的 context 以包含 h2
    # 简单策略：取并集

    min_old_start = min(h1.old_start, h2.old_start)
    max_old_end = max(h1.old_start + h1.old_lines, h2.old_start + h2.old_lines)
    min_new_start = min(h1.new_start, h2.new_start)
    max_new_end = max(h1.new_start + h1.new_lines, h2.new_start + h2.new_lines)

    # 合并行（保持顺序，去重上下文行）
    all_lines = []
    seen_content = set()

    for line in h1.lines + h2.lines:
        key = (line.type, line.content)
        if key not in seen_content or line.type != ' ':
            all_lines.append(line)
            seen_content.add(key)

    return Hunk(
        old_start=min_old_start,
        old_lines=max_old_end - min_old_start,
        new_start=min_new_start,
        new_lines=max_new_end - min_new_start,
        lines=all_lines,
        source_patch=f"{h1.source_patch}+{h2.source_patch}"
    )


def generate_hunk_header(hunk: Hunk) -> str:
    """生成 hunk 头"""
    return f"@@ -{hunk.old_start},{hunk.old_lines} +{hunk.new_start},{hunk.new_lines} @@"


def generate_file_diff(diff: FileDiff) -> str:
    """生成单个文件 diff 的文本"""
    lines = []
    lines.append(f"diff --git a/{diff.old_path} b/{diff.new_path}")

    if diff.is_new:
        lines.append("new file mode 100644")
    elif diff.is_delete:
        lines.append("deleted file mode 100644")

    lines.append(f"--- a/{diff.old_path}")
    lines.append(f"+++ b/{diff.new_path}")

    for hunk in diff.hunks:
        lines.append(generate_hunk_header(hunk))
        for line in hunk.lines:
            lines.append(f"{line.type}{line.content}")

    return '\n'.join(lines)


def classify_patch(subject: str) -> str:
    """根据补丁主题分类到功能域"""
    subject_lower = subject.lower()

    # 简化的分类规则
    if any(x in subject_lower for x in ['rebrand', 'rename package', 'luminol', 'lophine', 'brand']):
        return "01-rebrand"
    if any(x in subject_lower for x in ['config', 'configuration', 'settings']):
        return "02-config-system"
    if any(x in subject_lower for x in ['entity', 'mob', 'villager', 'dragon', 'sculk',
            'pathfind', 'collision', 'spawn', 'move', 'portal', 'teleport',
            'skip', 'optimize', 'reduce', 'replace', 'remove', 'fix', 'correct',
            'prevent', 'lobotomize', 'sensor', 'goal', 'brain', 'ai',
            'zero movement', 'planar', 'line of sight', 'criterion',
            'visible effects', 'chunk reload', 'debug subscription',
            'volatile reference', 'riding statistic', 'null check',
            'removed check', 'rendering', 'long command', 'async protocol',
            'global entities']):
        return "03-entity-optimizations"
    if any(x in subject_lower for x in ['chunk', 'region', 'tick region', 'linear',
            'threading', 'async chunk', 'cross', 'portal rate',
            'waypoint', 'secure seed', 'matter seed', 'read only',
            'force disable', 'server health', 'replay', 'bytebuf',
            'photographer', 'packet event', 'bukkit event', 'leaves event',
            'leaves photographer', 'end platform', 'portal locate',
            'entity teleport', 'region stats', 'threaded region',
            'tick region data', 'block pos transform', 'cancel task',
            'add missing teleportation', 'barrels', 'skip event']):
        return "04-chunk-region"
    if any(x in subject_lower for x in ['fix compilation', 'comment out', 'temporarily fix']):
        return "05-fixes"

    return "06-misc"


def main():
    if len(sys.argv) < 2:
        print("Usage: python merge_patches_v2.py <patch_directory> [output_directory]")
        sys.exit(1)

    patch_dir = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(patch_dir, '..', 'merged-v2')

    if not os.path.isdir(patch_dir):
        print(f"Error: {patch_dir} is not a directory")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # 解析所有补丁
    print(f"[*] Parsing patches from {patch_dir}...")
    all_patches = []
    patch_files = sorted(Path(patch_dir).glob('*.patch'))

    for patch_file in patch_files:
        patch = parse_patch(str(patch_file))
        if patch and patch.file_diffs:
            all_patches.append(patch)

    print(f"  Parsed {len(all_patches)} patches")

    # 按功能域分类
    domain_groups = defaultdict(list)
    for patch in all_patches:
        domain = classify_patch(patch.subject)
        domain_groups[domain].append(patch)

    print(f"\n[*] Classification:")
    for domain in sorted(domain_groups.keys()):
        print(f"  {domain}: {len(domain_groups[domain])} patches")

    # 对每个功能域，合并补丁
    print(f"\n[*] Merging patches by domain...")

    for domain in sorted(domain_groups.keys()):
        patches = domain_groups[domain]

        # 收集所有文件 diffs（保持顺序）
        file_diffs_map: Dict[str, List[Tuple[int, FileDiff]]] = defaultdict(list)

        for patch_idx, patch in enumerate(patches):
            for diff in patch.file_diffs:
                file_diffs_map[diff.old_path].append((patch_idx, diff))

        # 对每个文件，合并 hunks
        merged_diffs = []
        for file_path, indexed_diffs in file_diffs_map.items():
            # 按补丁顺序排序
            indexed_diffs.sort(key=lambda x: x[0])

            # 收集所有 hunks
            all_hunks = []
            is_new = False
            is_delete = False

            for patch_idx, diff in indexed_diffs:
                if diff.is_new:
                    is_new = True
                if diff.is_delete:
                    is_delete = True
                for hunk in diff.hunks:
                    all_hunks.append(hunk)

            # 调整行号
            adjusted = adjust_hunks(all_hunks)

            # 合并相邻 hunks
            merged = merge_adjacent_hunks(adjusted)

            # 创建合并后的 FileDiff
            merged_diff = FileDiff(
                old_path=file_path,
                new_path=file_path,
                hunks=merged,
                is_new=is_new,
                is_delete=is_delete
            )
            merged_diffs.append(merged_diff)

        # 按文件路径排序以获得稳定输出
        merged_diffs.sort(key=lambda d: d.old_path)

        # 生成合并后的补丁文件
        output_file = os.path.join(output_dir, f"{domain}-consolidated.patch")

        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(f"From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n")
            f.write(f"From: Mili Dev <dev@mili.example>\n")
            f.write(f"Date: Mon, 01 Jan 2026 00:00:00 +0000\n")
            f.write(f"Subject: [PATCH] {domain.split('-', 1)[1].replace('-', ' ').title()} (consolidated)\n")
            f.write(f"\n")
            f.write(f"Consolidated from {len(patches)} original patches:\n")
            for p in patches:
                f.write(f"  - {p.filename}: {p.subject}\n")
            f.write(f"\n\n")

            for diff in merged_diffs:
                f.write(generate_file_diff(diff))
                f.write('\n\n')

        print(f"  {domain}: merged into {os.path.basename(output_file)} ({len(merged_diffs)} files)")

    print(f"\n[*] Done! Output: {output_dir}")


if __name__ == '__main__':
    main()
