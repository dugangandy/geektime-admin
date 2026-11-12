#!/usr/bin/env python3
"""mobile_api.py 的自测（纯标准库）：合成课程树 + 临时端口起真实服务。

运行：python -m unittest test_mobile_api -v
"""
import http.client
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import serve  # noqa: E402

PASSWORD = "correct horse battery staple"
JSON_HEADERS = {"X-GK-Client": "android", "X-GK-Device": "test-device-0001"}


def _lesson_page(data):
    payload = json.dumps(data, ensure_ascii=True, separators=(",", ":"))
    return (
        '<!doctype html><html><head><meta charset="utf-8"></head><body>'
        '<script type="application/json" id="lesson-data">%s</script>'
        '</body></html>' % payload
    )


def _catalog(title, author, chapters):
    doc = {
        "title": title, "author": author,
        "total": sum(len(c["items"]) for c in chapters),
        "chapters": chapters,
    }
    return "window.COURSE = %s;" % json.dumps(doc, ensure_ascii=False)


class MobileApiCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._orig_log = serve.Handler.log_message
        serve.Handler.log_message = lambda *a, **k: None

    @classmethod
    def tearDownClass(cls):
        serve.Handler.log_message = cls._orig_log

    def setUp(self):
        self._cwd = os.getcwd()
        self.root = tempfile.mkdtemp(prefix="gk-api-test-")
        self._make_tree()
        store = serve.AuthStore(os.path.join(self.root, "auth.json"))
        store.set_password(PASSWORD, iterations=50_000)
        self._start()

    def _start(self, lock_count=6):
        self.cfg = serve.parse_args([
            "0", self.root, "--bind", "127.0.0.1",
            "--lock-fail-count", str(lock_count), "--lock-window-sec", "600",
            "--lock-base-sec", "60",
        ])
        self.httpd = serve.create_server(self.cfg)
        self.assertTrue(self.httpd.api is not None, "api 未启用")
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.jar = {}

    def _stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def tearDown(self):
        self._stop()
        os.chdir(self._cwd)
        shutil.rmtree(self.root, ignore_errors=True)

    # ---------------------------------------------------------------- 课程树
    def _make_tree(self):
        def put(rel, data):
            path = os.path.join(self.root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            mode = "wb" if isinstance(data, bytes) else "w"
            with open(path, mode, encoding=None if isinstance(data, bytes) else "utf-8") as f:
                f.write(data)

        manifest = {"version": 1, "courses": [
            {"name": "Course A", "dir": "backend", "badge": "王牌爆款",
             "author": "Alice", "identity": "前 XX 工程师", "lessons": 3,
             "learners_text": "1.2万人已学习", "cover": "Course A.webp"},
            # 清单里有，但磁盘上没有 catalog.js → 必须被过滤
            {"name": "Course B", "dir": "ai", "author": "Bob",
             "identity": "", "lessons": 2, "learners_text": "3万人已学习"},
        ]}
        put("template/nav/course_manifest.json",
            json.dumps(manifest, ensure_ascii=False))
        put("assets/covers/Course A.webp", b"RIFFxxxxWEBPVP8 ")
        put("Course A/reader/data/catalog.js", _catalog("Course A", "Alice", [
            {"name": "基础篇（2讲）", "items": [
                {"i": 0, "label": "00 | 进程与线程", "date": "2024-01-01", "dur": "10:00"},
                {"i": 1, "label": "01 | 内存模型", "date": "2024-01-02", "dur": "--:--"},
            ]},
            {"name": "进阶篇（1讲）", "items": [
                {"i": 2, "label": "02 | 并发陷阱", "date": "2024-01-03", "dur": "12:30"},
            ]},
        ]))
        put("Course A/00-audio.mp3", b"\x00" * 5000)
        put("Course A/00-doc.pdf", b"%PDF-1.4" + b"\x00" * 3000)
        put("Course A/00-video.mp4", b"\x00" * 4096)
        common = {"total": 3, "kind": "kind-simple", "course": "Course A",
                  "meta": "", "sub": ""}
        put("Course A/reader/pages/000.html", _lesson_page(dict(common, **{
            "idx": 0, "title": "00 | 进程与线程", "cover": "",
            "duration": "10:00", "audio": "../../00-audio.mp3",
            "video": "../../00-video.mp4",
            "pdf": "../../00-doc.pdf", "body": "<p>hello 并发</p>",
        })))
        # 巨型 base64 封面：audio/pdf 键被推到 8KB 之后
        big_cover = "data:image/jpeg;base64," + "A" * 30000
        put("Course A/reader/pages/001.html", _lesson_page(dict(common, **{
            "idx": 1, "title": "01 | 内存模型", "cover": big_cover,
            "duration": "--:--", "audio": "", "pdf": "",
            "body": "<p>no media</p>",
        })))
        put("Course A/reader/pages/002.html", _lesson_page(dict(common, **{
            "idx": 2, "title": "02 | 并发陷阱", "cover": "",
            "duration": "12:30", "audio": "../../../etc/passwd",
            "pdf": "../../00-doc.pdf", "body": "<p>%s</p>" % ("并发 " * 2000),
        })))
        # 存在于磁盘但不在清单：必须被排除
        put("左耳听风_old/reader/data/catalog.js",
            _catalog("左耳听风_old", "X", [{"name": "x", "items": [
                {"i": 0, "label": "x", "date": "", "dur": ""}]}]))

    # ---------------------------------------------------------------- 请求助手
    def req(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        hdrs = dict(headers or {})
        if self.jar:
            hdrs["Cookie"] = "; ".join("%s=%s" % kv for kv in self.jar.items())
        if isinstance(body, str):
            body = body.encode()
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        header_list = resp.getheaders()
        resp_headers = {}
        for k, v in header_list:
            resp_headers[k.lower()] = v
        for k, v in header_list:
            if k.lower() != "set-cookie":
                continue
            pair, attrs = v.split(";", 1)[0], v
            if "=" in pair:
                name, value = pair.split("=", 1)
                if "max-age=0" in attrs.lower():
                    self.jar.pop(name.strip(), None)
                else:
                    self.jar[name.strip()] = value
        conn.close()
        return resp.status, resp_headers, data

    def api(self, method, path, obj=None, headers=None, device="test-device-0001"):
        hdrs = {"X-GK-Device": device}
        body = None
        if obj is not None:
            body = json.dumps(obj).encode()
            hdrs["Content-Type"] = "application/json"
        hdrs.update(headers or {})
        status, resp_headers, data = self.req(method, path, body=body, headers=hdrs)
        try:
            payload = json.loads(data.decode("utf-8") or "{}")
        except ValueError:
            payload = None
        return status, payload, resp_headers

    def login(self, password=PASSWORD, keep=True, device="test-device-0001",
              extra=None):
        headers = dict(JSON_HEADERS)
        headers["X-GK-Device"] = device
        headers.update(extra or {})
        return self.api("POST", "/api/auth/login",
                        {"password": password, "keep": keep}, headers=headers,
                        device=device)

    def course_id(self):
        status, payload, _ = self.api("GET", "/api/courses")
        self.assertEqual(status, 200)
        return payload["courses"][0]["id"]

    # ---------------------------------------------------------------- 鉴权
    def test_ping_without_auth(self):
        status, payload, _ = self.api("GET", "/api/ping")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertFalse(payload["authenticated"])
        self.assertEqual(payload["courses"], 1)

    def test_courses_requires_auth_json_401(self):
        status, payload, _ = self.api("GET", "/api/courses")
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"]["code"], "unauthenticated")

    def test_login_wrong_password_then_ok(self):
        status, payload, _ = self.login("wrong")
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"]["code"], "bad_password")
        status, payload, headers = self.login()
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["courses"], 1)
        self.assertGreater(payload["expires_at"], time.time())
        self.assertIn("gk_sess", self.jar)
        self.assertIn("HttpOnly", headers.get("set-cookie", ""))

    def test_login_requires_client_header_and_json(self):
        status, payload, _ = self.api("POST", "/api/auth/login",
                                      {"password": PASSWORD})
        self.assertEqual(status, 403)
        self.assertEqual(payload["error"]["code"], "forbidden")
        status, _, _ = self.req(
            "POST", "/api/auth/login",
            body="password=" + PASSWORD,
            headers={"X-GK-Client": "android",
                     "Content-Type": "application/x-www-form-urlencoded"})
        self.assertEqual(status, 415)

    def test_login_rejects_cross_origin(self):
        status, payload, _ = self.login(
            extra={"Origin": "http://evil.example"})
        self.assertEqual(status, 403)
        self.assertEqual(payload["error"]["code"], "forbidden")

    def test_session_and_logout(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/auth/session")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        status, payload, _ = self.api("POST", "/api/auth/logout")
        self.assertEqual(status, 200)
        self.assertNotIn("gk_sess", self.jar)
        status, payload, _ = self.api("GET", "/api/courses")
        self.assertEqual(status, 401)

    def test_lockout_returns_429_with_retry_after(self):
        self._stop()
        self._start(lock_count=2)
        try:
            status, payload, _ = self.login("bad", device="lock-device-01")
            self.assertEqual(status, 401)
            status, payload, _ = self.login("bad", device="lock-device-01")
            self.assertEqual(status, 429)
            self.assertEqual(payload["error"]["code"], "locked")
            self.assertGreater(payload["error"]["retry_after"], 0)
            # 正确口令也要等锁定结束
            status, payload, _ = self.login(PASSWORD, device="lock-device-01")
            self.assertEqual(status, 429)
        finally:
            self.httpd.api.limiter.success("api-global")

    # ---------------------------------------------------------------- 课程
    def test_courses_only_readable(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/courses")
        self.assertEqual(status, 200)
        self.assertEqual(len(payload["courses"]), 1)
        row = payload["courses"][0]
        self.assertEqual(row["name"], "Course A")
        self.assertRegex(row["id"], r"^[0-9a-f]{12}$")
        self.assertEqual(row["total"], 3)
        self.assertEqual(row["read"], 0)
        self.assertFalse(row["started"])
        self.assertFalse(row["video_course"])     # 1 视频 / 3 讲 / 1 音频，不算视频课
        self.assertIsNone(row["last"])
        self.assertEqual(row["cover_url"], "/assets/covers/Course%20A.webp")
        self.assertEqual(payload["categories"],
                         [{"key": "backend", "label": "后端/架构", "count": 1}])

    def test_courses_category_filter(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/courses?category=ai")
        self.assertEqual(status, 200)
        self.assertEqual(payload["courses"], [])

    def test_course_detail_media_flags(self):
        self.login()
        cid = self.course_id()
        status, payload, _ = self.api("GET", "/api/courses/" + cid)
        self.assertEqual(status, 200)
        self.assertEqual(len(payload["chapters"]), 2)
        items = payload["chapters"][0]["items"] + payload["chapters"][1]["items"]
        self.assertEqual([i["idx"] for i in items], [0, 1, 2])
        self.assertTrue(items[0]["has_audio"] and items[0]["has_pdf"])
        # 大封面页必须仍能探测到“没有音频/原稿”
        self.assertFalse(items[1]["has_audio"])
        self.assertFalse(items[1]["has_pdf"])
        self.assertFalse(items[2]["has_audio"])          # 越界音频被拒
        self.assertTrue(items[2]["has_pdf"])
        # 详情页直接带可播地址，客户端据此一次构建播放队列
        self.assertEqual(items[0]["audio_url"], "/Course%20A/00-audio.mp3")
        self.assertEqual(items[0]["pdf_url"], "/Course%20A/00-doc.pdf")
        self.assertTrue(items[0]["has_video"])
        self.assertEqual(items[0]["video_url"], "/Course%20A/00-video.mp4")
        self.assertFalse(items[1]["has_video"])
        self.assertEqual(items[2]["audio_url"], "")      # ../ 越界必须被拒绝
        self.assertEqual(payload["total"], 3)
        self.assertFalse(payload["video_course"])        # 1 视频 / 3 讲 / 1 音频，不算视频课

    def test_course_detail_unknown_id(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/courses/deadbeefcafe")
        self.assertEqual(status, 404)
        self.assertEqual(payload["error"]["code"], "not_found")
        status, _, _ = self.api("GET", "/api/courses/../../etc/passwd")
        self.assertEqual(status, 404)

    def test_lesson_with_media(self):
        self.login()
        cid = self.course_id()
        status, payload, _ = self.api("GET", "/api/courses/%s/lessons/0" % cid)
        self.assertEqual(status, 200)
        self.assertEqual(payload["idx"], 0)
        self.assertEqual(payload["audio_url"], "/Course%20A/00-audio.mp3")
        self.assertEqual(payload["pdf_url"], "/Course%20A/00-doc.pdf")
        self.assertEqual(payload["video_url"], "/Course%20A/00-video.mp4")
        self.assertIn("hello", payload["body_html"])
        self.assertIsNone(payload["prev"])
        self.assertEqual(payload["next"]["idx"], 1)

    def test_lesson_without_media(self):
        self.login()
        cid = self.course_id()
        status, payload, _ = self.api("GET", "/api/courses/%s/lessons/1" % cid)
        self.assertEqual(status, 200)
        self.assertEqual(payload["audio_url"], "")
        self.assertEqual(payload["pdf_url"], "")
        self.assertEqual(payload["prev"]["idx"], 0)
        self.assertEqual(payload["next"]["idx"], 2)

    def test_lesson_rejects_path_traversal_media(self):
        self.login()
        cid = self.course_id()
        status, payload, _ = self.api("GET", "/api/courses/%s/lessons/2" % cid)
        self.assertEqual(status, 200)
        self.assertEqual(payload["audio_url"], "")
        self.assertEqual(payload["pdf_url"], "/Course%20A/00-doc.pdf")

    def test_lesson_not_found(self):
        self.login()
        cid = self.course_id()
        for idx in (9, 9999):
            status, payload, _ = self.api("GET", "/api/courses/%s/lessons/%d" % (cid, idx))
            self.assertEqual(status, 404, idx)

    def test_lesson_gzip(self):
        self.login()
        cid = self.course_id()
        status, headers, data = self.req(
            "GET", "/api/courses/%s/lessons/2" % cid,
            headers={"Accept-Encoding": "gzip"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("content-encoding"), "gzip")
        import gzip as gz
        self.assertIn("并发", gz.decompress(data).decode("utf-8"))

    # ---------------------------------------------------------------- 进度
    def test_progress_merge_and_stats(self):
        self.login()
        cid = self.course_id()
        now = time.time()
        status, payload, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 40, "pos": 100, "dur": 600})
        self.assertEqual(status, 200)
        self.assertFalse(payload["read"])
        self.assertLessEqual(abs(payload["at"] - now), 10)

        # pct 取大值，pos/dur 以最新为准，read 粘性
        status, payload, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 20, "pos": 50, "dur": 600})
        self.assertEqual(status, 200)
        self.assertEqual(payload["pct"], 40)

        # 跨过 88% 自动标记已学完，日统计只记一次
        status, payload, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 90, "pos": 500, "dur": 600,
            "listen_sec": 400})
        self.assertEqual(status, 200)
        self.assertTrue(payload["read"])
        status, payload, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 95, "read": True})
        self.assertTrue(payload["read"])

        status, payload, _ = self.api("GET", "/api/progress/" + cid)
        self.assertEqual(status, 200)
        rec = payload["lessons"]["0"]
        self.assertEqual(rec["pct"], 95)
        self.assertTrue(rec["read"])
        self.assertEqual(payload["last"]["lesson"], 0)

        status, payload, _ = self.api("GET", "/api/stats")
        self.assertEqual(status, 200)
        self.assertEqual(payload["lessons_read"], 1)
        self.assertEqual(payload["lessons_total"], 3)
        self.assertEqual(payload["courses_started"], 1)
        self.assertEqual(payload["days_read"], 1)
        self.assertEqual(payload["streak"], 1)
        self.assertEqual(payload["minutes"], 5)          # listen_sec 被 clamp 到 300
        self.assertEqual(payload["continue"][0]["lesson"], 0)
        self.assertTrue(payload["recent"])

        # 落盘结构
        with open(os.path.join(self.root, ".mobile", "progress.json"),
                  "r", encoding="utf-8") as f:
            saved = json.load(f)
        self.assertEqual(saved["v"], 1)
        self.assertEqual(saved["daily"][next(iter(saved["daily"]))]["seconds"], 300)

    def test_audio_and_reading_positions_are_independent(self):
        """pos/dur 是阅读像素，audio_pos/audio_dur 是毫秒，两者不得互相覆盖。"""
        self.login()
        cid = self.course_id()
        status, _, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 30, "pos": 420, "dur": 3000})
        self.assertEqual(status, 200)

        # 音频上报不带 pos/dur，阅读位置必须原样保留
        status, _, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 10,
            "audio_pos": 1800000, "audio_dur": 2400000, "listen_sec": 15})
        self.assertEqual(status, 200)
        status, payload, _ = self.api("GET", "/api/progress/" + cid)
        rec = payload["lessons"]["0"]
        self.assertEqual(rec["pos"], 420)
        self.assertEqual(rec["dur"], 3000)
        self.assertEqual(rec["audio_pos"], 1800000)
        self.assertEqual(rec["audio_dur"], 2400000)

        # 反向：阅读上报不带 audio_*，音频位置同样保留
        status, _, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 55, "pos": 900, "dur": 3000})
        self.assertEqual(status, 200)
        status, payload, _ = self.api("GET", "/api/progress/" + cid)
        rec = payload["lessons"]["0"]
        self.assertEqual(rec["pos"], 900)
        self.assertEqual(rec["audio_pos"], 1800000)
        self.assertEqual(rec["audio_dur"], 2400000)

        # 超过 24h 的毫秒值被 clamp，而不是沿用像素上限
        status, _, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "audio_pos": 10 ** 12})
        self.assertEqual(status, 200)
        status, payload, _ = self.api("GET", "/api/progress/" + cid)
        self.assertEqual(payload["lessons"]["0"]["audio_pos"], 86400 * 1000)

    def test_progress_rejects_bad_input(self):
        self.login()
        cid = self.course_id()
        status, payload, _ = self.api("POST", "/api/progress",
                                      {"course": "bad", "lesson": 0})
        self.assertEqual(status, 400)
        status, payload, _ = self.api("POST", "/api/progress",
                                      {"course": cid, "lesson": 9})
        self.assertEqual(status, 400)
        status, payload, _ = self.api("POST", "/api/progress",
                                      {"course": cid, "lesson": "x"})
        self.assertEqual(status, 400)

    def test_progress_course_unknown(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/progress/deadbeefcafe")
        self.assertEqual(status, 404)

    # ---------------------------------------------------------------- 搜索
    def test_search(self):
        self.login()
        status, payload, _ = self.api("GET", "/api/search?q=course")
        self.assertEqual(status, 200)
        self.assertEqual(len(payload["courses"]), 1)
        status, payload, _ = self.api("GET", "/api/search?q=%E5%86%85%E5%AD%98")
        self.assertEqual(status, 200)
        self.assertEqual(payload["lessons"][0]["idx"], 1)
        status, payload, _ = self.api("GET", "/api/search?q=")
        self.assertEqual(payload, {"courses": [], "lessons": []})
        status, payload, _ = self.api("GET", "/api/search?q=" + "a" * 65)
        self.assertEqual(status, 400)

    def test_search_courses_carry_progress(self):
        self.login()
        cid = self.course_id()
        status, _, _ = self.api("POST", "/api/progress", {
            "course": cid, "lesson": 0, "pct": 100, "read": True})
        self.assertEqual(status, 200)
        status, payload, _ = self.api("GET", "/api/search?q=course")
        self.assertEqual(status, 200)
        hit = payload["courses"][0]
        # 搜索结果必须和 /api/courses 同字段，否则客户端把学过的课显示成「未开始」
        status, listing, _ = self.api("GET", "/api/courses")
        row = next(c for c in listing["courses"] if c["id"] == cid)
        self.assertEqual(hit, row)
        self.assertTrue(hit["started"])
        self.assertEqual(hit["read"], 1)

    # ---------------------------------------------------------------- 静态 Range
    def test_range_streaming(self):
        self.login()
        status, headers, data = self.req(
            "GET", "/Course%20A/00-audio.mp3", headers={"Range": "bytes=0-99"})
        self.assertEqual(status, 206)
        self.assertEqual(len(data), 100)
        self.assertEqual(headers["content-range"], "bytes 0-99/5000")
        self.assertEqual(headers["accept-ranges"], "bytes")

        status, headers, data = self.req(
            "GET", "/Course%20A/00-audio.mp3", headers={"Range": "bytes=4990-"})
        self.assertEqual(status, 206)
        self.assertEqual(len(data), 10)

        status, headers, _ = self.req(
            "GET", "/Course%20A/00-audio.mp3", headers={"Range": "bytes=9999-10000"})
        self.assertEqual(status, 416)
        self.assertEqual(headers["content-range"], "bytes */5000")

        status, headers, data = self.req("HEAD", "/Course%20A/00-audio.mp3")
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-length"], "5000")
        self.assertEqual(data, b"")

    def test_api_head_dispatches(self):
        status, headers, data = self.req("HEAD", "/api/ping")
        self.assertEqual(status, 200)
        self.assertEqual(data, b"")
        self.assertGreater(int(headers["content-length"]), 0)


class RealCourseDataCase(unittest.TestCase):
    """真实课程库只读校验：不写进度，只验证索引/探测/解析。"""

    @classmethod
    def setUpClass(cls):
        cls.root = os.path.dirname(os.path.abspath(__file__))
        manifest = os.path.join(cls.root, "template", "nav", "course_manifest.json")
        cls.available = os.path.isfile(manifest) and os.path.isfile(
            os.path.join(cls.root, "左耳听风", "reader", "data", "catalog.js"))

    def setUp(self):
        if not self.available:
            self.skipTest("真实课程库不存在")
        cfg = serve.parse_args(["0", self.root])
        self._orig_log = serve.Handler.log_message
        serve.Handler.log_message = lambda *a, **k: None
        self.httpd = serve.create_server(cfg)
        self.api = self.httpd.api
        self.assertTrue(self.api is not None)

    def tearDown(self):
        if getattr(self, "httpd", None) is not None:
            self.httpd.server_close()
            serve.Handler.log_message = self._orig_log

    def test_discovers_ten_courses(self):
        names = [c["name"] for c in self.api.index.courses()]
        self.assertEqual(len(names), 10, names)
        self.assertNotIn("左耳听风_old", names)
        self.assertIn("左耳听风", names)

    def test_real_lesson_probe_and_body(self):
        course = self.api.index.get(
            [c["id"] for c in self.api.index.courses()
             if c["name"] == "左耳听风"][0])
        self.assertEqual(len(self.api.index.catalog(course)["items"]), 108)
        media0 = self.api._media(course, 0)
        self.assertTrue(media0["audio_url"].endswith(".mp3"))
        self.assertTrue(media0["pdf_url"].endswith(".pdf"))
        # 12 讲封面是内联 base64，audio 为空但 pdf 存在
        media12 = self.api._media(course, 12)
        self.assertEqual(media12["audio_url"], "")
        self.assertTrue(media12["pdf_url"].endswith(".pdf"))
        path = self.api.index.page_path(course, 12)
        data = self.api._load_lesson(path)
        self.assertEqual(data["idx"], 12)
        self.assertGreater(len(data["body"]), 100_000)
        self.assertEqual(self.api._resolve_media(course, data["audio"]), "")
        pdf = self.api._resolve_media(course, data["pdf"])
        self.assertTrue(pdf.startswith("/%E5%B7%A6%E8%80%B3%E5%90%AC%E9%A3%8E/"))


class IndexUnionCase(unittest.TestCase):
    """清单 ∪ 本地目录：本地新增的课程（未进 115 门清单）也要出现在 App 里。"""

    def test_local_only_course_is_listed(self):
        import mobile_api

        root = tempfile.mkdtemp(prefix="gk-union-")
        try:
            os.makedirs(os.path.join(root, "template", "nav"))
            with open(os.path.join(root, "template", "nav", "course_manifest.json"),
                      "w", encoding="utf-8") as f:
                json.dump({"version": 1, "courses": [
                    {"name": "清单课程", "dir": "backend", "author": "A", "lessons": 1},
                ]}, f, ensure_ascii=False)
            for name, author in (("清单课程", "A"), ("算法面试通关40讲", "王老师")):
                d = os.path.join(root, name, "reader", "data")
                os.makedirs(d)
                with open(os.path.join(d, "catalog.js"), "w", encoding="utf-8") as f:
                    f.write('window.COURSE = ' + json.dumps(
                        {"title": name, "author": author, "total": 3, "chapters": []},
                        ensure_ascii=False) + ";")
            os.makedirs(os.path.join(root, "空课程"))          # 没有 reader，不算

            index = mobile_api.CourseIndex(root, data_dir=root)
            names = sorted(c["name"] for c in index.courses())
            self.assertEqual(names, sorted(["清单课程", "算法面试通关40讲"]))

            extra = [c for c in index.courses() if c["name"] == "算法面试通关40讲"][0]
            self.assertEqual(extra["author"], "王老师")        # 从 catalog.js 取作者
            self.assertEqual(extra["category"], "cs")          # 算法/面试 → 计算机基础
            self.assertEqual(extra["category_label"], "计算机基础")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_video_course_flag_matches_homepage_rule(self):
        import mobile_api

        root = tempfile.mkdtemp(prefix="gk-video-")
        try:
            os.makedirs(os.path.join(root, "template", "nav"))
            with open(os.path.join(root, "template", "nav", "course_manifest.json"),
                      "w", encoding="utf-8") as f:
                json.dump({"version": 1, "courses": [
                    {"name": "纯视频课", "dir": "backend", "lessons": 4},
                    {"name": "混合课", "dir": "backend", "lessons": 40},
                ]}, f, ensure_ascii=False)
            for name, total, videos, audios in (
                    ("纯视频课", 4, 4, 0), ("混合课", 40, 2, 40)):
                d = os.path.join(root, name, "reader", "data")
                os.makedirs(d)
                with open(os.path.join(d, "catalog.js"), "w", encoding="utf-8") as f:
                    f.write("window.COURSE = " + json.dumps(
                        {"title": name, "author": "", "total": total,
                         "chapters": []}, ensure_ascii=False) + ";")
                for i in range(videos):
                    with open(os.path.join(root, name, "v%02d.mp4" % i), "wb") as f:
                        f.write(b"mp4")
                for i in range(audios):
                    with open(os.path.join(root, name, "a%02d.mp3" % i), "wb") as f:
                        f.write(b"mp3")

            index = mobile_api.CourseIndex(root, data_dir=root)
            flags = {c["name"]: c["video_course"] for c in index.courses()}
            self.assertTrue(flags["纯视频课"])     # 4 视频 / 4 讲 / 0 音频
            self.assertFalse(flags["混合课"])      # 2 视频 / 40 讲 / 40 音频

            # 搜索关键字与首页一致：输入「视频课」能命中
            self.assertIn("视频课", mobile_api.MobileAPI._search_text(
                {"name": "x", "author": "y", "video_course": True}))
            self.assertNotIn("视频课", mobile_api.MobileAPI._search_text(
                {"name": "x", "author": "y", "video_course": False}))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_new_reader_dir_shows_up_without_restart(self):
        import mobile_api

        root = tempfile.mkdtemp(prefix="gk-union2-")
        try:
            os.makedirs(os.path.join(root, "template", "nav"))
            with open(os.path.join(root, "template", "nav", "course_manifest.json"),
                      "w", encoding="utf-8") as f:
                json.dump({"version": 1, "courses": []}, f)
            index = mobile_api.CourseIndex(root, data_dir=root)
            self.assertEqual(index.courses(), [])

            # 构建完 reader 后，无需重启服务，下一次请求就能看到
            d = os.path.join(root, "新课程", "reader", "data")
            os.makedirs(d)
            with open(os.path.join(d, "catalog.js"), "w", encoding="utf-8") as f:
                f.write('window.COURSE = {"title":"新课程","author":"","total":1,"chapters":[]};')
            self.assertEqual([c["name"] for c in index.courses()], ["新课程"])
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
