#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MP4 faststart 重封装 / MPEG-TS 转封装（课程数据准备工具）
========================================================

两类处理：

1) .mp4 / .m4v / .mov —— moov 前置（faststart），不转码
   极客时间下载的视频多数把 moov 放在文件尾部（ftyp/free/mdat/moov）。网页和手机
   WebView 首播、拖动时要先发一个尾部 Range 请求才能拿到时长与索引，弱网下容易
   长时间转圈。本脚本用 ffmpeg 把这些文件重封装成 moov 前置（faststart），不转码。

2) .ts —— 转封装成 MP4
   浏览器的 <video>、阅读器和 App 的视频列表都只认 mp4/webm 一类容器，裸
   MPEG-TS 既不会被 build.py 收进课程视频，网页端也放不了。TS 先试 -c copy；
   分段下载合并出来的 TS 常在十几秒处 ADTS 音频断裂，复制会中途报错并截断，
   这时自动改成「视频无损复制 + 音轨重编码 AAC」，画面不重新编码。

用法
----
    # 默认：mp4 结果写到 <课程目录>/mp4-opt/，原文件不动
    python faststart_mp4.py "/Volumes/.../极客时间/算法面试通关40讲"

    # 只看看哪些文件需要处理
    python faststart_mp4.py "<课程目录>" --dry-run

    # 原地替换（先写临时文件再原子替换，保留原 mtime；必须显式加 --yes）
    python faststart_mp4.py "<课程目录>" --in-place --yes

    # TS 转封装；成功后删掉原 .ts
    python faststart_mp4.py "<课程目录>" --in-place --yes --remove-ts

    # 并行度、已有 faststart 的也重做
    python faststart_mp4.py "<课程目录>" --jobs 4 --force

约定
----
* 处理 .mp4 / .m4v / .mov / .ts；reader/、mp4-opt/ 等产物目录自动跳过。
* TS 的产物固定落在源文件旁的 <同名>.mp4：阅读器只在课程根目录和附件目录里找
  视频，mp4-opt/ 不会被收录，所以 --out 对 TS 不生效。
* mp4 已是 faststart 默认跳过（--force 重做）；TS 以同名 .mp4 已存在且 faststart
  作为「已处理」判据（此时若带 --remove-ts，只删源 .ts，不重新转封装）。
* 重封装后复查 box 顺序与体积：mp4 相差超过 5%、TS 超过 20% 视为异常，保留原文件。
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

TS_EXTS = ('.ts',)
VIDEO_EXTS = ('.mp4', '.m4v', '.mov') + TS_EXTS
SKIP_DIRS = ('reader', 'mp4-opt', 'node_modules', '.git')
MAX_SIZE_DRIFT = 0.05            # 重封装前后体积允许的偏差
TS_SIZE_DRIFT = 0.20             # TS 去掉包头 + 音轨重编码，体积变化比 mp4 大
TS_AUDIO_BITRATE = '128k'        # ADTS 断裂时只重编码音轨


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


def is_ts(path):
    return os.path.splitext(path)[1].lower() in TS_EXTS


def dst_for(src, root, out_dir, in_place):
    """计算输出路径。

    TS 换了容器，必须落到 .mp4；而且固定放在源文件旁——阅读器只在课程根目录和
    附件目录里找视频，mp4-opt/ 不会被收录。其余容器沿用原来的 out / in-place 规则。
    """
    if is_ts(src):
        return os.path.splitext(src)[0] + '.mp4'
    return src if in_place else os.path.join(out_dir, os.path.relpath(src, root))


# ---------------------------------------------------------------- 重封装

def find_videos(root):
    hits = []
    for cur, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith('.')]
        for name in files:
            if os.path.splitext(name)[1].lower() in VIDEO_EXTS:
                hits.append(os.path.join(cur, name))
    return sorted(hits)


def _remux_once(src, dst, ffmpeg, copy_audio):
    """跑一次 ffmpeg：画面一律无损复制，音轨按 copy_audio 决定复制还是重编码。"""
    tmp = dst + '.part'
    cmd = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
           '-i', src,
           '-map', '0:v:0?', '-map', '0:a:0?',      # 课程视频只有一轨画面一轨声音
           '-c:v', 'copy']
    if copy_audio:
        cmd += ['-c:a', 'copy']
    else:
        cmd += ['-c:a', 'aac', '-b:a', TS_AUDIO_BITRATE]
    if is_ts(src):
        # TS 的 PTS 常从 1~3 秒起跳，归一化后播放器不会先卡一段空白
        cmd += ['-avoid_negative_ts', 'make_zero']
    cmd += ['-movflags', '+faststart', '-map_metadata', '0', '-f', 'mp4', tmp]
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
    limit = TS_SIZE_DRIFT if is_ts(src) else MAX_SIZE_DRIFT
    if src_size and abs(dst_size - src_size) > src_size * limit:
        _unlink(tmp)
        return False, '体积异常（%.1f MB → %.1f MB）' % (src_size / 1e6, dst_size / 1e6)
    os.replace(tmp, dst)
    return True, ''


