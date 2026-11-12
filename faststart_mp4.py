#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MP4 faststart 重封装（课程数据准备工具）
======================================

极客时间下载的视频多数把 moov 放在文件尾部（ftyp/free/mdat/moov）。网页和手机
WebView 首播、拖动时要先发一个尾部 Range 请求才能拿到时长与索引，弱网下容易
长时间转圈。本脚本用 ffmpeg 把这些文件重封装成 moov 前置（faststart），不转码。

用法
----
    # 默认：结果写到 <课程目录>/mp4-opt/，原文件不动
    python faststart_mp4.py "/Volumes/.../极客时间/算法面试通关40讲"

    # 只看看哪些文件需要处理
    python faststart_mp4.py "<课程目录>" --dry-run

    # 原地替换（先写临时文件再原子替换，保留原 mtime；必须显式加 --yes）
    python faststart_mp4.py "<课程目录>" --in-place --yes

    # 并行度、已有 faststart 的也重做
    python faststart_mp4.py "<课程目录>" --jobs 4 --force

约定
----
* 只处理 .mp4 / .m4v / .mov；reader/、mp4-opt/ 等产物目录自动跳过。
* 已经是 faststart 的文件默认跳过（--force 可强制重做）。
* 重封装后复查 box 顺序与体积（相差超过 5% 视为异常，保留原文件）。
* ffmpeg 从 PATH 查找，或 --ffmpeg /path/to/ffmpeg 指定。
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

VIDEO_EXTS = ('.mp4', '.m4v', '.mov')
SKIP_DIRS = ('reader', 'mp4-opt', 'node_modules', '.git')
MAX_SIZE_DRIFT = 0.05            # 重封装前后体积允许的偏差


# ---------------------------------------------------------------- mp4 结构

def top_boxes(path, limit=8):
    """读出顶层 box 列表 [(类型, 偏移, 长度)]，只读文件头，不整读。"""
    boxes = []
    size_total = os.path.getsize(path)
    pos = 0
    with open(path, 'rb') as f:
        while pos + 8 <= size_total and len(boxes) < limit:
            f.seek(pos)
            hdr = f.read(8)
            if len(hdr) < 8:
                break
            size = struct.unpack('>I', hdr[:4])[0]
            typ = hdr[4:8].decode('latin1', 'replace')
            if size == 1:                       # 64 位长度
                ext = f.read(8)
                if len(ext) < 8:
                    break
                size = struct.unpack('>Q', ext)[0]
            elif size == 0:                     # 一直到文件尾
                size = size_total - pos
            if size < 8:
                break
            boxes.append((typ, pos, size))
            pos += size
    return boxes


def is_faststart(path):
    """moov 在 mdat 之前返回 True；结构异常（缺盒子）返回 None。"""
    types = [b[0] for b in top_boxes(path, 8)]
    if 'moov' not in types or 'mdat' not in types:
        return None
    return types.index('moov') < types.index('mdat')


# ---------------------------------------------------------------- 重封装

def find_videos(root):
    hits = []
    for cur, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
        for name in files:
            if os.path.splitext(name)[1].lower() in VIDEO_EXTS:
                hits.append(os.path.join(cur, name))
    return sorted(hits)


def remux(src, dst, ffmpeg):
    """ffmpeg -c copy + faststart，返回 (ok, 错误信息)。"""
    tmp = dst + '.part'
    cmd = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
           '-i', src,
           '-map', '0:v:0?', '-map', '0:a:0?',      # 课程视频只有一轨画面一轨声音
           '-c', 'copy', '-movflags', '+faststart', '-map_metadata', '0',
           '-f', 'mp4', tmp]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True)
    except OSError as exc:
        return False, 'ffmpeg 启动失败: %s' % exc
    if proc.returncode != 0:
        _unlink(tmp)
        lines = (proc.stderr or '').strip().splitlines()
        return False, (lines[-1] if lines else 'ffmpeg 退出码 %d' % proc.returncode)
    if is_faststart(tmp) is not True:
        _unlink(tmp)
        return False, '重封装后 moov 仍不在文件头'
    src_size = os.path.getsize(src)
    dst_size = os.path.getsize(tmp)
    if src_size and abs(dst_size - src_size) > src_size * MAX_SIZE_DRIFT:
        _unlink(tmp)
        return False, '体积异常（%.1f MB → %.1f MB）' % (src_size / 1e6, dst_size / 1e6)
    os.replace(tmp, dst)
    return True, ''


