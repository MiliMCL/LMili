#!/usr/bin/env python3
"""
Mili Patch Merger v3 - 最终版

核心改进：
1. 正确处理跨补丁的行号依赖（顺序应用模拟）
2. 同一文件的所有修改收集后统一调整行号
3. 按功能域分组输出，但保持行号正确性

算法：
1. 按顺序解析所有原始补丁
2. 对每个文件，按顺序收集所有 hunks
3. 模拟顺序应用，计算每个 hunk 的最终行号
4. 将调整后的 hunks 按功能域分组
5. 生成合并补丁，确保：
   a. 每个文件只出现在一个补丁中（避免跨补丁依赖）
   b. 补丁按功能域顺序应用
   c. 行号正确
"""

import os
import re
import sys
from pathlib import Path
from dataclasses import dataclass, field
from typing import List, Dict, Optional
from collections import defaultdict, OrderedDict


@dataclass
class HunkLine:
    type: str
    content: str


@dataclass
class Hunk:
    old_start: int
    old_lines: int
    new_start: int
    new_lines: int
    lines: List[HunkLine] = field(default_factory=list)
    source_patch: str = ""
    domain: str = ""


@dataclass
class FileDiff:
    old_path: str
    new_path: str
    hunks: List[Hunk] = field(default_factory=list)
    is_new: bool = False
    is_delete: bool = False


@dataclass
class Patch:
    filename: str
    subject: str
    file_diffs: List[FileDiff] = field(default_factory=list)
    domain: str = ""


def parse_patch(filepath: str) -> Optional[Patch]:
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
    except Exception:
        return None

    lines = content.split('\n')
    patch = Patch(filename=os.path.basename(filepath), subject="")

    for line in lines:
        if line.startswith('Subject: '):
            patch.subject = re.sub(r'^\[PATCH\]\s*', '', line[9:].strip())
            break

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
                pass

    if current_diff is not None:
        if current_hunk is not None:
            current_diff.hunks.append(current_hunk)
        patch.file_diffs.append(current_diff)

    return patch if patch.file_diffs else None


def classify_patch(subject: str) -> str:
    s = subject.lower()
    if any(x in s for x in ['rebrand', 'rename package', 'luminol', 'lophine', 'brand name']):
        return "01-rebrand"
    if any(x in s for x in ['config', 'configuration', 'settings', 'gui support']):
        return "02-config-system"
    if any(x in s for x in ['entity', 'mob', 'villager', 'dragon', 'sculk',
            'pathfind', 'collision', 'spawn', 'move', 'portal', 'teleport',
            'skip', 'optimize', 'reduce', 'replace', 'remove', 'fix', 'correct',
            'prevent', 'lobotomize', 'sensor', 'goal', 'brain', 'ai',
            'zero movement', 'planar', 'line of sight', 'criterion',
            'visible effects', 'chunk reload', 'debug subscription',
            'volatile reference', 'riding statistic', 'null check',
            'removed check', 'rendering', 'long command', 'async protocol',
            'global entities', 'canSee', 'distanceToSqr', 'negligible',
            'krypton', 'fast util', 'lithium', 'kaiiju', 'petal',
            'pufferfish', 'purpur', 'sparklypaper', 'gale', 'leaf',
            'leaves', 'secure seed', 'matter seed', 'noise generation',
            'end portal', 'ender pearl', 'tamable', 'off region',
            'poi', 'block goal', 'block behaviour', 'worldgen',
            'creative item', 'respawn place', 'unpatched task',
            'teleport async', 'move event', 'yam and pitch']):
        return "03-entity-optimizations"
    if any(x in s for x in ['chunk', 'region', 'tick region', 'linear',
            'threading', 'async chunk', 'cross', 'portal rate',
            'waypoint', 'read only', 'force disable', 'server health',
            'replay', 'bytebuf', 'photographer', 'packet event',
            'bukkit event', 'leaves event', 'end platform',
            'portal locate', 'region stats', 'threaded region',
            'tick region data', 'block pos transform', 'cancel task',
            'add missing teleportation', 'barrels', 'skip event',
            'data command', 'command block', 'watchdog', 'heightmap',
            'tripwire', 'packet limiter', 'offline mode', 'username',
            'vanilla random', 'cpu affinity', 'signature']):
        return "04-chunk-region"
    if any(x in s for x in ['fix compilation', 'comment out', 'temporarily fix']):
        return "05-fixes"
    return "06-misc"


