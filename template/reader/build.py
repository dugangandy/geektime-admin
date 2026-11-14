# -*- coding: utf-8 -*-
"""
极客时间课程 · 离线阅读器模板
============================

把一份「已下载的极客时间课程目录」构建成可离线浏览的导航 + 阅读页面，
版式参照极客时间官方的专栏阅读页（左侧课程目录 / 顶部栏 / 正文 / 右侧工具条）。

用法
----
    # 最简：在课程目录里生成 reader/
    python build.py "E:/.../极客时间/设计模式之美"

    # 自定义标题 / 作者 / 头衔（不指定则自动推断）
    python build.py "<课程目录>" --title "设计模式之美" --author "王争" --role "Google 工程师"

    # 输出到别的位置
    python build.py "<课程目录>" --out "E:/out/reader"

支持的课程目录布局
------------------
A. 分章节目录
     <课程>/00丨开篇词 (1讲)/00丨开篇词丨xxx.html
     <课程>/00丨开篇词 (1讲)/00丨开篇词丨xxx.m4a  (+ .pdf)
   章节顺序取目录名前缀数字；章内顺序优先按文章发布日期（与官网一致）。

B. 扁平目录
     <课程>/html/01 xxx.html
     <课程>/01 xxx.mp3
     <课程>/pdf/01 xxx.pdf
   没有章节信息时，按文件名自动归类为 开篇 / 导读 / 正文 / 加餐 / 测试 / 尾声 / 其他。

C. 只有 Markdown（没有 html）
     <课程>/01 xxx.md
     <课程>/images/<id>/xxx.png      （md 里用 ![](images/<id>/xxx.png) 引用）
   md 与 md 同名附件（mp3/pdf）会按文件名自动配对；同目录同时存在同名 .html 时以 html 为准。

D. 纯视频 / 纯音频（没有文字稿）
     <课程>/01 xxx.mp4          （或 视频/01 xxx.mp4、mp3/01 xxx.mp3）
   媒体文件自己就是一讲：视频生成 <video> 播放页，音频生成 <audio> 播放条；
   同序号只是附件的文件不单独成讲。视频优先于音频（同一讲两者都有时取视频）。

支持的正文来源（自动识别）
--------------------------
1. 极客时间原站「整页保存」：正文在 div._1Bj6HN3x_0，样式来自 se-* 的 <style data-jss>
2. 纯净版页面：正文在 div.content / div.text / article ...
3. Markdown（.md）：用 python-markdown 渲染（extra + sane_lists），图片路径按 pages/ 目录重写

产物
----
    <out>/index.html      外壳（目录 + 阅读区）
    <out>/assets/*        样式与脚本
    <out>/data/catalog.js 目录数据
    <out>/pages/NNN.html  每一讲的正文页

重复执行是幂等的：只会重写以上产物，不动课程正文文件。
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote, unquote

from bs4 import BeautifulSoup

try:                # 可选依赖：读音频时长（纯净版页面没有原站的时长信息）
    from mutagen import File as _MutagenFile
except ImportError:  # pragma: no cover
    _MutagenFile = None

try:                # 可选依赖：只有 md 没有 html 的课程需要
    import markdown as _markdown
except ImportError:  # pragma: no cover
    _markdown = None

try:                # 可选依赖：只用来读 PDF 页数，缺了也能正常构建（只是不显示页数）
    import pymupdf as _fitz
except ImportError:  # pragma: no cover
    _fitz = None

AUDIO_EXTS = ('.m4a', '.mp3', '.aac', '.wav', '.ogg', '.flac', '.opus')
# 视频讲次：只收浏览器 / WebView 原生能播的容器，mkv、flv 之类不列（改为附件亦不可播）
VIDEO_EXTS = ('.mp4', '.m4v', '.webm', '.mov')

PAGES_DIR = ''      # 输出 pages 目录，md 转 html 时用于重写图片相对路径
OUT_DIR = ''        # 阅读器输出根目录

# ================================================================ 配置区

HERE = os.path.dirname(os.path.abspath(__file__))
TPL_INDEX = os.path.join(HERE, 'index.html')
TPL_ASSETS = os.path.join(HERE, 'assets')

OUT_NAME = 'reader'                 # 默认输出目录名
# 课程目录里的产物目录，扫描正文/附件时一律跳过
# （mp4-opt 是 faststart_mp4.py 的默认输出目录，里面的 mp4 只是副本）
SKIP_DIRS = (OUT_NAME, 'mp4-opt')
DEFAULT_ROLE = ''                   # 默认头衔，可用 --role 覆盖

# 附件子目录名（pdf/ mp3/ 音频/ …）。为空表示自动扫描课程根目录下的一级子目录，
# 可用 --asset-dirs 显式限定，避免同名文件被误配。
ASSET_DIRS = ()

# 能当正文的文件后缀（纯音频目录只能当附件目录，不能当章节）
TEXT_EXTS = ('.html', '.md', '.pdf')

# 正文容器候选（按优先级）
CONTENT_SELECTORS = [
    '._1Bj6HN3x_0',                 # 极客时间原站整页保存（旧版）
    'div._3IatBmhv_0',              # 极客时间原站整页保存（新版哈希类名）
    '[class*="SlateRichContent_main"]',   # 新版整页保存：CSS Modules 哈希类名
    '[class*="Index_articleContent"]',
    '[data-slate-editor]',                # 新版编辑器正文（类名全哈希，只能靠 slate 属性定位）
    'div.content',                  # 纯净版（最常见）
    'div.text',                     # 早期纯净版
    'article',
    '.article-content',
    '#article-content',
    '.rich_media_content',
]

# 整页保存的页面里，这些属性既不参与样式匹配又占体积，构建时丢弃
STRIP_ATTRS = (
    'data-key', 'data-offset-key', 'data-annotation-key', 'data-annotation-type',
    'data-gramm', 'autocorrect', 'spellcheck', 'data-savepage-src',
    'data-slate-leaf', 'data-slate-object', 'data-v-99cd8b4a',
)

# 纯净版页面里的正文容器，重建时清掉这些标签
DROP_TAGS = ('script', 'style', 'noscript', 'iframe', 'audio', 'video', 'button')

# 扁平目录下，按文件名关键词分组（顺序即显示顺序）
GROUP_RULES = [
    ('开篇词', ('开篇词', '开篇', '发刊词', '先导篇', '学前必读', '课程介绍')),
    ('导读', ('导读', '序言', '前言', '序章', '学习攻略', '学习指南', '食用指南', '作者简介')),
    ('加餐', ('加餐', '特别放送', '特别福利', '答疑', '直播', '返场', '大咖助阵',
              '大咖对话', '大咖问答', '用户故事', '编辑手记', '课外阅读', '课程迭代', '温故',
              '春节', '新春', '元旦', '彩蛋')),
    ('测试', ('结课测试', '期末测试', '结课', '自测', '毕业测试', '期中测试', '满分试卷')),
    ('尾声', ('结束语', '结束篇', '尾声', '结语', '结业', '后记', '彩蛋')),
]
GROUP_ORDER = ['开篇词', '导读', '正文', '加餐', '测试', '尾声', '其他']

# 有编号的文章默认算「正文」；只有这些强特征词才允许把它挪到别的分组
# （"52 答疑：…" 这类是正常讲次，不能因为含"答疑"就被归到加餐）
NUM_OVERRIDE_RULES = [
    ('开篇词', ('开篇词', '开篇', '发刊词', '先导篇', '课程介绍')),
    ('测试', ('结课测试', '期末测试', '结课', '自测', '毕业测试', '满分试卷')),
    ('尾声', ('结束语', '尾声', '结业', '后记')),
]

CN_NUM = {'一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8,
          '九': 9, '十': 10, '十一': 11, '十二': 12, '十三': 13, '十四': 14, '十五': 15}

# 章节目录：`01-开篇词`、`01丨基础篇`、`03-API篇`，也兼容 `1开篇词` 这种数字紧贴中文的写法
CH_DIR_RE = re.compile(r'^\s*(\d+)\s*(?:[丨|｜\-—–_.、,，:：]\s*)?([A-Za-z\u4e00-\u9fa5].*)$')
CH_TAIL_RE = re.compile(r'[（(]\s*(\d+)\s*讲\s*[）)]\s*$')
FILE_NO_RE = re.compile(r'^\s*(?:第\s*)?(\d+)\s*(?:讲|课|节|章)?\s*[\s.、,，_丨|｜:：\-—]*\s*(.*)$')
# 标题分隔符：用于取「标题主干」（第一个分隔符之前的部分）
HEAD_SPLIT_RE = re.compile(r'[丨|｜:：]')


# ================================================================ 小工具

def clean(s):
    return re.sub(r'\s+', ' ', s or '').strip()


def cn_to_int(s):
    return CN_NUM.get(s, 99)


def date_from_name(stem):
    """从文件名推断发布日期，返回 YYYY-MM-DD；推断不出返回空串。

    支持「2017-10-10_标题」（改名后的扁平布局）与「171010-01 _ 标题」（旧命名）。
    """
    m = re.match(r'^(\d{4})[-_.](\d{1,2})[-_.](\d{1,2})', stem)
    if m:
        return '%s-%02d-%02d' % (m.group(1), int(m.group(2)), int(m.group(3)))
    m = re.match(r'^(\d{2})(\d{2})(\d{2})[-_]', stem)
    if m:
        return '20%s-%s-%s' % (m.group(1), m.group(2), m.group(3))
    return ''


def audio_duration(path):
    """读取音频时长（mm:ss）。没有 mutagen 或读取失败时返回空串。"""
    if not path or _MutagenFile is None:
        return ''
    try:
        info = _MutagenFile(path)
        sec = int(round(info.info.length)) if info is not None and info.info else 0
    except Exception:  # noqa: BLE001
        return ''
    if sec <= 0:
        return ''
    return '%02d:%02d' % (sec // 60, sec % 60)


def title_no(label):
    """取标题里的讲次序号（「01 | xxx」→ 1），无序号返回 None。"""
    m = re.match(r'^(\d+)\s*\|', label or '')
    return int(m.group(1)) if m else None


def rel_url(target_abs, start_dir):
    """目标文件相对 start_dir 的 URL（跨盘符时返回 None）。"""
    try:
        rel = os.path.relpath(target_abs, start_dir)
    except ValueError:
        return None
    return quote(rel.replace('\\', '/'))


def sub_dirs(root):
    """课程根目录下的一级子目录（跳过产物目录和以下划线开头的目录）。"""
    try:
        names = sorted(os.listdir(root))
    except OSError:
        return []
    out = []
    for n in names:
        p = os.path.join(root, n)
        if n.startswith(('_', '.')) or n in SKIP_DIRS or not os.path.isdir(p):
            continue
        out.append(p)
    return out


def asset_roots(root):
    """附件可能所在的目录：--asset-dirs 指定时只用这些，否则自动扫描一级子目录。"""
    if ASSET_DIRS:
        return [os.path.join(root, d) for d in ASSET_DIRS]
    return sub_dirs(root)


def norm_key(name):
    """文件名归一化：去掉方括号广告后缀、空白与常见分隔符。

    用于容忍「01 xxx」与「01-xxx」这类差异，以及分发方在文件名里塞的
    「【耗时整理‖免费分享 xxx】」之类后缀。
    """
    name = re.sub(r'[【\[［][^】\]］]*[】\]］]', '', name or '')
    # 分发方给附件追加的随机后缀，如「03 标题_XQ2dmG7iY1」，与正文名无关
    name = re.sub(r'_[A-Za-z0-9]{8,12}$', '', name)
    # 末尾语气标点也常被下载工具漏掉/多写（html 无「？」而 mp3 有），一并剔除
    return re.sub(r'[\s\-_—–·・.、,，:：|丨｜?？!！]+', '', name).lower()


def find_asset(stem, html_dir, root, exts):
    """在常见位置找同名附件，返回绝对路径。

    查找顺序：正文同目录 → 课程根目录 → 附件子目录（pdf/、mp3/、audio/、音频/ …）。
    子目录名不写死，用 --asset-dirs 可以显式限定，防止同名文件误配。
    精确同名找不到时，再按归一化文件名（忽略空格/短横等分隔符）兜底匹配一次。
    """
    exts = tuple(e.lower() for e in exts)
    cands = []
    for e in exts:
        cands.append(os.path.join(html_dir, stem + e))
        cands.append(os.path.join(root, stem + e))
    for sub in asset_roots(root):
        for e in exts:
            cands.append(os.path.join(sub, stem + e))
    for e in exts:
        cands.append(os.path.join(os.path.dirname(html_dir), stem + e))
    for c in cands:
        if os.path.isfile(c):
            return c

    # 兜底：忽略分隔符差异，如 html「01 标题」对应 pdf「01-标题」
    key = norm_key(stem)
    if not key:
        return None
    dirs = [html_dir, root, os.path.dirname(html_dir)] + asset_roots(root)
    for d in dirs:
        if not os.path.isdir(d):
            continue
        try:
            names = sorted(os.listdir(d))
        except OSError:
            continue
        for f in names:
            fstem, fext = os.path.splitext(f)
            if fext.lower() in exts and norm_key(fstem) == key:
                return os.path.join(d, f)
    return None


# ================================================================ 目录扫描

# 无编号章节目录的排序权重（越小越靠前）
SECTION_HINTS = [
    ('导读', 5), ('开篇', 10), ('背景', 20), ('理论', 30), ('基础', 35), ('实践', 40),
    ('项目', 50), ('实战', 55), ('进阶', 60), ('加餐', 70), ('放送', 70),
    ('答疑', 80), ('结束', 90), ('尾声', 90),
]


def section_rank(name):
    for key, rank in SECTION_HINTS:
        if key in name:
            return rank
    return 65


def is_chapter_dir(name):
    return bool(CH_DIR_RE.match(name)) or bool(CH_TAIL_RE.search(name))


def chapter_meta(name):
    m = CH_DIR_RE.match(name)
    if m:
        no, rest = int(m.group(1)), m.group(2)
    else:
        no, rest = 9999, name
    rest = CH_TAIL_RE.sub('', rest).strip()
    return no, rest or name


def file_order(name):
    """文件名排序键：数字前缀优先，其次中文数字（加餐一/二…），最后按名称。"""
    stem = os.path.splitext(name)[0]
    m = FILE_NO_RE.match(stem)
    if m:
        return (0, int(m.group(1)), stem)
    m2 = re.match(r'^[^\d]{0,6}?([一二三四五六七八九十]+)', stem)
    if m2:
        return (1, cn_to_int(m2.group(1)), stem)
    return (2, 0, stem)


def file_label(name, no=None):
    stem = os.path.splitext(name)[0]
    m = FILE_NO_RE.match(stem)
    if m:
        no = no if no is not None else m.group(1)
        rest = m.group(2).strip()
        if rest:
            return '%s | %s' % (no, rest)
    return stem


def stem_no(stem):
    """文件名开头的讲次序号，取不到返回 None。"""
    m = FILE_NO_RE.match(stem)
    return int(m.group(1)) if m else None


def article_files(dir_path):
    """目录下的正文文件：.html 优先，其次 .md，最后才是 .pdf（纯 PDF 课程）。

    没有任何文字的讲次按媒体文件成讲：视频（mp4/m4v/webm/mov）优先于音频。
    """
    try:
        names = os.listdir(dir_path)
    except OSError:
        return []
    names = [f for f in names if not f.startswith(('_', '.'))]   # 跳过临时/隐藏文件
    picked = {os.path.splitext(f)[0]: f for f in names if f.lower().endswith('.md')}
    picked.update({os.path.splitext(f)[0]: f for f in names if f.lower().endswith('.html')})
    # PDF：已有同名 html/md 的视为附件；名字对不上的（同一讲命名不一致）也视为附件；
    # 只有「没有对应 html/md」的 PDF 才单独作为一讲（如只有 PDF 的结束语/问卷）
    seen = {norm_key(stem) for stem in picked}
    # 同序号的 PDF 只当附件：下载来的 PDF 常见尾字被多打/少打（如 MapReducel.pdf
    # 对应 MapReduce.html），按名字归一化对不上，但显然不是新的一讲
    taken_nos = {n for n in (stem_no(s) for s in picked) if n is not None}
    for f in names:
        if not f.lower().endswith('.pdf'):
            continue
        stem = os.path.splitext(f)[0]
        if norm_key(stem) in seen or stem_no(stem) in taken_nos:
            continue
        picked[stem] = f
        seen.add(norm_key(stem))
    # 视频讲次（纯视频课程的主体）：排在音频之前，同一讲既有 mp4 又有 mp3 时取视频
    for f in names:
        if os.path.splitext(f)[1].lower() not in VIDEO_EXTS:
            continue
        stem = os.path.splitext(f)[0]
        if norm_key(stem) in seen or stem_no(stem) in taken_nos:
            continue
        picked[stem] = f
        seen.add(norm_key(stem))
    # 只有音频、没有文字的讲次也保留（生成只有播放器的占位页）
    for f in names:
        if os.path.splitext(f)[1].lower() not in AUDIO_EXTS:
            continue
        stem = os.path.splitext(f)[0]
        # 同序号的音频同理只当附件：改名副本（分隔符不同、标题带 ×）会让一讲变两讲
        if norm_key(stem) in seen or stem_no(stem) in taken_nos:
            continue
        picked[stem] = f
        seen.add(norm_key(stem))
    return sorted(picked.values())


def scan_course(course_dir, content_dir=None):
    """返回 (chapters, flat_dir)。chapters: [{'no','name','dir','files'}]"""
    chapters = []
    for name in sorted(os.listdir(course_dir)):
        p = os.path.join(course_dir, name)
        if not os.path.isdir(p) or name in SKIP_DIRS or name.startswith('_'):
            continue
        if not is_chapter_dir(name):
            continue
        files = article_files(p)
        if files:
            no, title = chapter_meta(name)
            chapters.append({'no': no, 'name': title, 'dir': p, 'files': files})

    chapters.sort(key=lambda c: (c['no'], c['name']))

    # 同一讲被放到别的章节目录里的错名音频副本（如「结束语 行百里者半九十.mp3」
    # 落在性能优化篇），别处已有同名正文，不再单独生成占位讲
    text_keys = {norm_key(os.path.splitext(f)[0])
                 for c in chapters for f in c['files']
                 if f.lower().endswith(('.html', '.md', '.pdf'))}
    for c in chapters:
        c['files'] = [f for f in c['files']
                      if not (os.path.splitext(f)[1].lower() in AUDIO_EXTS
                              and norm_key(os.path.splitext(f)[0]) in text_keys)]

    flat = None
    if not chapters:
        if content_dir:
            cand = os.path.join(course_dir, content_dir)
            flat = cand if os.path.isdir(cand) else None
        if flat is None and os.path.isdir(os.path.join(course_dir, 'html')):
            flat = os.path.join(course_dir, 'html')
        # 兜底：章节目录没有编号（如「开篇词」「历史背景篇」），把含正文的子目录当章节
        if chapters == [] and flat is None:
            subs_text, subs_pdf, subs_video = [], [], []
            for name in sorted(os.listdir(course_dir)):
                p = os.path.join(course_dir, name)
                if not os.path.isdir(p) or name in SKIP_DIRS or name.startswith('_'):
                    continue
                files = article_files(p)
                if not files:
                    continue
                has_text = any(f.lower().endswith(('.html', '.md')) for f in files)
                has_pdf = any(f.lower().endswith('.pdf') for f in files)
                has_video = any(f.lower().endswith(VIDEO_EXTS) for f in files)
                if not has_text and not has_pdf:
                    if has_video:
                        subs_video.append(p)    # 视频/、video/ 这类整目录放视频的布局
                    continue        # 纯音频目录（MP3/、mp3/）是附件目录，不能当章节
                item = {'no': section_rank(name), 'name': name, 'dir': p, 'files': files}
                (subs_text if has_text else subs_pdf).append(item)
            # 优先级：文字 > 视频 > 纯 PDF。视频课常把讲义 PDF 放在 课件/ 里，
            # 那些目录是附件，不能因为「有 pdf」就把整门课当 PDF 课程。
            if subs_text:
                subs_text.sort(key=lambda c: (c['no'], c['name']))
                return subs_text, None
            if len(subs_video) == 1:
                return [], subs_video[0]    # 只有视频的子目录，整目录当扁平目录
            if subs_pdf:
                subs_pdf.sort(key=lambda c: (c['no'], c['name']))
                return subs_pdf, None

        root_files = article_files(course_dir)
        if flat is None and any(f.lower().endswith(('.html', '.md')) for f in root_files):
            flat = course_dir
        if flat is None:
            # md/ pdf/ 之类子目录，目录名大小写不敏感（PDF/、MP3/）
            for sub in ('md', 'pdf'):
                cand = next((os.path.join(course_dir, n) for n in os.listdir(course_dir)
                             if n.lower() == sub and os.path.isdir(os.path.join(course_dir, n))), None)
                if cand:
                    flat = cand
                    break
        if flat is None and root_files:
            flat = course_dir
    return chapters, flat


def head_segment(stem):
    """标题主干：去掉开头编号，只保留第一个分隔符（丨/：）之前的部分。

    「69丨程序员练级攻略：开篇词」这类讲次的特征词只是副标题（系列开篇），
    主干是「程序员练级攻略」，不应因此被归到课程的「开篇词」分组。
    """
    m = FILE_NO_RE.match(stem)
    rest = (m.group(2) if m else stem) or ''
    return HEAD_SPLIT_RE.split(rest, 1)[0].strip()


def group_flat(files):
    """扁平目录：按文件名规则分组，返回 [(组名, [file...])]"""
    buckets = {}
    for f in files:
        stem = os.path.splitext(f)[0]
        if FILE_NO_RE.match(stem):
            g = '正文'
            head = head_segment(stem)
            for gname, kws in NUM_OVERRIDE_RULES:
                if any(k in head for k in kws):
                    g = gname
                    break
        else:
            g = '其他'
            for gname, kws in GROUP_RULES:
                if any(k in stem for k in kws):
                    g = gname
                    break
        buckets.setdefault(g, []).append(f)
    out = []
    for g in GROUP_ORDER:
        if g in buckets:
            out.append((g, sorted(buckets[g], key=file_order)))
    return out


# ================================================================ 单页解析

def pick_content(soup):
    for sel in CONTENT_SELECTORS:
        el = soup.select_one(sel)
        if el is not None and len(el.get_text(strip=True)) > 5:
            return el
    return guess_content(soup)


def guess_content(soup):
    """兜底：类名全哈希时，取文本量最大的 div，再下钻到仍保留 ≥90% 文本的最深子节点。"""
    divs = [d for d in soup.find_all('div') if len(d.get_text(strip=True)) > 200]
    if not divs:
        return None
    best = max(divs, key=lambda d: len(d.get_text(strip=True)))
    limit = len(best.get_text(strip=True)) * 0.9
    while True:
        child = [c for c in best.find_all('div', recursive=False)
                 if len(c.get_text(strip=True)) >= limit]
        if not child:
            break
        best = child[0]
    return best if len(best.get_text(strip=True)) > 200 else None


def filter_css(soup, classes):
    """整页保存：从 <style> 里筛出命中正文类名的规则。"""
    pats = [re.compile(r'(?<![\w-])\.' + re.escape(c) + r'(?![\w-])')
            for c in sorted(classes, key=len, reverse=True)]
    if not pats:
        return ''

    def hit(text):
        return any(p.search(text) for p in pats)

    out = []
    for st in soup.find_all('style'):
        text = st.string or ''
        if not text:
            continue
        if st.has_attr('data-jss'):
            if hit(text):
                out.append(text.strip())
        else:
            for m in re.finditer(r'([^{}]+)\{([^{}]*)\}', text):
                if hit(m.group(1)):
                    out.append('%s{%s}' % (clean(m.group(1)), m.group(2).strip()))
    return '\n'.join(out)


def convert_mathjax(content):
    """把 MathJax 2 的 HTML-CSS 排版结果还原成原生 MathML。

    原页面靠一套绝对定位的 span + 内联样式把公式“画”出来，去掉这些样式后
    公式会串成一行乱码；data-mathml 里保留了等价的 MathML，直接换回来最稳。
    """
    for el in content.find_all(attrs={'data-mathml': True}):
        try:
            math = BeautifulSoup(el['data-mathml'], 'lxml').find('math')
        except Exception:  # noqa: BLE001
            math = None
        if math is None:
            continue
        holder = el
        parent = el.parent
        if parent is not None and 'MathJax_Display' in (parent.get('class') or []):
            holder = parent
        holder.replace_with(math)

    for tag in content.select('span.MathJax_Preview'):
        tag.decompose()
    for tag in content.find_all('script', type='math/tex'):
        tag.decompose()


def normalize_title(t):
    """把「02讲日志系统：…」「02丨日志系统…」统一成官方的「02 | 日志系统…」。"""
    t = clean(t).replace('丨', '|').replace('｜', '|')
    t = re.sub(r'\s*\|\s*', ' | ', t).strip()
    m = re.match(r'^(\d+)\s*\|\s*(.+)$', t)
    if m and m.group(2).strip():
        return '%02d | %s' % (int(m.group(1)), m.group(2).strip())
    # 「02讲日志系统」这类：讲/课/节 必须紧贴数字（不能隔着空格），
    # 否则「00 课前准备 xxx」会被吞掉「课」字
    m = re.match(r'^(\d+)[讲课节]\s*(.+)$', t)
    if m and len(m.group(2)) > 1:
        return '%02d | %s' % (int(m.group(1)), m.group(2).strip())
    # 「00 课前准备 xxx」这类：数字与标题之间必须有分隔符，
    # 否则 (?:讲|课|节)? 会把标题首字「课」吞掉（曾把「课前准备」变成「前准备」）
    m = re.match(r'^(\d+)\s+([^.、,，_丨|｜:：\-—\s].*)$', t)
    if m and len(m.group(2)) > 1:
        return '%02d | %s' % (int(m.group(1)), m.group(2).strip())
    return t


def md_to_html(raw, md_dir):
    """Markdown 转正文 HTML：渲染 + 本地图片路径改写成相对 pages/ 的 URL。"""
    if _markdown is None:
        raise RuntimeError('缺少 markdown 库，请先执行：python -m pip install markdown')
    html = _markdown.markdown(raw, extensions=['extra', 'sane_lists'])
    soup = BeautifulSoup('<div id="md-root">%s</div>' % html, 'lxml')
    root = soup.find(id='md-root')

    # 首个一级标题与页面标题重复，去掉
    first = root.find('h1')
    if first is not None:
        first.decompose()

    for img in root.find_all('img'):
        src = (img.get('src') or '').strip()
        if not src:
            img.decompose()
            continue
        if re.match(r'^(https?:|data:|//)', src):
            continue
        cand = os.path.normpath(os.path.join(md_dir, unquote(src)))
        url = rel_url(cand, PAGES_DIR) if os.path.isfile(cand) and PAGES_DIR else None
        if url:
            img['src'] = url
            img.attrs.pop('loading', None)
        else:
            img.decompose()     # 本地图片缺失时不留破图

    return root.decode_contents().strip()


def parse_md_page(task):
    """解析一讲 Markdown 页面。"""
    ci, ch_name, path, root = task
    md_dir = os.path.dirname(path)
    stem, _ = os.path.splitext(os.path.basename(path))

    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        raw = f.read()

    label = ''
    m = re.search(r'^\s*#\s+(.+)$', raw, re.M)
    if m:
        label = normalize_title(m.group(1))
    if not label:
        label = normalize_title(file_label(os.path.basename(path)))

    audio_file = find_asset(stem, md_dir, root, ('.m4a', '.mp3', '.aac', '.wav'))
    video_file = find_asset(stem, md_dir, root, VIDEO_EXTS)
    pdf_file = find_asset(stem, md_dir, root, ('.pdf',))

    return {
        'ci': ci,
        'ch': ch_name,
        'path': path,
        'label': label,
        'no': title_no(label),
        'date': date_from_name(stem),
        'author': '',
        'course_name': '',
        'duration': audio_duration(audio_file) if audio_file else '',
        'speaker': '',
        'size': '',
        'cover': '',
        'css': '',
        'body': md_to_html(raw, md_dir),
        'audio': audio_file,
        'video': video_file,
        'pdf': pdf_file,
        'kind': 'md',
    }


def pdf_page_count(pdf_path):
    """PDF 页数；pymupdf 未安装或读取失败时返回 0（正文里就不显示页数）。"""
    if _fitz is None:
        return 0
    try:
        doc = _fitz.open(pdf_path)
        try:
            return doc.page_count
        finally:
            doc.close()
    except Exception:  # noqa: BLE001
        return 0


PDF_SRC_TOKEN = '{{PDF_SRC}}'


def pdf_viewer_html(pages):
    """内嵌浏览器原生 PDF 阅读器；PDF 地址由 render_page 按 pages 目录补成相对路径。"""
    tip = ('本讲正文为 PDF 原文%s，正文用浏览器内置 PDF 阅读器打开，可自由缩放、选中复制与检索；'
           '若此处显示空白，请点上方「新窗口打开」查看原文件。'
           % ('（共 %d 页）' % pages if pages else ''))
    return (
        '<div class="pdf-view">\n'
        '  <div class="pdf-view-bar">\n'
        '    <span class="pdf-view-name">PDF 原文</span>\n'
        '    <a class="pdf-view-btn" href="' + PDF_SRC_TOKEN + '" target="_blank" rel="noopener">新窗口打开</a>\n'
        '  </div>\n'
        '  <object class="pdf-frame" data="' + PDF_SRC_TOKEN + '#view=FitH" type="application/pdf">\n'
        '    <div class="pdf-fallback">当前浏览器无法内嵌显示 PDF，'
        '<a href="' + PDF_SRC_TOKEN + '" target="_blank" rel="noopener">点此打开原文件</a>。</div>\n'
        '  </object>\n'
        '  <p class="pdf-tip">' + tip + '</p>\n'
        '</div>\n')


def parse_pdf_page(task):
    """解析一讲纯 PDF 页面：正文内嵌浏览器原生 PDF 阅读器，保留原始矢量清晰度。"""
    ci, ch_name, path, root = task
    pdf_dir = os.path.dirname(path)
    stem = os.path.splitext(os.path.basename(path))[0]
    clean_stem = re.sub(r'[【\[［][^】\]］]*[】\]］]', '', stem).strip()

    audio_file = find_asset(stem, pdf_dir, root, ('.m4a', '.mp3', '.aac', '.wav'))
    video_file = find_asset(stem, pdf_dir, root, VIDEO_EXTS)
    body = pdf_viewer_html(pdf_page_count(path))

    return {
        'ci': ci,
        'ch': ch_name,
        'path': path,
        'label': normalize_title(file_label(clean_stem)),
        'no': title_no(normalize_title(file_label(clean_stem))),
        'date': date_from_name(clean_stem),
        'author': '',
        'course_name': '',
        'duration': audio_duration(audio_file) if audio_file else '',
        'speaker': '',
        'size': '',
        'cover': '',
        'css': '',
        'body': body,
        'audio': audio_file,
        'video': video_file,
        'pdf': path,
        'kind': 'pdf',
    }


def _media_page(task, key, body):
    """只有素材、没有文字稿的讲次：正文放提示，播放器照常可用。

    key 为 'audio' 或 'video'，决定播放器用哪种媒体元素。
    """
    ci, ch_name, path, root = task
    media_dir = os.path.dirname(path)
    stem = os.path.splitext(os.path.basename(path))[0]
    label = normalize_title(file_label(os.path.basename(path)))
    return {
        'ci': ci,
        'ch': ch_name,
        'path': path,
        'label': label,
        'no': title_no(label),
        'date': date_from_name(stem),
        'author': '',
        'course_name': '',
        'duration': audio_duration(path),   # mutagen 也能读 mp4，读不出就留空
        'speaker': '',
        'size': '',
        'cover': '',
        'css': '',
        'body': body,
        'audio': path if key == 'audio' else None,
        'video': path if key == 'video' else None,
        'pdf': find_asset(stem, media_dir, root, ('.pdf',)),
        'kind': 'simple',
    }


def parse_audio_page(task):
    """解析只有音频、没有文字稿的讲次。"""
    return _media_page(task, 'audio',
                       '<p class="empty-body">本讲暂无文字稿，可使用上方播放器收听。</p>')


def parse_video_page(task):
    """解析只有视频、没有文字稿的讲次。"""
    return _media_page(task, 'video',
                       '<p class="empty-body">本讲暂无文字稿，可使用上方视频播放器观看。</p>')


def parse_page(task):
    """解析一讲。task = (chapter_idx, chapter_name, html_path, root)"""
    ci, ch_name, path, root = task
    if path.lower().endswith('.md'):
        return parse_md_page(task)
    if path.lower().endswith('.pdf'):
        return parse_pdf_page(task)
    ext = os.path.splitext(path)[1].lower()
    if ext in VIDEO_EXTS:
        return parse_video_page(task)
    if ext in AUDIO_EXTS:
        return parse_audio_page(task)
    html_dir = os.path.dirname(path)
    stem, _ = os.path.splitext(os.path.basename(path))

    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        raw = f.read()

    soup = BeautifulSoup(raw, 'lxml')
    content = pick_content(soup)
    if content is None:
        raise RuntimeError('未找到正文容器: %s' % path)

    is_geek = '_1Bj6HN3x_0' in (content.get('class') or [])
    if is_geek:
        # 原站整页：保留 se-* 结构与样式
        for tag in content.find_all(True):
            for a in STRIP_ATTRS:
                if tag.has_attr(a):
                    del tag[a]
        classes = set()
        for tag in content.find_all(True):
            classes.update(tag.get('class', []))
        classes.discard('')
        css = filter_css(soup, classes)
        body = content.decode_contents().strip()
    else:
        # 纯净版：清掉脚本样式，正文按模板自带样式排版
        convert_mathjax(content)
        for tag in content.find_all(DROP_TAGS):
            tag.decompose()
        for tag in content.find_all(True):
            tag.attrs.pop('style', None)
            tag.attrs.pop('class', None)
        css = ''
        body = content.decode_contents().strip()
        # 参考资料等附加块
        ref = soup.select_one('.reference, .references, .appendix')
        if ref is not None and ref.get_text(strip=True):
            for tag in ref.find_all(DROP_TAGS):
                tag.decompose()
            body += ('\n<hr>\n<div class="gk-extra"><p><strong>参考资料</strong></p>%s</div>'
                     % ref.decode_contents().strip())

    # ---- 标题
    title = ''
    for sel in ('h1.vJXgmLTi_0', 'h1.title', 'h1', 'title'):
        el = soup.select_one(sel)
        if el is not None:
            t = clean(el.get_text())
            if t and len(t) < 120:
                title = t
                break
    label = normalize_title(title or file_label(os.path.basename(path)))

    # ---- 日期 / 作者 / 专栏名
    date = author = course_name = ''
    meta = soup.select_one('._2LbT9q3y_0')
    if meta is not None:
        spans = meta.find_all('span', recursive=False)
        if spans:
            date = clean(spans[0].get_text())
        if len(spans) > 1:
            author = clean(spans[1].get_text())
        if len(spans) > 2:
            course_name = clean(spans[2].get_text())
        el = meta.select_one('._14EYfoWo_0')
        if el is not None:
            course_name = clean(el.get_text())
    if not re.match(r'^\d{4}[-/.]\d{1,2}', date or ''):
        date = ''
    if not date:
        # 纯净版页面没有原站的 meta 容器，退回用文件名里的日期
        date = date_from_name(stem)
    if author in ('文章', '编辑', '专栏'):
        author = ''

    # ---- 封面（懒加载留下的空 data URI 视为没有封面）
    cover = ''
    for sel in ('img._1-ZfmNK8_0', 'div.cover img', '.cover img'):
        el = soup.select_one(sel)
        if el is not None and el.get('src') and not re.match(r'^data:[^,]*,?\s*$', el['src']):
            cover = el['src']
            break

    # ---- 音频
    speaker = duration = size = ''
    bar = soup.select_one('._1qj_iPD7_0')
    if bar is not None:
        h3 = bar.find('h3')
        if h3 is not None:
            speaker = clean(h3.get_text()).replace('讲述：', '').replace('讲述:', '')
        p = bar.find('p')
        if p is not None:
            txt = p.get_text(' ', strip=True)
            m = re.search(r'时长\s*([\d:]+)', txt)
            if m:
                duration = m.group(1)
            m = re.search(r'大小\s*[:：]?\s*([\d.]+\s*[KMGk]?B?)', txt)
            if m:
                size = m.group(1).replace(' ', '')

    audio_file = None
    a = soup.find('audio')
    if a is not None:
        src = a.get('src')
        if not src:
            s2 = a.find('source')
            if s2 is not None:
                src = s2.get('src')
        if src and not re.match(r'^(https?:|data:|blob:)', src):
            cand = os.path.normpath(os.path.join(html_dir, src))
            if os.path.isfile(cand):
                audio_file = cand
    if audio_file is None:
        audio_file = find_asset(stem, html_dir, root, ('.m4a', '.mp3', '.aac', '.wav'))

    # ---- 视频（有视频稿的讲次：视频优先展示，音频仍保留在播放条）
    video_file = None
    v = soup.find('video')
    if v is not None:
        src = v.get('src')
        if not src:
            s2 = v.find('source')
            if s2 is not None:
                src = s2.get('src')
        if src and not re.match(r'^(https?:|data:|blob:)', src):
            cand = os.path.normpath(os.path.join(html_dir, src))
            if os.path.isfile(cand):
                video_file = cand
    if video_file is None:
        video_file = find_asset(stem, html_dir, root, VIDEO_EXTS)

    pdf_file = find_asset(stem, html_dir, root, ('.pdf',))

    if not duration and audio_file:
        # 纯净版页面没有原站音频条，用音频文件元数据补时长
        duration = audio_duration(audio_file)

    return {
        'ci': ci,
        'ch': ch_name,
        'path': path,
        'label': label,
        'no': title_no(label),
        'date': date,
        'author': author,
        'course_name': course_name,
        'duration': duration,
        'speaker': speaker,
        'size': size,
        'cover': cover,
        'css': css,
        'body': body,
        'audio': audio_file,
        'video': video_file,
        'pdf': pdf_file,
        'kind': 'geek' if is_geek else 'simple',
    }


def safe_parse(task):
    """单页解析失败不影响整体构建。"""
    try:
        return parse_page(task)
    except Exception as e:  # noqa: BLE001
        print('  ! 跳过 %s（%s）' % (os.path.basename(task[2]), e))
        return None


# ================================================================ 页面模板
#
# pages/NNN.html 只是数据壳：整页 DOM 由 assets/page.js 运行时渲染，
# 所以改 UI 不用重新生成课程内容（见 sync_ui.py）。

# 外壳里的资源引用（assets 模板文件 + 本课程的目录数据），构建/同步时统一补 ?v= 指纹
SHELL_URL_RE = re.compile(r'(assets/(?:app\.js|shell\.css)|data/catalog\.js)(?:\?v=[\w.-]+)?')


def file_ver(path):
    """按文件内容取指纹；文件不存在返回 '0'（宁可缓存旧版也不要 404）。"""
    try:
        with open(path, 'rb') as f:
            return hashlib.sha1(f.read()).hexdigest()[:10]
    except OSError:
        return '0'


def asset_ver(name):
    """模板静态资源的缓存版本串：按文件内容取指纹。

    serve.py 对 /assets/ 发 7 天强缓存，脚本里换 URL 才能让旧浏览器拿到新文件；
    用内容指纹而不是手写版本号，改完 page.js 重新构建即自动生效。
    """
    return file_ver(os.path.join(TPL_ASSETS, name))


PAGE_TPL = u"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<link rel="icon" href="data:,">
<link rel="stylesheet" href="../assets/article.css?v=__CSS_V__">
</head>
<body class="article-page">
<noscript>
  <div class="page-fail">本页正文由本地脚本渲染，请在浏览器里启用 JavaScript 后刷新。</div>
</noscript>
<script type="application/json" id="lesson-data">__DATA__</script>
<script src="../assets/page.js?v=__PAGE_JS_V__"></script>
</body>
</html>
"""


