#!/usr/bin/env python3
"""首页数据合并 / 按需构建队列 / 静态副本的自测（纯标准库）。

运行：python -m unittest test_autobuild -v
"""
import json
import os
import re
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import site_generator  # noqa: E402

_PAGE = '<html><body><div class="content">x</div></body></html>'


def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(text, bytes):
        path.write_bytes(text)
    else:
        path.write_text(text, encoding="utf-8")


class CollectCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="gk-auto-"))
        self.catalog = site_generator.load_catalog(HERE)

        # 本地：一门有 reader / 一门有内容没 reader / 一门空目录
        write(self.root / "有阅读器" / "html" / "01.html", _PAGE)
        write(self.root / "有阅读器" / "reader" / "index.html", "<html></html>")
        write(self.root / "有阅读器" / "reader" / "data" / "catalog.js",
              'window.COURSE = {"total": 12};')
        write(self.root / "待生成" / "html" / "01.html", _PAGE)
        (self.root / "空目录").mkdir()
        write(self.root / "assets" / "covers" / "待生成.webp", b"RIFF____WEBP")

        self.manifest = self.root / "template" / "nav" / "course_manifest.json"
        write(self.manifest, json.dumps({"version": 1, "courses": [
            {"name": "有阅读器", "dir": "backend", "state": "done",
             "href": "有阅读器/reader/index.html", "cover": "有阅读器.webp",
             "author": "官网作者", "identity": "头衔", "lessons": 12,
             "learners_text": "1万人已学习"},
            {"name": "待生成", "dir": "ai", "author": "清单作者", "lessons": 8},
            {"name": "只在清单里", "dir": "sec", "cover": "只在清单里.webp", "lessons": 30},
        ]}, ensure_ascii=False))

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def collect(self, **kw):
        return self.catalog.collect_courses(self.root, self.manifest, "html", **kw)

    def test_union_and_states(self):
        cs = {c["name"]: c for c in self.collect()}
        self.assertEqual(len(cs), 4)                       # 3 本地 + 1 清单
        self.assertEqual(cs["有阅读器"]["state"], "reader")
        self.assertEqual(cs["待生成"]["state"], "pending")
        self.assertEqual(cs["空目录"]["state"], "none")
        self.assertEqual(cs["只在清单里"]["state"], "none")
        self.assertTrue(cs["有阅读器"]["local"])
        self.assertFalse(cs["只在清单里"]["local"])

    def test_metadata_priority(self):
        write(self.root / "待生成" / "course.json", json.dumps(
            {"title": "手工标题", "author": "手工作者",
             "direction": "大数据", "lessons": 99}, ensure_ascii=False))
        cs = {c["name"]: c for c in self.collect()}
        self.assertEqual(cs["待生成"]["display"], "手工标题")
        self.assertEqual(cs["待生成"]["author"], "手工作者")
        self.assertEqual(cs["待生成"]["dir"], "bigdata")
        self.assertEqual(cs["待生成"]["lessons"], 99)
        self.assertEqual(cs["有阅读器"]["author"], "官网作者")   # 清单补空
        self.assertEqual(cs["有阅读器"]["chapters"], 12)        # 讲数取 reader 的 total

    def test_build_states_override(self):
        cs = {c["name"]: c for c in self.collect(
            build_states={"待生成": "building", "有阅读器": "failed"})}
        self.assertEqual(cs["待生成"]["state"], "building")
        self.assertEqual(cs["有阅读器"]["state"], "reader")     # 已有 reader 不被覆盖

    def test_cover_only_when_file_exists(self):
        cs = {c["name"]: c for c in self.collect()}
        self.assertEqual(cs["待生成"]["cover_file"], "待生成.webp")
        self.assertEqual(cs["只在清单里"]["cover_file"], "")     # 清单有名字但文件不在
        self.assertEqual(cs["有阅读器"]["cover_file"], "")

    def test_render_offline_has_no_build_link(self):
        online = self.catalog.render_index(self.collect(), "标题", "#f66121", offline=False)
        offline = self.catalog.render_index(self.collect(), "标题", "#f66121", offline=True)
        self.assertIn("/__build/", online)
        self.assertNotIn("/__build/", offline)
        self.assertIn('data-status="pending"', online)
        self.assertIn("启动服务后可生成", offline)

    def test_count_media_skips_product_dirs(self):
        c = self.root / "媒体课"
        write(c / "01.mp3", b"ID3")
        write(c / "02.pdf", b"%PDF")
        write(c / "03.mp4", b"mp4")
        write(c / "reader" / "ignored.mp3", b"ID3")
        write(c / "mp4-opt" / "ignored.mp4", b"mp4")
        self.assertEqual(self.catalog.count_media(c), (1, 1, 1))

    def test_count_media_cache_invalidates_on_change(self):
        c = self.root / "缓存媒体课"
        write(c / "01.mp3", b"ID3")
        self.assertEqual(self.catalog.count_media(c), (1, 0, 0))
        self.assertEqual(self.catalog.count_media(c), (1, 0, 0))   # 指纹命中
        write(c / "02.mp3", b"ID3")                                 # 顶层新增
        self.assertEqual(self.catalog.count_media(c), (2, 0, 0))
        write(c / "chapter" / "03.mp4", b"mp4")                     # 新子目录
        self.assertEqual(self.catalog.count_media(c), (2, 0, 1))

    def test_is_video_course_rule(self):
        f = self.catalog.is_video_course
        self.assertTrue(f({"videos": 35, "lessons": 35, "audios": 0}))
        self.assertTrue(f({"videos": 27, "lessons": 29, "audios": 25}))  # 27>14.5, 25<27
        self.assertTrue(f({"videos": 20, "chapters": 30, "audios": 0}))  # 讲数取 chapters
        self.assertFalse(f({"videos": 4, "lessons": 49, "audios": 47}))   # 混合课
        self.assertFalse(f({"videos": 10, "lessons": 30, "audios": 12}))  # 10<=15
        self.assertFalse(f({"videos": 30, "lessons": 30, "audios": 30}))  # 音频不比视频少
        self.assertFalse(f({"videos": 0, "lessons": 30, "audios": 20}))

    def test_video_course_tag_and_filter_chip(self):
        write(self.root / "视频课" / "01.mp4", b"mp4")
        html = self.catalog.render_index(self.collect(), "标题", "#f66121")
        self.assertIn('data-status="video"', html)       # 筛选 chip
        self.assertIn('data-video="1"', html)            # 卡片标记
        self.assertIn('class="tag-video">视频课', html)   # pill
        self.assertIn('视频课 <span>1</span>', html)      # chip 计数

    def test_non_video_card_has_no_tag(self):
        card = self.catalog.card_html({
            "name": "混合课", "state": "reader", "href": "x",
            "videos": 4, "lessons": 49, "audios": 47,
        })
        self.assertNotIn("tag-video", card)
        self.assertNotIn("data-video", card)

    def test_static_copy_atomic_and_guarded(self):
        html = self.catalog.render_index(self.collect(), "标题")
        dst = self.catalog.write_static_index(self.root, html, "index.html")
        self.assertTrue(Path(dst).is_file())
        self.assertIn(self.catalog.MARKER, Path(dst).read_text(encoding="utf-8"))
        self.assertFalse((self.root / "index.html.tmp").exists())
        write(self.root / "other.html", "<html>hand written</html>")
        with self.assertRaises(RuntimeError):
            self.catalog.write_static_index(self.root, html, "other.html")


class BuildQueueCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="gk-queue-"))
        (self.root / "课程A" / "html").mkdir(parents=True)
        self.queue = site_generator.BuildQueue(
            self.root, build_py=HERE / "template" / "reader" / "build.py")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_whitelist_and_traversal(self):
        self.assertEqual(self.queue.resolve("课程A"), "课程A")
        for bad in ("", ".", "..", "../课程A", "课程A/../x", "C:\\Windows", "/etc/passwd", "不存在"):
            self.assertIsNone(self.queue.resolve(bad), bad)

    def test_status_defaults(self):
        self.assertEqual(self.queue.status("课程A")["state"], "pending")
        self.assertEqual(self.queue.status("不存在")["state"], "invalid")

    def test_reader_exists_is_ready(self):
        write(self.root / "课程A" / "reader" / "index.html", "<html></html>")
        self.assertEqual(self.queue.status("课程A")["state"], "ready")
        self.assertEqual(self.queue.request("课程A"), ("课程A", "ready"))

    def test_request_is_idempotent_and_finishes(self):
        self.assertEqual(self.queue.request("课程A"), ("课程A", "building"))
        self.assertEqual(self.queue.request("课程A")[1], "building")   # 不重复起线程
        for _ in range(150):
            if self.queue.status("课程A")["state"] in ("ready", "failed"):
                break
            time.sleep(0.2)
        # 空课程目录必然构建失败，但状态机必须收敛而不是卡在 building
        self.assertIn(self.queue.status("课程A")["state"], ("ready", "failed"))
        self.assertNotEqual(self.queue.status("课程A")["state"], "building")


