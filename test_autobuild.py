#!/usr/bin/env python3
"""首页数据合并 / 按需构建队列 / 静态副本的自测（纯标准库）。

运行：python -m unittest test_autobuild -v
"""
import json
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

        self.manifest = self.root / "_导航模版" / "course_manifest.json"
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
            self.root, build_py=HERE / "_阅读器模板" / "build.py")

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