def esc(s):
    return (s or '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


def build_meta(a, course_title, course_author=''):
    parts = []
    if a['date']:
        parts.append('<span>%s</span>' % esc(a['date']))
    author = a['author'] or course_author
    if author:
        parts.append('<span>%s</span>' % esc(author))
    parts.append('<a class="art-course" href="javascript:void(0)">%s</a>' % esc(course_title))
    out = ''
    for i, p in enumerate(parts):
        if i:
            out += '<i class="dot"></i>'
        out += p
    return out


def build_sub(a):
    if not a.get('audio') and not a.get('video'):
        return '<span>本讲暂无音频</span>'
    items = []
    if a.get('video') and not a.get('audio'):
        items.append('<span>视频</span>')
    if a['speaker']:
        items.append('<span>讲述：%s</span>' % esc(a['speaker']))
    if a['size']:
        items.append('<span>大小：%s</span>' % esc(a['size']))
    # 时长拿不到时先占位，由 page.js 在读取音频元数据后补齐
    items.append('<span id="subDur">时长：%s</span>' % (a['duration'] or '--:--'))
    return ''.join(items)


def json_payload(data):
    """把页面数据序列化成可安全塞进 <script type="application/json"> 的字符串。"""
    return (json.dumps(data, ensure_ascii=False, separators=(',', ':'))
            .replace('<', '\\u003c')
            .replace('>', '\\u003e'))


def render_page(a, idx, total, pages_dir, prev_a, next_a, course_title, course_author=''):
    cover = ''
    if a['cover']:
        # 封面可能是外链，离线打不开时整块隐藏，避免出现破图
        cover = ('<figure class="art-cover"><img src="%s" alt="" '
                 'onerror="this.parentNode.style.display=\'none\'"></figure>') % esc(a['cover'])

    audio_url = rel_url(a['audio'], pages_dir) if a.get('audio') else ''
    video_url = rel_url(a['video'], pages_dir) if a.get('video') else ''
    pdf_url = rel_url(a['pdf'], pages_dir) if a.get('pdf') else ''

    body = a['body']
    if PDF_SRC_TOKEN in body:
        body = body.replace(PDF_SRC_TOKEN, esc(pdf_url or '#'))

    data = {
        'v': DATA_VERSION,
        'idx': idx,
        'total': total,
        'kind': 'kind-simple' if a['kind'] in ('simple', 'md') else 'kind-' + a['kind'],
        # 下面这些字段都是「可直接插入 HTML」的片段（文本已转义）
        'title': esc(a['label']),
        'course': esc(course_title),
        'meta': build_meta(a, course_title, course_author),
        'cover': cover,
        'sub': build_sub(a),
        'duration': esc(a['duration'] or '--:--'),
        'audio': esc(audio_url or ''),
        'video': esc(video_url or ''),
        'pdf': esc(pdf_url or ''),
        'body': body,
        'style': a['css'] or '',
        'prev': ({'url': '%03d.html' % (idx - 1), 'title': esc(prev_a['label'])}
                 if prev_a else None),
        'next': ({'url': '%03d.html' % (idx + 1), 'title': esc(next_a['label'])}
                 if next_a else None),
    }
    return (PAGE_TPL.replace('__TITLE__', '%s - %s' % (esc(a['label']), esc(course_title)))
                    .replace('__CSS_V__', asset_ver('article.css'))
                    .replace('__PAGE_JS_V__', asset_ver('page.js'))
                    .replace('__DATA__', json_payload(data)))


# ================================================================ 外壳

def render_shell(out_dir, catalog):
    """壳与模板一致（课程信息由 assets/app.js 从 data/catalog.js 填充）。

    只把 assets/app.js、assets/shell.css 的 URL 换成内容指纹，避免 /assets/ 的
    7 天强缓存继续命中旧壳脚本。sync_ui.py 有一份同样规则的实现。
    """
    with open(TPL_INDEX, 'r', encoding='utf-8') as f:
        tpl = f.read()
    tpl = SHELL_URL_RE.sub(
        lambda m: '%s?v=%s' % (m.group(1), file_ver(os.path.join(out_dir, m.group(1)))), tpl)
    with open(os.path.join(out_dir, 'index.html'), 'w', encoding='utf-8') as f:
        f.write(tpl)


STALE_ASSETS = ('article.js',)      # 旧版正文脚本，已并入 page.js

# 页面数据结构版本（page.js 校验，不一致时给提示而不是静默白屏）
DATA_VERSION = 1


def copy_assets(out_dir):
    dst = os.path.join(out_dir, 'assets')
    if not os.path.isdir(dst):
        os.makedirs(dst)
    for name in os.listdir(TPL_ASSETS):
        src = os.path.join(TPL_ASSETS, name)
        if os.path.isfile(src):
            shutil.copyfile(src, os.path.join(dst, name))
    for name in STALE_ASSETS:
        p = os.path.join(dst, name)
        if os.path.isfile(p):
            os.remove(p)


def prune_stale_pages(pages_dir, total):
    """删掉上一次构建残留的正文页（讲次变少 / 重编号时会留孤儿文件）。

    序号 < total 的旧页会被本次循环原样覆盖，只需处理序号超界的那批。
    """
    removed = 0
    for name in os.listdir(pages_dir):
        m = re.match(r'^(\d{3})\.html$', name)
        if m and int(m.group(1)) >= total:
            try:
                os.remove(os.path.join(pages_dir, name))
                removed += 1
            except OSError:
                pass
    return removed


# ================================================================ 主流程

def build(course_dir, out_dir, title=None, author=None, role=None,
          content_dir=None, jobs=8, quiet=False, asset_dirs=None):
    global ASSET_DIRS, PAGES_DIR, OUT_DIR
    ASSET_DIRS = tuple(asset_dirs or ())
    course_dir = os.path.abspath(course_dir)
    out_dir = os.path.abspath(out_dir)
    pages_dir = os.path.join(out_dir, 'pages')
    PAGES_DIR = pages_dir
    OUT_DIR = out_dir
    data_dir = os.path.join(out_dir, 'data')
    for d in (out_dir, pages_dir, data_dir):
        if not os.path.isdir(d):
            os.makedirs(d)

    chapters, flat = scan_course(course_dir, content_dir)

    tasks = []
    groups = []
    if chapters:
        for ci, ch in enumerate(chapters):
            groups.append({'name': ch['name'], 'count': len(ch['files'])})
            for f in ch['files']:
                tasks.append((ci, ch['name'], os.path.join(ch['dir'], f), course_dir))
    elif flat:
        grouped = group_flat(article_files(flat))
        for gi, (gname, files) in enumerate(grouped):
            groups.append({'name': gname, 'count': len(files)})
            for f in files:
                tasks.append((gi, gname, os.path.join(flat, f), course_dir))
    else:
        raise SystemExit('没有在 %s 找到任何可构建的正文或媒体文件'
                         '（html / md / pdf / mp3 / mp4 等）' % course_dir)

    # 缺 markdown 库时 md 正文会全部解析失败，只会剩媒体占位页，
    # 生成一个「1 讲」的残缺阅读器；这里直接失败，别让产物看起来是好的。
    if _markdown is None and any(t[2].lower().endswith('.md') for t in tasks):
        raise SystemExit('缺少 markdown 库，无法渲染 .md 正文；'
                         '请先执行：%s -m pip install markdown' % sys.executable)

    if not quiet:
        print('课程目录: %s' % course_dir)
        print('布局: %s · %d 组 · %d 讲'
              % ('分章节目录' if chapters else '扁平目录', len(groups), len(tasks)))
        print('解析中 ...')

    with ThreadPoolExecutor(max_workers=jobs) as ex:
        arts = list(ex.map(safe_parse, tasks))

    skipped = [a for a in arts if a is None]
    arts = [a for a in arts if a is not None]
    if not arts:
        raise SystemExit('所有页面解析失败，请检查目录结构')
    if not quiet:
        kinds = {}
        for a in arts:
            kinds[a['kind']] = kinds.get(a['kind'], 0) + 1
        print('正文类型: %s' % ', '.join('%s=%d' % kv for kv in kinds.items()))

    # ---- 课程名 / 作者推断
    if not title or not author:
        from collections import Counter
        cn = Counter(a['course_name'] for a in arts if a['course_name'])
        au = Counter(a['author'] for a in arts if a['author'])
        if not title:
            title = (cn.most_common(1)[0][0] if cn else os.path.basename(course_dir))
        if not author:
            author = au.most_common(1)[0][0] if au else ''
    role = DEFAULT_ROLE if role is None else role
    course_meta = ' · '.join([x for x in (author, role) if x])

    # ---- 排序：先按组，再按发布日期 / 文件名
    ordered = []
    for gi, g in enumerate(groups):
        items = [a for a in arts if a['ci'] == gi and a['ch'] == g['name']]
        dated = [a for a in items if a['date']]
        if len(dated) == len(items) and len(items) > 1:
            # 同一天的讲次再按标题里的序号排（开篇词等无序号视为最前）
            items.sort(key=lambda a: (a['date'],
                                      -1 if a['no'] is None else a['no'],
                                      a['path']))
        else:
            items.sort(key=lambda a: file_order(os.path.basename(a['path'])))
        g['items'] = items
        ordered.extend(items)

    total = len(ordered)
    if not quiet:
        print('生成 %d 个正文页 ...' % total)

    prune_stale_pages(pages_dir, total)

    for i, a in enumerate(ordered):
        prev_a = ordered[i - 1] if i > 0 else None
        next_a = ordered[i + 1] if i < total - 1 else None
        html = render_page(a, i, total, pages_dir, prev_a, next_a, title, author)
        with open(os.path.join(pages_dir, '%03d.html' % i), 'w', encoding='utf-8') as f:
            f.write(html)

    catalog = {'title': title, 'author': author, 'role': role,
               'total': total, 'chapters': []}
    offset = 0
    for g in groups:
        items = g['items']
        catalog['chapters'].append({
            'name': '%s（%d讲）' % (g['name'], len(items)),
            'items': [{'i': offset + k, 'label': a['label'], 'date': a['date'], 'dur': a['duration']}
                      for k, a in enumerate(items)],
        })
        offset += len(items)

    with open(os.path.join(data_dir, 'catalog.js'), 'w', encoding='utf-8') as f:
        f.write('window.COURSE = ')
        json.dump(catalog, f, ensure_ascii=False, separators=(',', ':'))
        f.write(';\n')

    copy_assets(out_dir)
    render_shell(out_dir, catalog)

    if not quiet:
        print('完成：%s' % out_dir)
        print('  课程: %s%s' % (title, (' · ' + course_meta) if course_meta else ''))
        print('  章节: %d 组 / %d 讲' % (len(groups), total))
        if skipped:
            print('  跳过: %d 讲（正文容器无法识别）' % len(skipped))
        print('  打开: %s' % os.path.join(out_dir, 'index.html'))
    return catalog


def main(argv=None):
    ap = argparse.ArgumentParser(
        description='把已下载的极客时间课程目录构建成离线阅读器',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例:\n'
               '  python build.py "E:/资料/极客时间/设计模式之美"\n'
               '  python build.py "<课程目录>" --title 课程名 --author 作者 --role 头衔\n')
    ap.add_argument('course', nargs='?', default=os.getcwd(), help='课程根目录（默认当前目录）')
    ap.add_argument('--out', default=None, help='输出目录（默认 <课程>/reader）')
    ap.add_argument('--title', default=None, help='课程名（默认自动推断 / 目录名）')
    ap.add_argument('--author', default=None, help='作者（默认自动推断）')
    ap.add_argument('--role', default=None, help='作者头衔，如 "Google 工程师"')
    ap.add_argument('--content-dir', default=None, help='扁平布局的正文子目录，默认 html')
    ap.add_argument('--asset-dirs', default=None,
                    help='附件子目录名，逗号分隔（如 mp3,音频,pdf）；默认自动扫描课程根目录下的一级子目录')
    ap.add_argument('--jobs', type=int, default=8, help='并行解析线程数')
    args = ap.parse_args(argv)

    course = os.path.abspath(args.course)
    if not os.path.isdir(course):
        raise SystemExit('课程目录不存在: %s' % course)
    out = args.out or os.path.join(course, OUT_NAME)
    asset_dirs = ([x.strip() for x in args.asset_dirs.split(',') if x.strip()]
                  if args.asset_dirs else None)
    build(course, out, title=args.title, author=args.author, role=args.role,
          content_dir=args.content_dir, jobs=args.jobs, asset_dirs=asset_dirs)


if __name__ == '__main__':
    sys.exit(main())