class SiteGeneratorCase(unittest.TestCase):
    """首页缓存失效：数据目录里的「内容变化」必须让 courses() 重新扫描。

    旧签名只看一级目录名 + 清单/catalog.html/course.json 的 mtime，往已有课程
    目录补正文、命令行外部构建 reader、补封面都读不到，卡片会一直停在旧状态。
    """
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="gk-site-"))
        nav = self.root / "template" / "nav"
        nav.mkdir(parents=True)
        for name in ("build_catalog.py", "catalog_template.html"):
            shutil.copy2(HERE / "template" / "nav" / name, nav / name)
        self.manifest = nav / "course_manifest.json"
        write(self.manifest, json.dumps({"version": 1, "courses": []}))
        self.site = site_generator.SiteGenerator(self.root, self.manifest)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def course(self, name):
        return {c["name"]: c for c in self.site.courses()}[name]

    def test_unchanged_tree_reuses_cache(self):
        write(self.root / "稳定课" / "html" / "01.html", _PAGE)
        self.site.courses()
        cached = self.site._courses
        self.site.courses()
        self.assertIs(self.site._courses, cached, "未变化时应命中缓存")

    def test_new_course_dir_is_picked_up(self):
        self.assertNotIn("新课", {c["name"] for c in self.site.courses()})
        write(self.root / "新课" / "html" / "01.html", _PAGE)
        self.assertEqual(self.course("新课")["state"], "pending")

    def test_content_added_to_existing_subdir_invalidates(self):
        # html/ 事先存在：只往里补文件，课程目录自身 mtime 不变
        (self.root / "空课" / "html").mkdir(parents=True)
        self.assertEqual(self.course("空课")["state"], "none")
        write(self.root / "空课" / "html" / "01.html", _PAGE)
        self.assertEqual(self.course("空课")["state"], "pending")

    def test_media_added_to_existing_subdir_invalidates(self):
        write(self.root / "附课" / "html" / "01.html", _PAGE)
        (self.root / "附课" / "音频").mkdir()
        self.assertEqual(self.course("附课")["audios"], 0)
        write(self.root / "附课" / "音频" / "01.mp3", b"ID3")
        self.assertEqual(self.course("附课")["audios"], 1)

    def test_reader_built_externally_invalidates(self):
        # 不经过 BuildQueue（无 on_done 回调）也要生效
        write(self.root / "构建课" / "html" / "01.html", _PAGE)
        (self.root / "构建课" / "reader").mkdir()
        self.assertEqual(self.course("构建课")["state"], "pending")
        write(self.root / "构建课" / "reader" / "index.html", "<html></html>")
        self.assertEqual(self.course("构建课")["state"], "reader")

    def test_reader_catalog_js_change_invalidates(self):
        write(self.root / "讲数课" / "reader" / "index.html", "<html></html>")
        js = self.root / "讲数课" / "reader" / "data" / "catalog.js"
        write(js, 'window.COURSE = {"total": 1};')
        self.assertEqual(self.course("讲数课")["chapters"], 1)
        write(js, 'window.COURSE = {"total": 9, "author": "张三"};')
        self.assertEqual(self.course("讲数课")["chapters"], 9)

    def test_cover_added_invalidates(self):
        # 封面在 root/assets/covers，课程目录外置：补封面不能靠「root 多了一级
        # 目录」被旧签名误打误撞发现，必须靠封面目录自身的指纹。
        data_dir = self.root / "课程数据"
        (data_dir / "封面课").mkdir(parents=True)
        write(self.manifest, json.dumps({"version": 1, "courses": [
            {"name": "封面课", "cover": "封面课.webp"}]}, ensure_ascii=False))
        site = site_generator.SiteGenerator(self.root, self.manifest,
                                           data_dir=data_dir)

        def course():
            return {c["name"]: c for c in site.courses()}["封面课"]

        self.assertEqual(course()["cover_file"], "")
        write(self.root / "assets" / "covers" / "封面课.webp", b"RIFF____WEBP")
        self.assertEqual(course()["cover_file"], "封面课.webp")

    def test_course_json_change_still_invalidates(self):
        write(self.root / "元数据课" / "html" / "01.html", _PAGE)
        self.assertEqual(self.course("元数据课")["author"], "")
        write(self.root / "元数据课" / "course.json",
              json.dumps({"author": "手工作者"}, ensure_ascii=False))
        self.assertEqual(self.course("元数据课")["author"], "手工作者")

    def test_build_states_do_not_bypass_cache(self):
        write(self.root / "缓存课" / "html" / "01.html", _PAGE)
        self.site.courses()
        cached = self.site._courses
        states = {"缓存课": "building"}
        out = {c["name"]: c["state"] for c in self.site.courses(states)}
        self.assertIs(self.site._courses, cached, "叠加状态不应触发全量重扫")
        self.assertEqual(out["缓存课"], "building")
        # 叠加是视图，不能污染缓存里的基础数据
        self.assertEqual([c["state"] for c in cached if c["name"] == "缓存课"],
                         ["pending"])
        self.assertEqual(self.course("缓存课")["state"], "pending")

    def test_reader_state_not_overridden_by_build_state(self):
        write(self.root / "已生成" / "reader" / "index.html", "<html></html>")
        out = {c["name"]: c["state"]
               for c in self.site.courses({"已生成": "failed"})}
        self.assertEqual(out["已生成"], "reader")

    def test_manifest_only_course_can_show_build_state(self):
        write(self.manifest, json.dumps({"version": 1, "courses": [
            {"name": "清单占位", "dir": "sec"}]}, ensure_ascii=False))
        out = {c["name"]: c["state"]
               for c in self.site.courses({"清单占位": "building"})}
        self.assertEqual(out["清单占位"], "building")


