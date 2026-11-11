# -*- coding: utf-8 -*-
"""
把「课程总目录」index.html 里的课程卡片链接，指向各课程的离线阅读器。

用法：
    python _阅读器模板/relink_catalog.py            # 默认处理上一级（极客时间）目录
    python _阅读器模板/relink_catalog.py <目录>

规则：
    <课程>/index.html  ->  <课程>/reader/index.html   （仅当该课程已生成 reader 时）
    已经是 reader/index.html 的不会被重复替换（幂等）

首次运行会把原文件备份为 index.html.bak。
"""

import os
import re
import shutil
import sys
from urllib.parse import unquote

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(HERE)
IDX = os.path.join(ROOT, 'index.html')

TEXT_MAP = [
    ('暂无网页导航', '暂无正文页面'),
    ('33 门含网页导航', '33 门已生成阅读器'),
    ('有网页导航', '已生成阅读器'),
    ('点击进入课程导航页', '点击进入离线阅读器'),
    ('进入课程', '打开阅读器'),
]

if not os.path.isfile(IDX):
    raise SystemExit('找不到总目录文件: %s' % IDX)

with open(IDX, encoding='utf-8') as f:
    html = f.read()

hit = []


def link(m):
    enc = m.group(1)
    name = unquote(enc)
    if os.path.isfile(os.path.join(ROOT, name, 'reader', 'index.html')):
        hit.append(name)
        return 'href="%s/reader/index.html"' % enc
    return m.group(0)


html, _ = re.subn(r'href="([^"]+?)/index\.html"', link, html)

for a, b in TEXT_MAP:
    html = html.replace(a, b)

bak = IDX + '.bak'
if not os.path.isfile(bak):
    shutil.copyfile(IDX, bak)

with open(IDX, 'w', encoding='utf-8') as f:
    f.write(html)

print('已更新 %d 个课程链接 -> reader/index.html' % len(hit))
for n in sorted(hit):
    print('  ✓', n)
