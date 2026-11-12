#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「template/reader」的壳与前端资源同步到所有已生成的阅读器。

只同步 UI 文件（index.html + assets/*），不碰 pages/ 里的正文数据，
所以改完界面（结构 / 样式 / 脚本）跑一次即可全站生效，无需重新构建课程。

用法：
    python template/reader/sync_ui.py                 # 同步全部阅读器
    python template/reader/sync_ui.py --check         # 只检查哪些阅读器落后
    python template/reader/sync_ui.py Redis 10x       # 只同步名字含这些片段的课程
"""
import argparse
import filecmp
import hashlib
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TPL_INDEX = os.path.join(HERE, 'index.html')
TPL_ASSETS = os.path.join(HERE, 'assets')

# 模板根目录里要同步的文件（assets/ 整个目录会自动覆盖）
ROOT_SYNC = ('index.html',)
# 模板根目录里这些后缀出现新文件时提醒：说明同步清单该扩展了
ROOT_WATCH = ('.css', '.js', '.svg', '.ico', '.png', '.json', '.woff2')
# 旧版资源（已并入 page.js），同步时顺手删掉
STALE_ASSETS = ('article.js',)

# pages/NNN.html 里引用的资源：serve.py 对 /assets/ 发 7 天强缓存，
# 换掉 URL 上的 ?v= 指纹，已打开过课程的浏览器才会去取新文件
PAGE_ASSETS = ('page.js', 'article.css')
VER_RE = re.compile(r'(\.\./assets/(?:page\.js|article\.css))(?:\?v=[\w.-]+)?')

# 外壳 index.html 里引用的资源，规则与 build.py 的 SHELL_URL_RE 一致
SHELL_ASSETS = ('app.js', 'shell.css')
SHELL_RE = re.compile(r'(assets/(?:app\.js|shell\.css)|data/catalog\.js)(?:\?v=[\w.-]+)?')


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


def asset_ver(name):
    """资源指纹，与 build.py 的 asset_ver 保持一致（按文件内容，不看 mtime）。"""
    path = os.path.join(TPL_ASSETS, name)
    try:
        with open(path, 'rb') as f:
            return hashlib.sha1(f.read()).hexdigest()[:10]
    except OSError:
        return '0'


def file_ver(path):
    """按文件内容取指纹；文件不存在返回 '0'。"""
    try:
        with open(path, 'rb') as f:
            return hashlib.sha1(f.read()).hexdigest()[:10]
    except OSError:
        return '0'


def render_shell(reader_dir, vers=None):
    """外壳 HTML：assets 用模板指纹，data/catalog.js 用该阅读器自己的内容指纹。

    目录数据是每门课各一份，指纹必须按 reader/data/catalog.js 现算；
    这样课程重建后新加/改名的讲次不会因为 1 天缓存看不到。
    """
    with open(TPL_INDEX, 'r', encoding='utf-8') as f:
        text = f.read()
    vers = vers or {n: asset_ver(n) for n in SHELL_ASSETS}

    def repl(m):
        key = m.group(1)
        ver = file_ver(os.path.join(reader_dir, key)) if key.startswith('data/') \
            else vers.get(os.path.basename(key), '0')
        return '%s?v=%s' % (key, ver)

    return SHELL_RE.sub(repl, text)


def sync_page_ver(reader_dir, vers, dry=False):
    """把 pages/*.html 里的 page.js / article.css 版本串换成当前指纹。

    返回改动（dry 时为「将改动」）的页面数。老版本页面没有 ?v= 也会补上。
    """
    pages = os.path.join(reader_dir, 'pages')
    if not os.path.isdir(pages):
        return 0
    changed = 0
    for name in sorted(os.listdir(pages)):
        if not name.endswith('.html'):
            continue
        path = os.path.join(pages, name)
        try:
            with open(path, 'r', encoding='utf-8') as f:
                text = f.read()
        except OSError:
            continue

        def repl(m):
            return '%s?v=%s' % (m.group(1), vers.get(os.path.basename(m.group(1)), '0'))

        new = VER_RE.sub(repl, text)
        if new == text:
            continue
        changed += 1
        if dry:
            continue
        try:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(new)
        except OSError as e:
            print('  ! 写 %s 失败：%s（%s）' % (path, e))
    return changed


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


def plan_reader(reader_dir, manifest, shell_vers=None):
    """返回待执行的动作列表：[('copy', src, dst) | ('write', text, dst) | ('del', dst)]。"""
    actions = []
    for _, src, rel in manifest:
        if os.path.basename(rel) in STALE_ASSETS:
            continue
        dst = os.path.join(reader_dir, rel)
        if rel == 'index.html':
            # 外壳要按当前资源指纹重新渲染，不能直接比对模板原文
            want = render_shell(reader_dir, shell_vers)
            try:
                with open(dst, 'r', encoding='utf-8') as f:
                    have = f.read()
            except OSError:
                have = None
            if have != want:
                actions.append(('write', want, dst))
            continue
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
            elif act == 'write':        # 外壳按新指纹渲染后的 HTML
                with open(b, 'w', encoding='utf-8') as f:
                    f.write(a)
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
    vers = {n: asset_ver(n) for n in PAGE_ASSETS}
    shell_vers = {n: asset_ver(n) for n in SHELL_ASSETS}
    dirty = 0
    files = 0
    for name, reader_dir in readers:
        actions = plan_reader(reader_dir, manifest, shell_vers)
        # 资源内容变了，pages 里的 ?v= 也要跟着变，否则旧缓存继续命中
        pages_fixed = sync_page_ver(reader_dir, vers, dry=args.check)
        if not actions and not pages_fixed:
            continue
        dirty += 1
        files += len(actions)
        tail = '，%d 个页面版本串' % pages_fixed if pages_fixed else ''
        if args.check:
            items = [os.path.basename(b) for _, _, b in actions]
            if pages_fixed:
                items.append('%d 个页面的资源版本串' % pages_fixed)
            print('需要更新 %s：%s' % (name, ', '.join(items)))
        else:
            run_actions(actions)
            print('同步 %s（%d 个文件%s）' % (name, len(actions), tail))

    if args.check:
        print('---- %d / %d 个阅读器需要同步，共 %d 个文件' % (dirty, len(readers), files))
    else:
        print('---- 已同步 %d / %d 个阅读器，共 %d 个文件' % (dirty, len(readers), files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