class ReaderVideoPlaceholderCase(unittest.TestCase):
    """离线阅读器内联 <video>：首屏就预留高度，loadedmetadata 后不再顶开正文。"""

    def setUp(self):
        assets = HERE / "template" / "reader" / "assets"
        self.page_js = (assets / "page.js").read_text(encoding="utf-8")
        self.css = (assets / "article.css").read_text(encoding="utf-8")

    def test_video_markup_reserves_ratio_before_metadata(self):
        m = re.search(r'<video id="video"[^>]*>', self.page_js)
        self.assertIsNotNone(m, "视频讲次未生成 <video> 元素")
        self.assertIn('width="16"', m.group(0))
        self.assertIn('height="9"', m.group(0))
        self.assertIn('preload="metadata"', m.group(0))

    def test_css_locks_placeholder_height(self):
        self.assertRegex(self.css,
                         r"\.video-wrap\s*\{[^}]*aspect-ratio:\s*16\s*/\s*9")
        self.assertRegex(self.css,
                         r"\.video-wrap video\s*\{[^}]*object-fit:\s*contain")
        # 绝对定位把 <video> 钉在固定盒子里，height 不再随 intrinsic ratio 变化
        self.assertRegex(self.css,
                         r"\.video-wrap video\s*\{[^}]*position:\s*absolute")