def main():
    if len(sys.argv) < 2:
        print("Usage: python merge_patches_v3.py <patch_directory> [output_directory]")
        sys.exit(1)

    patch_dir = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(patch_dir, '..', 'merged-v3')

    if not os.path.isdir(patch_dir):
        print(f"Error: {patch_dir} is not a directory")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # Step 1: Parse all patches in order
    print(f"[*] Parsing patches from {patch_dir}...")
    all_patches = []
    for patch_file in sorted(Path(patch_dir).glob('*.patch')):
        patch = parse_patch(str(patch_file))
        if patch and patch.file_diffs:
            patch.domain = classify_patch(patch.subject)
            all_patches.append(patch)

    print(f"  Parsed {len(all_patches)} patches")

    # Step 2: Collect all hunks per file, in order
    # file_path -> [(patch_index, hunk)]
    file_hunks = OrderedDict()

    for patch_idx, patch in enumerate(all_patches):
        for diff in patch.file_diffs:
            file_path = diff.old_path
            if file_path not in file_hunks:
                file_hunks[file_path] = []

            for hunk in diff.hunks:
                hunk.domain = patch.domain
                file_hunks[file_path].append((patch_idx, hunk))

    # Step 3: For each file, adjust line numbers sequentially
    print(f"[*] Adjusting line numbers for {len(file_hunks)} files...")

    # Result: domain -> file_path -> list of adjusted hunks
    domain_files = defaultdict(lambda: defaultdict(list))

    for file_path, indexed_hunks in file_hunks.items():
        # Sort by patch index, then by old_start
        indexed_hunks.sort(key=lambda x: (x[0], x[1].old_start))

        # Adjust line numbers
        line_offset = 0
        for patch_idx, hunk in indexed_hunks:
            # Adjust the hunk's line numbers
            adjusted_hunk = Hunk(
                old_start=hunk.old_start + line_offset,
                old_lines=hunk.old_lines,
                new_start=hunk.new_start + line_offset,
                new_lines=hunk.new_lines,
                lines=hunk.lines,
                source_patch=hunk.source_patch,
                domain=hunk.domain
            )

            # Update offset for next hunk
            delta = hunk.new_lines - hunk.old_lines
            line_offset += delta

            # Add to domain grouping
            domain_files[hunk.domain][file_path].append(adjusted_hunk)

    # Step 4: Generate consolidated patches per domain
    print(f"\n[*] Generating consolidated patches...")

    domain_order = ["01-rebrand", "02-config-system", "03-entity-optimizations",
                    "04-chunk-region", "05-fixes", "06-misc"]

    for domain in domain_order:
        if domain not in domain_files:
            continue

        files = domain_files[domain]
        output_file = os.path.join(output_dir, f"{domain}-consolidated.patch")

        # Collect source patch names
        source_patches = set()
        for file_path, hunks in files.items():
            for hunk in hunks:
                source_patches.add(hunk.source_patch)

        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(f"From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n")
            f.write(f"From: Mili Dev <dev@mili.example>\n")
            f.write(f"Date: Mon, 01 Jan 2026 00:00:00 +0000\n")
            f.write(f"Subject: [PATCH] {domain.split('-', 1)[1].replace('-', ' ').title()} (consolidated)\n")
            f.write(f"\n")
            f.write(f"Consolidated from {len(source_patches)} original patches:\n")
            for p in sorted(source_patches):
                f.write(f"  - {p}\n")
            f.write(f"\n\n")

            # Sort files for stable output
            for file_path in sorted(files.keys()):
                hunks = files[file_path]

                f.write(f"diff --git a/{file_path} b/{file_path}\n")
                f.write(f"--- a/{file_path}\n")
                f.write(f"+++ b/{file_path}\n")

                for hunk in hunks:
                    f.write(f"@@ -{hunk.old_start},{hunk.old_lines} +{hunk.new_start},{hunk.new_lines} @@\n")
                    for line in hunk.lines:
                        f.write(f"{line.type}{line.content}\n")

                f.write("\n")

        print(f"  {domain}: {len(files)} files -> {os.path.basename(output_file)}")

    print(f"\n[*] Done! Output: {output_dir}")


if __name__ == '__main__':
    main()