def _unlink(path):
    try:
        os.unlink(path)
    except OSError:
        pass


# ---------------------------------------------------------------- 主流程

def main(argv=None):
    ap = argparse.ArgumentParser(description='把 mp4 重封装成 faststart（moov 前置），提升首播与拖动体验')
    ap.add_argument('dir', help='课程目录（可含子目录）')
    ap.add_argument('--out', help='输出目录，默认 <课程目录>/mp4-opt')
    ap.add_argument('--in-place', action='store_true', help='原地替换原文件')
    ap.add_argument('--yes', action='store_true', help='配合 --in-place，确认覆盖原文件')
    ap.add_argument('--force', action='store_true', help='已是 faststart 也重做')
    ap.add_argument('--dry-run', action='store_true', help='只列出计划，不写文件')
    ap.add_argument('--jobs', type=int, default=2, help='并行转封装数量，默认 2')
    ap.add_argument('--ffmpeg', default='ffmpeg', help='ffmpeg 路径，默认从 PATH 查找')
    args = ap.parse_args(argv)

    root = os.path.abspath(args.dir)
    if not os.path.isdir(root):
        raise SystemExit('目录不存在: %s' % root)
    ffmpeg = args.ffmpeg
    if not args.dry_run:
        if shutil.which(ffmpeg) is None:
            raise SystemExit('找不到 ffmpeg（可 pip/brew 安装，或用 --ffmpeg 指定）')
    if args.in_place and not args.yes and not args.dry_run:
        raise SystemExit('--in-place 会覆盖原视频文件，确认后加 --yes 再执行')

    out_dir = root if args.in_place else os.path.abspath(args.out or os.path.join(root, 'mp4-opt'))
    files = find_videos(root)
    if not files:
        raise SystemExit('目录里没有视频文件: %s' % root)

    plan, skipped = [], 0
    for src in files:
        state = is_faststart(src)
        if state is True and not args.force:
            skipped += 1
            continue
        if state is None:
            print('  [跳过] 不是标准 mp4: %s' % os.path.relpath(src, root))
            skipped += 1
            continue
        dst = src if args.in_place else os.path.join(out_dir, os.path.relpath(src, root))
        plan.append((src, dst))

    total_bytes = sum(os.path.getsize(s) for s, _ in plan)
    print('扫描 %d 个视频，待处理 %d 个（%.1f GB），跳过 %d 个（已 faststart 或非 mp4）'
          % (len(files), len(plan), total_bytes / 1e9, skipped))
    if args.dry_run:
        for src, dst in plan:
            print('  %s -> %s' % (os.path.relpath(src, root), dst))
        return 0
    if not plan:
        print('无需处理。')
        return 0

    done, failed = 0, []
    started = time.time()
    # 原文件 mtime 保留：阅读器的缓存键与页面时间不受影响
    stats = {}

    def work(item):
        src, dst = item
        try:
            stats[src] = os.stat(src)
            if not args.in_place:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
            ok, err = remux(src, dst, ffmpeg)
            if ok and args.in_place:
                st = stats[src]
                os.utime(src, (st.st_atime, st.st_mtime))
            return src, ok, err
        except Exception as exc:                     # noqa: BLE001
            return src, False, str(exc)

    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as ex:
        for i, (src, ok, err) in enumerate(ex.map(work, plan), 1):
            if ok:
                done += 1
                print('[%d/%d] ok   %s' % (i, len(plan), os.path.relpath(src, root)))
            else:
                failed.append((src, err))
                print('[%d/%d] FAIL %s —— %s' % (i, len(plan), os.path.relpath(src, root), err))

    elapsed = time.time() - started
    print('完成：成功 %d，失败 %d，用时 %.0f 秒，输出 %s'
          % (done, len(failed), elapsed, out_dir if not args.in_place else '（原地替换）'))
    if failed:
        for src, err in failed:
            print('  失败: %s —— %s' % (src, err))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