class SyncUiDataDirCase(unittest.TestCase):
    """sync_ui 的 --data-dir：课程目录外置时也能找到阅读器。"""

    def test_find_readers_honors_data_dir(self):
        import importlib.util
        path = HERE / "template" / "reader" / "sync_ui.py"
        spec = importlib.util.spec_from_file_location("gk_sync_ui", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        root = Path(tempfile.mkdtemp(prefix="gk-syncdir-"))
        try:
            d = root / "课程A" / "reader" / "data"
            d.mkdir(parents=True)
            (d / "catalog.js").write_text("window.COURSE={};", encoding="utf-8")
            self.assertEqual([n for n, _ in mod.find_readers([], str(root))], ["课程A"])
            self.assertEqual(mod.find_readers(["不存在"], str(root)), [])
        finally:
            shutil.rmtree(root, ignore_errors=True)


class FaststartTsCase(unittest.TestCase):
    """faststart_mp4.py 的 TS 转封装：落点规则与扫描范围。"""

    def setUp(self):
        import importlib.util
        path = HERE / "faststart_mp4.py"
        spec = importlib.util.spec_from_file_location("gk_faststart", path)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)

    def test_ts_output_is_mp4_next_to_source(self):
        root = os.path.join("/data", "course")
        src = os.path.join(root, "a.ts")
        self.assertEqual(self.mod.dst_for(src, root, "/out", False),
                         os.path.join(root, "a.mp4"))
        # --out 与 --in-place 都不改变 TS 的落点（阅读器只认课程根目录/附件目录）
        self.assertEqual(self.mod.dst_for(src, root, "/out", True),
                         os.path.join(root, "a.mp4"))
        self.assertEqual(self.mod.dst_for(os.path.join(root, "a.TS"), root, "/out", True),
                         os.path.join(root, "a.mp4"))

    def test_mp4_keeps_original_rules(self):
        root = os.path.join("/data", "course")
        sub = os.path.join(root, "sub", "a.mp4")
        self.assertEqual(self.mod.dst_for(sub, root, "/out", False),
                         os.path.join("/out", "sub", "a.mp4"))
        self.assertEqual(self.mod.dst_for(sub, root, "/out", True), sub)

    def test_find_videos_includes_ts_and_skips_outputs(self):
        root = Path(tempfile.mkdtemp(prefix="gk-ts-"))
        try:
            (root / "a.ts").write_bytes(b"ts")
            (root / "b.mp4").write_bytes(b"mp4")
            (root / "notes.txt").write_bytes(b"x")
            for skip in ("reader", "mp4-opt"):
                (root / skip).mkdir()
                (root / skip / "c.mp4").write_bytes(b"mp4")
            names = [os.path.basename(p) for p in self.mod.find_videos(str(root))]
            self.assertEqual(names, ["a.ts", "b.mp4"])
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_remove_ts_requires_in_place_and_yes(self):
        root = Path(tempfile.mkdtemp(prefix="gk-ts-"))
        try:
            (root / "a.ts").write_bytes(b"ts")
            with self.assertRaises(SystemExit):
                self.mod.main([str(root), "--remove-ts", "--dry-run"])
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
