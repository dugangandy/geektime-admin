#!/usr/bin/env python3
"""serve.py 访问控制的自测（纯标准库）：临时目录 + 临时端口起真实服务验证。

运行：python -m unittest test_serve_auth -v
"""
import http.client
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import serve  # noqa: E402

PASSWORD = "correct horse battery staple"


class AuthServerCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._orig_log = serve.Handler.log_message
        serve.Handler.log_message = lambda *a, **k: None

    @classmethod
    def tearDownClass(cls):
        serve.Handler.log_message = cls._orig_log

    def setUp(self):
        self._cwd = os.getcwd()
        self.root = tempfile.mkdtemp(prefix="gk-auth-test-")
        self._make_tree()
        store = serve.AuthStore(os.path.join(self.root, "auth.json"))
        store.set_password(PASSWORD, iterations=50_000)
        self.cfg = serve.parse_args([
            "0", self.root, "--bind", "127.0.0.1",
            "--lock-fail-count", "2", "--lock-window-sec", "600",
            "--lock-base-sec", "60",
        ])
        self.httpd = serve.create_server(self.cfg)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.jar = {}

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        os.chdir(self._cwd)
        shutil.rmtree(self.root, ignore_errors=True)

    def _make_tree(self):
        def put(rel, data):
            path = os.path.join(self.root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            mode = "wb" if isinstance(data, bytes) else "w"
            with open(path, mode) as f:
                f.write(data)
        put("index.html",
            '<!doctype html><html><head><meta charset="utf-8"><title>目录</title></head>'
            '<body><header class="topbar"><div class="wrap topbar-inner">'
            '<h1 class="logo">极客时间</h1>'
            '<div class="search-bar"><input id="search"></div>'
            '</div></header><main>目录</main></body></html>')
        # 模拟真实 catalog.html：压缩导出页，没有 </body>
        put("catalog.html", '<html><body><div class="cat">总目录</div></div>')
        # 模拟阅读器页面：不应被注入
        put("course/reader/pages/001.html",
            "<html><body><article>正文</article></body></html>")
        put("big.mp3", b"\x00" * 5000)
        put("serve.py", "secret source")
        put("notes.bak", "backup")
        put("data.json", "{}")
        put(".git/config", "[core]")
        put("sub/.git/config", "[core]")
        os.makedirs(os.path.join(self.root, "emptydir"))

    # ---------------------------------------------------------------- 请求助手
    def req(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        hdrs = dict(headers or {})
        if self.jar:
            hdrs["Cookie"] = "; ".join("%s=%s" % kv for kv in self.jar.items())
        if isinstance(body, str):
            body = body.encode()
        if body is not None:
            hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        header_list = resp.getheaders()
        resp_headers = {}
        for k, v in header_list:                 # 同名头后者覆盖（Set-Cookie 单独处理）
            resp_headers[k.lower()] = v
        # 同步 Cookie 罐：收集本次响应所有 Set-Cookie
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

    def get_csrf(self, page="/__auth/login"):
        status, headers, _ = self.req("GET", page, headers={"Accept": "text/html"})
        self.assertEqual(status, 200)
        return self.jar[serve.CSRF_COOKIE]

    def login(self, password=PASSWORD, next_url="/", keep="1", csrf=None):
        if csrf is None:
            csrf = self.get_csrf()
        form = urllib.parse.urlencode(
            {"csrf": csrf, "password": password, "next": next_url, "keep": keep})
        return self.req("POST", "/__auth/login", body=form,
                        headers={"Accept": "text/html"})

    # ---------------------------------------------------------------- 用例
    def test_html_requires_login(self):
        status, headers, _ = self.req("GET", "/index.html",
                                      headers={"Accept": "text/html"})
        self.assertEqual(status, 302)
        self.assertTrue(headers["location"].startswith("/__auth/login?next="))

    def test_media_requires_login(self):
        status, _, _ = self.req("GET", "/big.mp3")
        self.assertEqual(status, 401)

    def test_sensitive_files_blocked(self):
        for path in ("/serve.py", "/notes.bak", "/data.json",
                     "/.git/config", "/sub/.git/config"):
            status, _, _ = self.req("GET", path, headers={"Accept": "text/html"})
            self.assertEqual(status, 404, path)

    def test_directory_listing_disabled(self):
        self.assertEqual(self.login()[0], 303)
        status, _, _ = self.req("GET", "/emptydir/", headers={"Accept": "text/html"})
        self.assertEqual(status, 404)

    def test_login_success_then_private_cache_and_range(self):
        status, headers, _ = self.login()
        self.assertEqual(status, 303)
        self.assertEqual(headers["location"], "/")
        sess_cookie = headers.get("set-cookie", "")
        self.assertIn("HttpOnly", sess_cookie)
        self.assertIn("SameSite=Lax", sess_cookie)
        self.assertIn("Max-Age=2592000", sess_cookie)   # 30 天（keep=1）

        status, headers, body = self.req("GET", "/index.html",
                                         headers={"Accept": "text/html"})
        self.assertEqual(status, 200)
        self.assertIn("private", headers["cache-control"])

        status, headers, body = self.req("GET", "/big.mp3",
                                         headers={"Range": "bytes=0-99"})
        self.assertEqual(status, 206)
        self.assertEqual(headers["content-range"], "bytes 0-99/5000")
        self.assertEqual(len(body), 100)

    def test_no_keep_short_session(self):
        status, headers, _ = self.login(keep="")
        self.assertEqual(status, 303)
        self.assertIn("Max-Age=86400", headers.get("set-cookie", ""))

    def test_logout_invalidates_session(self):
        self.assertEqual(self.login()[0], 303)
        csrf = self.get_csrf("/__auth/logout")
        form = urllib.parse.urlencode({"csrf": csrf})
        status, headers, _ = self.req("POST", "/__auth/logout", body=form)
        self.assertEqual(status, 303)
        self.assertIn("logged_out=1", headers["location"])
        status, _, _ = self.req("GET", "/index.html", headers={"Accept": "text/html"})
        self.assertEqual(status, 302)

    def test_lockout_after_failures(self):
        status, _, body = self.login(password="wrong-1")
        self.assertEqual(status, 401)
        self.assertIn("口令不正确".encode(), body)
        status, _, body = self.login(password="wrong-2")   # 第 2 次失败触发锁定
        self.assertEqual(status, 429)
        self.assertIn("已暂时锁定".encode(), body)
        status, _, _ = self.login(password=PASSWORD)       # 锁定期内正确口令也拒绝
        self.assertEqual(status, 429)

    def test_open_redirect_rejected(self):
        status, headers, _ = self.login(next_url="//evil.com")
        self.assertEqual(status, 303)
        self.assertEqual(headers["location"], "/")

    def test_csrf_rejected(self):
        form = urllib.parse.urlencode(
            {"csrf": "forged", "password": PASSWORD, "next": "/"})
        status, _, _ = self.req("POST", "/__auth/login", body=form,
                                headers={"Accept": "text/html"})
        self.assertEqual(status, 403)

    def test_revoke_sessions_kicks_user(self):
        self.assertEqual(self.login()[0], 303)
        self.httpd.store.revoke_sessions()
        status, _, _ = self.req("GET", "/index.html", headers={"Accept": "text/html"})
        self.assertEqual(status, 302)

    def test_logout_widget_injected_on_index(self):
        self.assertEqual(self.login()[0], 303)
        for path in ("/", "/index.html"):
            status, _, body = self.req("GET", path, headers={"Accept": "text/html"})
            self.assertEqual(status, 200, path)
            self.assertEqual(body.count(b'id="gk-logout-widget"'), 1, path)
            self.assertIn(b'href="/__auth/logout"', body)

    def test_catalog_fragment_never_injected(self):
        # catalog.html 是官网课程元数据的 HTML 片段（不对外访问），不得被注入
        self.assertEqual(self.login()[0], 303)
        status, _, body = self.req("GET", "/catalog.html",
                                   headers={"Accept": "text/html"})
        self.assertEqual(status, 200)
        self.assertNotIn(b"gk-logout-widget", body)
        self.assertTrue(body.startswith(b'<html><body><div class="cat">'))

    def test_logout_widget_sits_in_topbar_right(self):
        self.assertEqual(self.login()[0], 303)
        _, _, body = self.req("GET", "/index.html", headers={"Accept": "text/html"})
        widget = body.index(b'id="gk-logout-widget"')
        # 在搜索框之后、</header> 之前，且中间只剩 topbar-inner 自己的 </div>
        self.assertLess(body.index(b'class="search-bar"'), widget)
        tail = body[widget:body.index(b"</header>")]
        self.assertEqual(tail.count(b"</div>"), 1)
        # 文案为「登出」（数字实体），不再是「退出」
        self.assertIn(b"&#30331;&#20986;", body)
        self.assertNotIn(b"&#36864;&#20986;", body)

    def test_inject_falls_back_to_fixed_widget(self):
        # 页面没有 header 时退化为视口右上角的 fixed 版本
        out = serve.inject_logout_widget(b"<html><body><p>x</p></body></html>")
        self.assertIn(b"position:fixed", out)
        self.assertIn(b"&#30331;&#20986;", out)
        self.assertEqual(out.count(b'id="gk-logout-widget"'), 1)

    def test_inject_function_appends_to_eof_without_body_tag(self):
        # 片段没有 </body> 时函数追加到 EOF（仅作为工具函数行为保障）
        raw = b'<div class="fragment">x</div>'
        out = serve.inject_logout_widget(raw)
        self.assertTrue(out.startswith(raw))
        self.assertTrue(out.endswith(b"</a>"))
        self.assertEqual(out.count(b'id="gk-logout-widget"'), 1)

    def test_logout_widget_not_injected_on_reader(self):
        self.assertEqual(self.login()[0], 303)
        status, _, body = self.req("GET", "/course/reader/pages/001.html",
                                   headers={"Accept": "text/html"})
        self.assertEqual(status, 200)
        self.assertNotIn(b"gk-logout-widget", body)

    def test_accept_ranges_dropped_and_range_ignored_on_injected_page(self):
        self.assertEqual(self.login()[0], 303)
        status, headers, body = self.req(
            "GET", "/index.html",
            headers={"Accept": "text/html", "Range": "bytes=0-9"})
        self.assertEqual(status, 200)               # 注入页不支持 Range，返回完整页
        self.assertNotIn("accept-ranges", headers)
        self.assertIn(b'id="gk-logout-widget"', body)

    def test_head_matches_get_length_on_injected_page(self):
        self.assertEqual(self.login()[0], 303)
        _, get_headers, get_body = self.req("GET", "/index.html",
                                            headers={"Accept": "text/html"})
        _, head_headers, head_body = self.req("HEAD", "/index.html",
                                              headers={"Accept": "text/html"})
        self.assertEqual(head_body, b"")
        self.assertEqual(int(head_headers["content-length"]), len(get_body))
        self.assertEqual(int(get_headers["content-length"]), len(get_body))

    def test_injection_is_idempotent(self):
        raw = b"<html><body>x</body></html>"
        once = serve.inject_logout_widget(raw)
        twice = serve.inject_logout_widget(once)
        self.assertIs(once, twice)
        self.assertEqual(once.count(b'id="gk-logout-widget"'), 1)

    def test_build_routes_require_login(self):
        for path in ("/__build/course", "/__build/status/course"):
            status, headers, _ = self.req("GET", path, headers={"Accept": "text/html"})
            self.assertEqual(status, 302, path)
            self.assertTrue(headers["location"].startswith("/__auth/login?next="))
        status, _, _ = self.req("GET", "/__build/course")
        self.assertEqual(status, 401)

    def test_build_routes_disabled_without_templates(self):
        # 临时目录没有 _导航模版，动态首页与按需构建自动退化
        self.assertIsNone(self.httpd.site)
        self.assertIsNone(self.httpd.build_queue)
        self.assertEqual(self.login()[0], 303)
        status, _, _ = self.req("GET", "/__build/course", headers={"Accept": "text/html"})
        self.assertEqual(status, 404)
        status, _, _ = self.req("GET", "/__build/status/course",
                                headers={"Accept": "text/html"})
        self.assertEqual(status, 404)

    def test_login_page_security_headers(self):
        status, headers, body = self.req("GET", "/__auth/login",
                                         headers={"Accept": "text/html"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("x-frame-options"), "DENY")
        self.assertIn("no-store", headers.get("cache-control", ""))
        self.assertIn("访问口令".encode(), body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