def remux(src, dst, ffmpeg):
    """重封装成 faststart MP4，返回 (ok, 错误信息, 是否重编码了音轨)。

    TS 先按无损复制走一次；分段合并出来的 TS 常见 ADTS 音频断裂，-c copy 会
    中途报错并截断，这时只重编码音轨，画面仍无损复制。
    """
    ok, err = _remux_once(src, dst, ffmpeg, copy_audio=True)
    if ok or not is_ts(src):
        return ok, err, False
    ok2, err2 = _remux_once(src, dst, ffmpeg, copy_audio=False)
    return ok2, err2, ok2


def _unlink(path):
    try:
        os.unlink(path)
    except OSError:
        pass


# ---------------------------------------------------------------- 主流程

def main(argv=None):
    ap = argparse.ArgumentParser(
        description='mp4 重封装成 faststart（moov 前置）；TS 转封装成 mp4')
    ap.add_argument('dir', help='课程目录（可含子目录）')
    ap.add_argument('--out', help='mp4 的输出目录，默认 <课程目录>/mp4-opt（TS 固定写到源目录）')
    ap.add_argument('--remove-ts', action='store_true',
                    help='TS 转封装成功后删除原 .ts（需配合 --in-place --yes）')
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
    if args.remove_ts and not (args.in_place and args.yes):
        raise SystemExit('--remove-ts 会删除原 .ts，需同时加 --in-place --yes')

    out_dir = root if args.in_place else os.path.abspath(args.out or os.path.join(root, 'mp4-opt'))
    files = find_videos(root)
    if not files:
        raise SystemExit('目录里没有视频文件: %s' % root)

    plan, skipped = [], 0
    for src in files:
        dst = dst_for(src, root, out_dir, args.in_place)
        if is_ts(src):
            # TS 没有 moov：用「同名 .mp4 已存在且已 faststart」判断是否处理过
            if not args.force and os.path.isfile(dst) and is_faststart(dst) is True:
                # --remove-ts 时，已转好的也顺手删掉源文件（dry-run 不动手）
                if args.remove_ts and not args.dry_run and os.path.getsize(dst) > 1024:
                    _unlink(src)
                    print('  [清理] %s 已存在，删除 %s'
                          % (os.path.basename(dst), os.path.relpath(src, root)))
                skipped += 1
                continue
            plan.append((src, dst))
            continue
        state = is_faststart(src)
        if state is True and not args.force:
            skipped += 1
            continue
        if state is None:
            print('  [跳过] 不是标准 mp4: %s' % os.path.relpath(src, root))
            skipped += 1
            continue
        plan.append((src, dst))

    total_bytes = sum(os.path.getsize(s) for s, _ in plan)
    ts_count = sum(1 for s, _ in plan if is_ts(s))
    print('扫描 %d 个视频，待处理 %d 个（%.1f GB，其中 TS 转封装 %d 个），跳过 %d 个'
          % (len(files), len(plan), total_bytes / 1e9, ts_count, skipped))
    if args.dry_run:
        for src, dst in plan:
            print('  %s -> %s' % (os.path.relpath(src, root), os.path.relpath(dst, root)))
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
            st = os.stat(src)
            stats[src] = st
            parent = os.path.dirname(dst)
            if parent and not os.path.isdir(parent):
                os.makedirs(parent, exist_ok=True)
            ok, err, audio = remux(src, dst, ffmpeg)
            if ok:
                if args.in_place or is_ts(src):
                    # 产物沿用源文件 mtime：阅读器缓存键与页面排期不受影响
                    os.utime(dst, (st.st_atime, st.st_mtime))
                if is_ts(src) and args.remove_ts:
                    _unlink(src)
            return src, ok, err, audio
        except Exception as exc:                     # noqa: BLE001
            return src, False, str(exc), False

    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as ex:
        for i, (src, ok, err, audio) in enumerate(ex.map(work, plan), 1):
            if ok:
                done += 1
                print('[%d/%d] ok   %s%s' % (i, len(plan), os.path.relpath(src, root),
                                             '（音轨重编码）' if audio else ''))
            else:
                failed.append((src, err))
                print('[%d/%d] FAIL %s —— %s' % (i, len(plan), os.path.relpath(src, root), err))

    elapsed = time.time() - started
    ts_only = bool(plan) and all(is_ts(s) for s, _ in plan)
    where = ('（TS 写到各自源目录）' if ts_only
             else '（原地替换）' if args.in_place else out_dir)
    print('完成：成功 %d，失败 %d，用时 %.0f 秒，输出 %s'
          % (done, len(failed), elapsed, where))
    if failed:
        for src, err in failed:
            print('  失败: %s —— %s' % (src, err))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
