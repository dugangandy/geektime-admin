#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一次性迁移：把「骨架写死在页面里」的旧正文页转成数据壳。

旧 pages/NNN.html  = 完整 HTML（标题 / 播放器 / 正文 / 导航 …）
新 pages/NNN.html  = 8 行外壳 + <script type="application/json" id="lesson-data">

内容零变动（不重新解析课程、不重排版），只把结构搬进 JSON，交给 assets/page.js 渲染。
迁移后改 UI 只需 sync_ui.py。

用法：
    python _阅读器模板/migrate_pages.py --dry-run          # 只统计
    python _阅读器模板/migrate_pages.py 超级访谈           # 迁移名字含该片段的课程
    python _阅读器模板/migrate_pages.py                    # 迁移全部
"""
import argparse
import os
import sys

from bs4 import BeautifulSoup

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import build  # noqa: E402  复用 PAGE_TPL / json_payload


def find_readers(filters):
    out = []
    for name in sorted(os.listdir(ROOT)):
        r = os.path.join(ROOT, name, 'reader')
        if not os.path.isfile(os.path.join(r, 'data', 'catalog.js')):
            continue
        if filters and not any(f in name for f in filters):
            continue
        out.append((name, r))
    return out


def inner(node):
    return node.decode_contents() if node else ''


def text_or(node, default=''):
    return node.get_text().strip() if node else default


def to_data(html):
    """旧页面 → 页面数据 dict。"""
    soup = BeautifulSoup(html, 'html.parser')
    body = soup.body
    if body is None:
        raise ValueError('没有 body')

    h1 = soup.select_one('h1.art-title')
    if h1 is None:
        raise ValueError('没有标题节点')

    classes = body.get('class') or []
    kind = next((c for c in classes if c.startswith('kind-')), 'kind-simple')

    pdf_node = soup.select_one('a.pdf-act')
    pdf = ''
    if pdf_node is not None and not pdf_node.has_attr('hidden'):
        pdf = pdf_node.get('href') or ''
        if pdf == '#':
            pdf = ''

    audio = body.get('data-audio') or ''
    if audio == '#':
        audio = ''

    def nav(which):
        node = soup.select_one('.art-nav .nav-item.' + which)
        if node is None:
            return None
        if 'off' in (node.get('class') or []):
            return None
        return {'url': node.get('href') or 'javascript:void(0)',
                'title': inner(node.select_one('.nav-title'))}

    style_node = soup.find('style')
    style = style_node.decode_contents().strip() if style_node else ''

    course = inner(soup.select_one('.art-course'))
    return {
        'idx': int(body.get('data-idx') or 0),
        'total': int(body.get('data-total') or 0),
        'kind': kind,
        'title': inner(h1),
        'course': course,
        'meta': inner(soup.select_one('.art-meta')),
        'cover': str(soup.select_one('figure.art-cover') or ''),
        'sub': inner(soup.select_one('.player-sub')),
        'duration': text_or(soup.select_one('#chipCur'), '--:--'),
        'audio': audio,
        'pdf': pdf,
        'body': inner(soup.select_one('#content')),
        'style': style,
        'prev': nav('prev'),
        'next': nav('next'),
    }


def to_page(html, data):
    title_node = BeautifulSoup(html, 'html.parser').find('title')
    page_title = title_node.get_text() if title_node else data['title']
    return (build.PAGE_TPL.replace('__TITLE__', page_title)
                           .replace('__DATA__', build.json_payload(data)))


def migrate_reader(reader_dir, dry_run=False):
    pages_dir = os.path.join(reader_dir, 'pages')
    done = skipped = failed = 0
    for name in sorted(os.listdir(pages_dir)):
        if not name.endswith('.html'):
            continue
        path = os.path.join(pages_dir, name)
        with open(path, 'r', encoding='utf-8') as f:
            html = f.read()
        if 'id="lesson-data"' in html:
            skipped += 1
            continue
        try:
            data = to_data(html)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print('  ! 跳过 %s（%s）' % (name, e))
            continue
        if dry_run:
            done += 1
            continue
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(to_page(html, data))
        done += 1
    return done, skipped, failed


def main(argv=None):
    ap = argparse.ArgumentParser(description='把旧正文页迁移成数据壳')
    ap.add_argument('filters', nargs='*', help='只迁移名字包含这些片段的课程')
    ap.add_argument('--dry-run', action='store_true', help='只统计，不写文件')
    args = ap.parse_args(argv)

    readers = find_readers(args.filters)
    if not readers:
        print('没有找到阅读器')
        return 1

    total = failed = 0
    for name, reader_dir in readers:
        done, skipped, fail = migrate_reader(reader_dir, args.dry_run)
        total += done
        failed += fail
        print('%-30s 迁移 %3d  已是新格式 %3d  失败 %d' % (name, done, skipped, fail))

    print('---- %s %d 个页面，失败 %d' % ('待迁移' if args.dry_run else '已迁移', total, failed))
    return 0 if failed == 0 else 2


if __name__ == '__main__':
    sys.exit(main())
