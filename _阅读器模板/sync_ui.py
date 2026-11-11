#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「_阅读器模板」的壳与前端资源同步到所有已生成的阅读器。

只同步 UI 文件（index.html + assets/*），不碰 pages/ 里的正文数据，
所以改完界面（结构 / 样式 / 脚本）跑一次即可全站生效，无需重新构建课程。

用法：
    python _阅读器模板/sync_ui.py                 # 同步全部阅读器
    python _阅读器模板/sync_ui.py --check         # 只检查哪些阅读器落后
    python _阅读器模板/sync_ui.py Redis 10x       # 只同步名字含这些片段的课程
"""
import argparse
import filecmp
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TPL_INDEX = os.path.join(HERE, 'index.html')
TPL_ASSETS = os.path.join(HERE, 'assets')

# 模板根目录里要同步的文件（assets/ 整个目录会自动覆盖）
ROOT_SYNC = ('index.html',)
# 模板根目录里这些后缀出现新文件时提醒：说明同步清单该扩展了
ROOT_WATCH = ('.css', '.js', '.svg', '.ico', '.png', '.json', '.woff2')
# 旧版资源（已并入 page.js），同步时顺手删掉
STALE_ASSETS = ('article.js',)


def template_manifest():
    """返回 [('copy', 模板源, 目标名)]，目标名相对 reader 目录。"""
    items = [('copy', TPL_INDEX, 'index.html')]
    for name in sorted(os.listdir(TPL_ASSETS)):
        src = os.path.join(TPL_ASSETS, name)
        if os.path.isfile(src):
            items.append(('copy', src, os.path.join('assets', name)))
    return items


def unwatched_root_files():
    """模板根目录里没进同步清单的静态资源（提醒用）。"""
    extra = []
    for name in sorted(os.listdir(HERE)):
        p = os.path.join(HERE, name)
        if not os.path.isfile(p) or name in ROOT_SYNC:
            continue
        if name.lower().endswith(ROOT_WATCH):
            extra.append(name)
    return extra


def find_readers(filters):
    """返回 [(课程名, reader 目录)]。"""
    out = []
    for name in sorted(os.listdir(ROOT)):
        r = os.path.join(ROOT, name, 'reader')
        if not os.path.isfile(os.path.join(r, 'data', 'catalog.js')):
            continue
        if filters and not any(f in name for f in filters):
            continue
        out.append((name, r))
    return out


def plan_reader(reader_dir, manifest):
    """返回待执行的动作列表：[('copy', src, dst) | ('del', dst)]。"""
    actions = []
    for _, src, rel in manifest:
        if os.path.basename(rel) in STALE_ASSETS:
            continue
        dst = os.path.join(reader_dir, rel)
        if not os.path.isfile(dst) or not filecmp.cmp(src, dst, shallow=False):
            actions.append(('copy', src, dst))
    for name in STALE_ASSETS:
        dst = os.path.join(reader_dir, 'assets', name)
        if os.path.isfile(dst):
            actions.append(('del', dst))
    return actions


def run_actions(actions):
    done = []
    for act, a, b in actions:
        try:
            if act == 'copy':
                parent = os.path.dirname(b)
                if not os.path.isdir(parent):
                    os.makedirs(parent)
                shutil.copyfile(a, b)
            else:
                os.remove(b)
            done.append(act)
        except OSError as e:
            print('  ! %s 失败：%s（%s）' % (act, b, e))
    return done


def main(argv=None):
    ap = argparse.ArgumentParser(description='把模板 UI 同步到所有阅读器')
    ap.add_argument('filters', nargs='*', help='只同步名字包含这些片段的课程')
    ap.add_argument('--check', action='store_true', help='只检查，不写入')
    args = ap.parse_args(argv)

    extra = unwatched_root_files()
    if extra:
        print('提示：模板根目录有未列入同步清单的静态文件：%s' % ', '.join(extra))
        print('      （若是新的 UI 文件，请加进 ROOT_SYNC）')

    readers = find_readers(args.filters)
    if not readers:
        print('没有找到阅读器（先跑 build.py）')
        return 1

    manifest = template_manifest()
    dirty = 0
    files = 0
    for name, reader_dir in readers:
        actions = plan_reader(reader_dir, manifest)
        if not actions:
            continue
        dirty += 1
        files += len(actions)
        if args.check:
            print('需要更新 %s：%s' % (name, ', '.join(os.path.basename(b) for _, _, b in actions)))
        else:
            run_actions(actions)
            print('同步 %s（%d 个文件）' % (name, len(actions)))

    if args.check:
        print('---- %d / %d 个阅读器需要同步，共 %d 个文件' % (dirty, len(readers), files))
    else:
        print('---- 已同步 %d / %d 个阅读器，共 %d 个文件' % (dirty, len(readers), files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
