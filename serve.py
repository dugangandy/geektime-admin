#!/usr/bin/env python3
"""极客时间离线资料的静态文件服务 + 口令门禁（单文件、纯标准库、无外部依赖）。

在静态服务能力之上叠加一层访问控制：
- 登录页：输入共享口令后签发 HMAC 签名会话 Cookie，未登录一律不可访问资料
- 会话：空闲滑动续期 + 绝对上限；改口令后 epoch+1，所有旧会话立即失效
- 防爆破：连续失败锁定（按来源 IP + 全局兜底，指数退避）、固定失败延迟、并发限制
- 自保护：*.py/*.bat/*.bak/auth.json 等敏感文件与点文件一律 404；目录不再列举
- 审计：auth.log 记录登录/登出/锁定等安全事件（绝不记录口令）

静态服务能力（与旧版一致）：HTTP/1.1 keep-alive、Range、gzip、缓存头。

用法：
    python serve.py --set-password        # 首次使用：交互式设置访问口令（生成 auth.json）
    python serve.py --gen-password        # 打印一个强随机口令
    python serve.py --revoke-sessions     # 作废所有已登录会话（不改口令）
    python serve.py                       # 127.0.0.1:8888（缺 auth.json 会提示并退出）
    python serve.py 8888 . --bind 127.0.0.1 --trust-proxy
    python serve.py 8888 . --data-dir "D:/课程数据"    # 课程文件夹放在别处

课程数据目录（--data-dir / GK_DATA_DIR）只放各门课程的一级子目录；
catalog.html、封面、清单、模板、auth.json、index.html 等仍以 root 为准。
未指定时 data_dir == root，行为与旧版完全一致。

部署注意：经 ssh -R 反向隧道暴露时，client_address 恒为 127.0.0.1，
按 IP 的限流会退化为全局限流，因此全局兜底常开；切勿按回环地址做免登录豁免。
"""
import argparse
import base64
import binascii
import functools
import gzip
import hashlib
import hmac
import html
import ipaddress
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.parse
from datetime import datetime, timezone
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

import site_generator

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)\s*$")
B64_RE = re.compile(r"^[A-Za-z0-9_-]+$")

# 值得压缩的类型（图片/音频/PDF 已压缩过，再 gzip 无收益还费 CPU）
COMPRESSIBLE = {
    ".html", ".htm", ".css", ".js", ".mjs", ".json", ".svg",
    ".txt", ".map", ".webmanifest",
}

# 一律拒绝下载的扩展名（含 auth.json；课程资料里没有页面依赖 .json）
DENY_EXTS = {
    ".py", ".pyc", ".pyo", ".bat", ".cmd", ".ps1", ".sh",
    ".ini", ".log", ".bak", ".orig", ".json", ".sqlite", ".db",
}

PBKDF2_ITERATIONS = 600_000
AUTH_VERSION = 1

SESSION_COOKIE = "gk_sess"
CSRF_COOKIE = "gk_csrf"
SESSION_TOKEN_PREFIX = "v1"
SLIDE_REISSUE_AFTER = 6 * 3600          # 距上次签发超过 6h 才重签，避免每个静态响应都写 Set-Cookie
MAX_POST_BODY = 4096
LOGIN_SEM_LIMIT = 4                     # 口令校验（PBKDF2）并发上限，防 CPU 打满
FAIL_DELAY = 0.3                        # 失败响应固定延迟，拉低在线爆破速度
GZIP_MAX_BYTES = 1024 * 1024            # 超过 1MB 的文本不做 gzip，避免整文件读入内存


# ====================================================================== 存储
class AuthStore:
    """auth.json：口令哈希 + 会话签名密钥 + epoch。原子写、mtime 缓存。"""

    def __init__(self, path):
        self.path = os.path.abspath(path)
        self._lock = threading.Lock()
        self._data = None
        self._mtime = 0.0

    @property
    def exists(self):
        return os.path.isfile(self.path)

    def _fresh(self):
        return {
            "version": AUTH_VERSION,
            "algo": "pbkdf2_sha256",
            "iterations": PBKDF2_ITERATIONS,
            "salt": base64.b64encode(secrets.token_bytes(16)).decode(),
            "hash": "",
            "session_key": base64.b64encode(secrets.token_bytes(32)).decode(),
            "epoch": 1,
            "updated": "",
        }

    def _read(self):
        try:
            mtime = os.stat(self.path).st_mtime
        except OSError:
            mtime = 0.0
        if self._data is not None and mtime == self._mtime:
            return self._data
        with open(self.path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict) or data.get("version") != AUTH_VERSION:
            raise SystemExit("auth.json 版本不兼容，请删除后重新 --set-password")
        self._data, self._mtime = data, mtime
        return data

    def _write(self, data):
        data["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass
        with self._lock:
            self._data, self._mtime = data, os.stat(self.path).st_mtime

    @staticmethod
    def _derive(password, salt_b64, iterations):
        return base64.b64encode(
            hashlib.pbkdf2_hmac(
                "sha256", password.encode("utf-8"),
                base64.b64decode(salt_b64), iterations,
            )
        ).decode()

    def set_password(self, password, iterations=None):
        iterations = iterations or PBKDF2_ITERATIONS
        data = self._read() if self.exists else self._fresh()
        data["algo"] = "pbkdf2_sha256"
        data["iterations"] = iterations
        data["salt"] = base64.b64encode(secrets.token_bytes(16)).decode()
        data["hash"] = self._derive(password, data["salt"], iterations)
        data["epoch"] = int(data.get("epoch", 0)) + 1   # 改口令 = 所有旧会话下线
        self._write(data)

    def revoke_sessions(self):
        data = self._read()
        data["epoch"] = int(data.get("epoch", 0)) + 1
        self._write(data)

    def verify(self, password):
        data = self._read()
        if not data.get("hash"):
            return False
        try:
            candidate = self._derive(password, data["salt"], int(data["iterations"]))
        except (ValueError, TypeError, binascii.Error):
            return False
        return hmac.compare_digest(candidate, data["hash"])

    @property
    def session_key(self):
        return base64.b64decode(self._read()["session_key"])

    @property
    def epoch(self):
        return int(self._read().get("epoch", 0))


# ====================================================================== 会话
class SessionSigner:
    """无状态会话：token = v1.<b64url(json)>.<b64url(hmac)>。key 在 auth.json，重启不失效。"""

    def __init__(self, store):
        self.store = store

    @staticmethod
    def _b64e(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    @staticmethod
    def _b64d(text):
        return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))

    def sign(self, sid, now, abs_expire):
        payload = json.dumps(
            {"sid": sid, "iat": int(now), "abs": int(abs_expire), "e": self.store.epoch},
            separators=(",", ":"),
        ).encode("utf-8")
        sig = hmac.new(self.store.session_key, payload, hashlib.sha256).digest()
        return "%s.%s.%s" % (SESSION_TOKEN_PREFIX, self._b64e(payload), self._b64e(sig))

    def verify(self, token, now, idle_seconds, max_seconds):
        """返回 (payload, reissue_token)；无效返回 (None, None)。"""
        try:
            ver, payload_s, sig_s = token.split(".")
            if ver != SESSION_TOKEN_PREFIX or not B64_RE.match(payload_s) or not B64_RE.match(sig_s):
                return None, None
            payload = json.loads(self._b64d(payload_s))
            expected = hmac.new(self.store.session_key, self._b64d(payload_s), hashlib.sha256).digest()
            if not hmac.compare_digest(expected, self._b64d(sig_s)):
                return None, None
            sid, iat, abs_expire, epoch = (
                payload.get("sid"), payload.get("iat"), payload.get("abs"), payload.get("e"))
            if not (isinstance(sid, str) and isinstance(iat, int)
                    and isinstance(abs_expire, int) and isinstance(epoch, int)):
                return None, None
            if epoch != self.store.epoch:
                return None, None
            if now - iat > idle_seconds or now > abs_expire:
                return None, None
            reissue = None
            if now - iat > SLIDE_REISSUE_AFTER:      # 滑动续期：刷新 iat，保留绝对上限
                reissue = self.sign(sid, now, abs_expire)
            return payload, reissue
        except (ValueError, TypeError, binascii.Error, json.JSONDecodeError):
            return None, None


# ====================================================================== 限流
class RateLimiter:
    """失败锁定：key -> 15 分钟窗口内失败时间戳；超限锁 10 分钟，重复触发指数退避至 60 分钟。"""

    def __init__(self, fail_count=5, window=900, lock_base=600, lock_max=3600):
        self.fail_count = fail_count
        self.window = window
        self.lock_base = lock_base
        self.lock_max = lock_max
        self._lock = threading.Lock()
        self._fails = {}     # key -> [ts, ...]
        self._locks = {}     # key -> (unlock_ts, consecutive_locks)

    def locked_seconds(self, key, now=None):
        now = time.time() if now is None else now
        with self._lock:
            until = self._locks.get(key, (0, 0))[0]
            return max(0, int(until - now))

    def fail(self, key, now=None):
        now = time.time() if now is None else now
        with self._lock:
            self._sweep(now)
            ts = self._fails.setdefault(key, [])
            ts.append(now)
            if len(ts) < self.fail_count:
                return 0
            self._fails.pop(key, None)
            _, locks = self._locks.get(key, (0, 0))
            locks = min(locks + 1, 6)
            secs = min(self.lock_base * (2 ** (locks - 1)), self.lock_max)
            self._locks[key] = (now + secs, locks)
            return int(secs)

    def success(self, key):
        with self._lock:
            self._fails.pop(key, None)
            self._locks.pop(key, None)

    def _sweep(self, now):
        cutoff = now - self.window
        for k in [k for k, v in self._fails.items() if not v or v[-1] < cutoff]:
            self._fails.pop(k, None)
        for k in [k for k, (until, _) in self._locks.items() if until <= now]:
            self._locks.pop(k, None)


# ====================================================================== 审计
class AuditLog:
    """auth.log：安全事件滚动日志（1MB x 2），绝不写口令。"""

    def __init__(self, path):
        self.path = os.path.abspath(path)
        self._lock = threading.Lock()

    def write(self, event, ip, detail=""):
        line = "%s [%s] ip=%s %s\n" % (
            datetime.now(timezone.utc).isoformat(timespec="seconds"),
            event, ip or "-", detail[:300],
        )
        with self._lock:
            try:
                if os.path.getsize(self.path) > 1024 * 1024:
                    os.replace(self.path, self.path + ".1")
            except OSError:
                pass
            try:
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(line)
            except OSError:
                pass


# ====================================================================== 页面
AUTH_CSS = """
:root{--paper:#FBF9F7;--card:#FFFFFF;--line:#E8E9EC;--ink:#17181C;--sub:#5B5F6A;
--mute:#6F7681;--brand:#F66121;--brand-d:#C2410C;--tint:#FFF1E9;--err:#DC2626;--warn:#B45309;}
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%}
body{font-family:"PingFang SC","Hiragino Sans GB","Microsoft YaHei",system-ui,sans-serif;
background:var(--paper);color:var(--ink);min-height:100dvh;display:flex;align-items:center;
justify-content:center;padding:24px;position:relative;overflow-x:hidden;-webkit-font-smoothing:antialiased}
body::before{content:"";position:fixed;inset:0;pointer-events:none;
background:radial-gradient(560px 380px at 88% -8%,rgba(246,97,33,.14),transparent 62%),
radial-gradient(480px 420px at -12% 108%,rgba(246,97,33,.07),transparent 60%)}
.wrap{width:100%;max-width:400px;position:relative;z-index:1;
animation:rise .24s ease-out both}
@keyframes rise{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}
.brand{text-align:center;margin-bottom:28px}
.brand .mark{width:52px;height:52px;margin:0 auto 14px;border-radius:16px;
background:linear-gradient(135deg,var(--brand),var(--brand-d));display:flex;align-items:center;
justify-content:center;box-shadow:0 10px 24px rgba(246,97,33,.28)}
.brand h1{font-size:26px;font-weight:600;letter-spacing:.5px}
.brand p{margin-top:8px;font-size:14px;color:var(--sub)}
.card{background:rgba(255,255,255,.78);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);
border:1px solid var(--line);border-radius:20px;padding:28px 26px 24px;
box-shadow:0 1px 2px rgba(23,24,28,.04),0 18px 44px rgba(23,24,28,.07)}
label.fld{display:block;font-size:13px;font-weight:500;color:var(--sub);margin-bottom:8px}
.pwbox{position:relative}
input[type=password],input[type=text]{width:100%;height:48px;padding:0 46px 0 14px;font-size:16px;
color:var(--ink);background:var(--card);border:1px solid var(--line);border-radius:12px;outline:none;
transition:border-color .15s,box-shadow .15s}
input:focus{border-color:var(--brand);box-shadow:0 0 0 4px rgba(246,97,33,.16)}
.eye{position:absolute;right:6px;top:50%;transform:translateY(-50%);width:36px;height:36px;border:0;
background:none;cursor:pointer;color:var(--mute);border-radius:10px;display:flex;align-items:center;
justify-content:center}
.eye:hover{background:var(--tint);color:var(--brand)}
.row{display:flex;align-items:center;justify-content:space-between;margin:16px 0 18px}
.keep{display:flex;align-items:center;gap:8px;font-size:14px;color:var(--sub);cursor:pointer;user-select:none}
.keep input{width:16px;height:16px;accent-color:var(--brand);cursor:pointer}
button.go{width:100%;height:48px;border:0;border-radius:12px;background:var(--brand);color:#fff;
font-size:16px;font-weight:600;letter-spacing:2px;cursor:pointer;
transition:background .15s,transform .1s,box-shadow .15s;box-shadow:0 8px 20px rgba(246,97,33,.26)}
button.go:hover{background:var(--brand-d)}
button.go:active{transform:scale(.985)}
button.go:disabled{background:#B9BDC7;box-shadow:none;cursor:not-allowed;letter-spacing:0}
.alert{display:none;margin-top:16px;padding:11px 13px;border-radius:10px;font-size:14px;line-height:1.5}
.alert.show{display:block}
.alert.error{background:#FEF2F2;border:1px solid #FECACA;border-left:3px solid var(--err);color:#991B1B}
.alert.warn{background:#FFFBEB;border:1px solid #FDE68A;border-left:3px solid var(--warn);color:#92400E}
.alert.ok{background:#F0FDF4;border:1px solid #BBF7D0;border-left:3px solid #15803D;color:#166534}
.alert.shake{animation:shake .3s}
@keyframes shake{0%,100%{transform:none}25%{transform:translateX(-5px)}75%{transform:translateX(5px)}}
.foot{margin-top:22px;text-align:center;font-size:12px;color:var(--mute);line-height:1.7}
.foot a{color:var(--sub);text-decoration:none;border-bottom:1px solid var(--line)}
.foot a:hover{color:var(--brand)}
.out-actions{display:flex;gap:12px;margin-top:22px}
.ghost{flex:1;height:44px;border-radius:12px;border:1px solid var(--line);background:var(--card);
color:var(--sub);font-size:15px;cursor:pointer}
.ghost:hover{border-color:var(--brand);color:var(--brand)}
@media (max-width:480px){.card{padding:24px 20px 20px}.brand h1{font-size:22px}}
@media (prefers-reduced-motion:reduce){.wrap{animation:none}.alert.shake{animation:none}
button.go{transition:none}}
"""

PAGE_SHELL = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex">
<title>$TITLE</title>
<style>$CSS</style>
</head>
<body>
<div class="wrap">
$MAIN
</div>
</body>
</html>
"""

BOOK_SVG = (
    '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#fff" '
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>'
    '<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>'
)

EYE_OPEN = (
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/>'
    '<circle cx="12" cy="12" r="3"/></svg>'
)
EYE_CLOSED = (
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M17.94 17.94A10.6 10.6 0 0 1 12 19c-6.5 0-10-7-10-7a17.6 17.6 0 0 1 4.06-4.94"/>'
    '<path d="M9.9 4.24A9.5 9.5 0 0 1 12 5c6.5 0 10 7 10 7a17.7 17.7 0 0 1-2.16 3.19"/>'
    '<path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/>'
    '<line x1="2" y1="2" x2="22" y2="22"/></svg>'
)


def render_page(title, main):
    from string import Template
    return Template(PAGE_SHELL).substitute(
        TITLE=html.escape(title), CSS=AUTH_CSS, MAIN=main)


def render_login(next_url, csrf, error="", lock_secs=0, pw_value="",
                 keep_checked=True, logged_out=False, idle_days=7):
    from string import Template
    err = ('<div class="alert error show shake" role="alert">%s</div>'
           % html.escape(error)) if error else ""
    notice = ('<div class="alert ok show" role="alert">已退出登录，欢迎再来。</div>'
              if logged_out else "")
    if lock_secs > 0:
        notice += ('<div class="alert warn show" role="alert">尝试次数过多，已暂时锁定，'
                   '请 <b id="lock-cd">%d</b> 秒后再试。</div>' % lock_secs)
    disabled = "disabled" if lock_secs > 0 else ""
    lock_js = ""
    if lock_secs > 0:
        lock_js = (
            "<script>(function(){var s=%d,e=document.getElementById('lock-cd'),b=document.getElementById('go');"
            "var t=setInterval(function(){s--;if(e)e.textContent=s;"
            "if(s<=0){clearInterval(t);if(b)b.disabled=false;}},1000);})();</script>"
        ) % lock_secs
    eye_js = (
        "<script>(function(){var i=document.getElementById('pw'),b=document.getElementById('eye');"
        "if(!b)return;b.addEventListener('click',function(){"
        "var show=i.type==='password';i.type=show?'text':'password';"
        "b.innerHTML=show?%s:%s;i.focus();});})();</script>"
        % (json.dumps(EYE_CLOSED), json.dumps(EYE_OPEN))
    )
    main = Template("""
<div class="brand">
  <div class="mark">$MARK</div>
  <h1>极客时间 · 离线资料库</h1>
  <p>仅限授权访问 · 请输入访问口令</p>
</div>
<div class="card">
  <form method="post" action="/__auth/login" autocomplete="off">
    <input type="hidden" name="csrf" value="$CSRF">
    <input type="hidden" name="next" value="$NEXT">
    <label class="fld" for="pw">访问口令</label>
    <div class="pwbox">
      <input id="pw" name="password" type="password" value="$PW"
             autocomplete="current-password" required $DIS>
      <button type="button" class="eye" id="eye" aria-label="显示或隐藏口令">$EYE</button>
    </div>
    <div class="row">
      <label class="keep"><input type="checkbox" name="keep" value="1" $KEEP>在此设备上保持登录</label>
    </div>
    <button class="go" id="go" type="submit" $DIS>进 入</button>
  </form>
  $ERR$NOTICE
</div>
<div class="foot">未登录无法访问任何课程内容 · 会话空闲 $DAYS 天后自动失效<br>
<a href="/__auth/logout">退出登录</a></div>
$LOCKJS$EYEJS
""").substitute(
        MARK=BOOK_SVG, CSRF=html.escape(csrf), NEXT=html.escape(next_url, quote=True),
        PW=html.escape(pw_value, quote=True), KEEP="checked" if keep_checked else "",
        DIS=disabled, ERR=err, NOTICE=notice, DAYS=int(idle_days),
        EYE=EYE_OPEN, LOCKJS=lock_js, EYEJS=eye_js,
    )
    return render_page("登录 · 极客时间离线资料库", main)


def render_logout(csrf):
    from string import Template
    main = Template("""
<div class="brand">
  <div class="mark">$MARK</div>
  <h1>退出登录</h1>
  <p>退出后本设备需要重新输入口令才能访问</p>
</div>
<div class="card">
  <form method="post" action="/__auth/logout">
    <input type="hidden" name="csrf" value="$CSRF">
    <div class="out-actions">
      <button class="go" type="submit" style="flex:1">确认退出</button>
      <button class="ghost" type="button" onclick="history.back()">取消</button>
    </div>
  </form>
</div>
""").substitute(MARK=BOOK_SVG, CSRF=html.escape(csrf))
    return render_page("退出登录 · 极客时间离线资料库", main)


# ====================================================================== 处理器
class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"          # 开启 keep-alive
    server_version = "geekbang-offline/2.0"

    def version_string(self):
        return self.server_version         # 不泄露 Python 版本

    def handle_one_request(self):
        self._extra = []                   # 每个请求清空待注入的响应头
        try:
            super().handle_one_request()
        except (ConnectionResetError, BrokenPipeError):
            self.close_connection = True

    def end_headers(self):
        for k, v in getattr(self, "_extra", []):
            self.send_header(k, v)
        super().end_headers()

    def _add_header(self, k, v):
        self._extra.append((k, v))

    # ---------------------------------------------------------------- 小工具
    def _cache_control(self, path):
        # 受保护内容必须 private，防止共享缓存把资料发给未登录者
        if os.sep + "assets" + os.sep in path:
            return "private, max-age=604800"
        if os.path.splitext(path)[1].lower() in (".html", ".htm"):
            return "private, no-cache"      # 重建后尽快生效
        return "private, max-age=86400"

    # ---------------------------------------------------------------- 双根映射
    @staticmethod
    def _course_segment(name):
        """一级目录是否可能是课程：与 build_catalog.scan / BuildQueue.allowed 一致。"""
        return not (name.startswith(("_", ".")) or name.endswith("_old")
                    or name in ("assets", "template"))

    def translate_path(self, path):
        """课程文件夹外置时，把 /<课程名>/... 映射到 data_dir，其余仍走服务根。

        先复用父类结果（unquote / normpath / 丢弃 `..`），再按首段分流：只有首段
        在 data_dir 下确实是目录且不是站点资源时才映射。URL 契约 /<课程名>/...
        不变，因此鉴权、敏感扩展 404、Range/gzip/缓存、登出注入全部照旧。
        """
        local = super().translate_path(path)
        srv = self.server
        root = getattr(srv, "root", None)
        data_dir = getattr(srv, "data_dir", None)
        if not root or not data_dir or os.path.normcase(root) == os.path.normcase(data_dir):
            return local
        base = os.path.abspath(getattr(self, "directory", None) or root)
        try:
            rel = os.path.relpath(os.path.normpath(local), base)
        except ValueError:
            return local
        if rel in ("", ".", "..") or rel.startswith(".." + os.sep):
            return local
        parts = rel.split(os.sep)
        if not self._course_segment(parts[0]):
            return local
        if not os.path.isdir(os.path.join(data_dir, parts[0])):
            return local
        mapped = os.path.join(data_dir, *parts)
        try:
            real_root = os.path.realpath(data_dir)
            if os.path.commonpath([real_root, os.path.realpath(mapped)]) != real_root:
                return local
        except (ValueError, OSError):
            return local
        return mapped

    def _try_open(self):
        """把请求路径解析成本地文件；打不开返回 None（交给父类处理 301/404/列表）。"""
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            index = os.path.join(path, "index.html")
            if os.path.isfile(index):
                return index
            return None
        if os.path.isfile(path):
            return path
        return None

    @staticmethod
    def _parse_range(header, size):
        m = RANGE_RE.match(header or "")
        if not m:
            return None
        start_s, end_s = m.group(1), m.group(2)
        if not start_s and not end_s:
            return None
        if start_s:
            start = int(start_s)
            end = int(end_s) if end_s else size - 1
        else:                               # bytes=-N：最后 N 字节
            start = max(size - int(end_s), 0)
            end = size - 1
        if start >= size:
            return "unsatisfiable"
        return start, min(end, size - 1)

    def _write(self, data):
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _stream(self, fh, remaining):
        """按 64KB 分块发送，避免把音视频整文件读入内存。"""
        try:
            while remaining > 0:
                chunk = fh.read(min(65536, remaining))
                if not chunk:
                    self.close_connection = True
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _cookies(self):
        jar = SimpleCookie()
        try:
            jar.load(self.headers.get("Cookie", ""))
        except Exception:
            pass
        return {k: v.value for k, v in jar.items()}

    def _client_key(self):
        """限流/审计用的来源标识。隧道下恒为 127.0.0.1；--trust-proxy 且对端为回环时取 XFF。"""
        ip = self.client_address[0]
        if self.server.cfg.trust_proxy:
            try:
                if ipaddress.ip_address(ip).is_loopback:
                    fwd = self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
                    if fwd and re.fullmatch(r"[0-9A-Fa-f:.]{3,45}", fwd):
                        ip = fwd
            except ValueError:
                pass
        return ip

    @staticmethod
    def _safe_next(raw):
        """防开放重定向：只接受站内绝对路径。"""
        if not raw or not raw.startswith("/") or raw.startswith("//"):
            return "/"
        if "\\" in raw or ":" in raw or any(ord(c) < 32 for c in raw):
            return "/"
        if raw.startswith("/__auth"):
            return "/"
        return raw

    def _send_html(self, doc, status=HTTPStatus.OK, no_store=False):
        body = doc.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if no_store:
            self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self._write(body)

    def _send_json(self, obj, status=HTTPStatus.OK, no_store=True, gzip_ok=True):
        body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Vary", "Cookie")
        if no_store:
            self.send_header("Cache-Control", "no-store")
        if gzip_ok and len(body) > 1024 and "gzip" in self.headers.get("Accept-Encoding", ""):
            body = gzip.compress(body, 5)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self._write(body)

    def _redirect(self, location, status=HTTPStatus.FOUND):
        body = b""
        self.send_response(status)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------------------------------------------------------------- 鉴权门
    @staticmethod
    def _is_sensitive(url_path):
        parts = [p for p in url_path.split("/") if p]
        if any(p.startswith(".") for p in parts):
            return True
        return os.path.splitext(url_path)[1].lower() in DENY_EXTS

    def _session_payload(self, now):
        """返回 (payload, reissue_token)；未登录返回 (None, None)。"""
        cfg = self.server.cfg
        token = self._cookies().get(SESSION_COOKIE, "")
        if not token:
            return None, None
        return self.server.signer.verify(
            token, now, cfg.idle_days * 86400, cfg.max_days * 86400)

    def _gate(self, url_path):
        """静态资源的统一鉴权钩子。放行返回 True，否则已写好响应返回 False。"""
        cfg = self.server.cfg
        if cfg.no_auth:
            return True
        if self._is_sensitive(url_path):
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
            return False
        now = time.time()
        payload, reissue = self._session_payload(now)
        if payload is None:
            accept = self.headers.get("Accept", "")
            if "text/html" in accept:
                nxt = urllib.parse.quote(self.path, safe="/%?=&")
                self._redirect("/__auth/login?next=" + nxt)
            else:
                body = b"401 Unauthorized"
                self.send_response(HTTPStatus.UNAUTHORIZED)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self._write(body)
            return False
        if reissue:
            max_age = max(0, int(reissue and self._abs_of(reissue) - now))
            self._add_header("Set-Cookie", _cookie(SESSION_COOKIE, reissue, max_age=max_age))
        return True

    @staticmethod
    def _abs_of(token):
        try:
            payload_b64 = token.split(".")[1]
            return int(json.loads(SessionSigner._b64d(payload_b64))["abs"])
        except Exception:
            return int(time.time())

    def _should_inject(self, path):
        """只有站点首页与总目录页注入登出按钮；阅读器/正文页保持原样。"""
        if self.server.cfg.no_auth:            # 调试模式没有会话可退，注入会误导
            return False
        return os.path.normcase(os.path.abspath(path)) in self.server.page_inject_targets

    def _send_home(self):
        """动态首页：实时扫描子目录 + 清单；成功返回 True，失败返回 False 交给静态分支。"""
        site = self.server.site
        states = self.server.build_queue.states() if self.server.build_queue else None
        try:
            doc = site.home_html(states).encode("utf-8")
        except Exception as exc:
            sys.stderr.write("  [site] 首页渲染失败，回退静态副本：%s\n" % exc)
            return False
        if not self.server.cfg.no_auth:
            doc = inject_logout_widget(doc)
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "private, no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Vary", "Cookie")
        self.send_header("Content-Length", str(len(doc)))
        self.end_headers()
        if self.command != "HEAD":
            self._write(doc)
        return True

    # ---------------------------------------------------------------- 响应
    def send_head(self):
        split = urllib.parse.urlsplit(self.path)
        url_path = split.path
        if not self._gate(url_path):
            return None
        if url_path in ("/", "/index.html") and self.server.site is not None:
            if self._send_home():
                return None
            # 渲染失败时继续走静态副本，避免首页 500
        path = self._try_open()
        if path is None:
            # 目录存在但没有 index.html：不列举文件
            local = self.translate_path(url_path)
            if os.path.isdir(local):
                self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                return None
            return super().send_head()

        head_only = self.command == "HEAD"
        ctype = self.guess_type(path)
        ext = os.path.splitext(path)[1].lower()

        # ---- 注入登出按钮：只有 index.html 命中，体积小，保留整读
        if self._should_inject(path):
            try:
                with open(path, "rb") as f:
                    raw = f.read()
            except OSError:
                self.send_error(HTTPStatus.NOT_FOUND, "File not found")
                return None
            raw = inject_logout_widget(raw)
            size = len(raw)
            headers = [
                ("Content-Type", ctype),
                ("Cache-Control", self._cache_control(path)),
                ("X-Content-Type-Options", "nosniff"),
                ("Vary", "Cookie"),
            ]
            if ext in COMPRESSIBLE and "gzip" in self.headers.get("Accept-Encoding", ""):
                body = gzip.compress(raw, 5)
                self.send_response(HTTPStatus.OK)
                for k, v in headers:
                    self.send_header(k, v)
                self.send_header("Content-Encoding", "gzip")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if not head_only:
                    self._write(body)
                return None
            self.send_response(HTTPStatus.OK)
            for k, v in headers:
                self.send_header(k, v)
            self.send_header("Content-Length", str(size))
            self.end_headers()
            if not head_only:
                self._write(raw)
            return None

        try:
            size = os.stat(path).st_size
            fh = open(path, "rb")
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return None

        try:
            base_headers = [
                ("Content-Type", ctype),
                ("Accept-Ranges", "bytes"),
                ("Cache-Control", self._cache_control(path)),
                ("X-Content-Type-Options", "nosniff"),
                ("Vary", "Cookie"),
            ]

            # ---- Range 请求（音视频拖动 / PDF 分段）：只发请求区间，不读整个文件
            rng = self._parse_range(self.headers.get("Range"), size)
            if rng == "unsatisfiable":
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            if rng:
                start, end = rng
                self.send_response(HTTPStatus.PARTIAL_CONTENT)
                for k, v in base_headers:
                    self.send_header(k, v)
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
                self.send_header("Content-Length", str(end - start + 1))
                self.end_headers()
                if not head_only:
                    fh.seek(start)
                    self._stream(fh, end - start + 1)
                return None

            # ---- 普通请求：小文本可 gzip；音视频/PDF/图片直接流式发送
            want_gzip = (
                ext in COMPRESSIBLE
                and size <= GZIP_MAX_BYTES
                and "gzip" in self.headers.get("Accept-Encoding", "")
            )
            if want_gzip:
                body = gzip.compress(fh.read(), 5)
                self.send_response(HTTPStatus.OK)
                for k, v in base_headers:
                    self.send_header(k, v)
                self.send_header("Content-Encoding", "gzip")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if not head_only:
                    self._write(body)
                return None

            self.send_response(HTTPStatus.OK)
            for k, v in base_headers:
                self.send_header(k, v)
            self.send_header("Content-Length", str(size))
            self.end_headers()
            if not head_only:
                self._stream(fh, size)
            return None
        finally:
            fh.close()

    def do_HEAD(self):
        split = urllib.parse.urlsplit(self.path)
        if split.path == "/api" or split.path.startswith("/api/"):
            self._route_api()
            return
        if split.path in ("/__auth/login", "/__auth/logout"):
            self._route_head_auth(split.path)
            return
        self.send_head()

    def _route_api(self):
        api = getattr(self.server, "api", None)
        if api is None:
            self._send_json(
                {"error": {"code": "not_found", "message": "api disabled"}},
                status=HTTPStatus.NOT_FOUND)
            return
        api.handle(self)

    # ---------------------------------------------------------------- __auth 路由
    def do_GET(self):
        split = urllib.parse.urlsplit(self.path)
        url_path = split.path
        if url_path == "/api" or url_path.startswith("/api/"):
            self._route_api()
            return
        if url_path == "/__auth":
            self._redirect("/__auth/login")
            return
        if url_path == "/__auth/login":
            self._route_login_get(split)
            return
        if url_path == "/__auth/logout":
            self._route_logout_get()
            return
        if url_path.startswith("/__auth/"):
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
            return
        if url_path == "/__build" or url_path.startswith("/__build/"):
            self._route_build(url_path)
            return
        super().do_GET()

    def _route_build(self, url_path):
        """按需构建：/__build/<课程> 触发，/__build/status/<课程> 查询状态。"""
        if not self._gate("/__build"):          # 统一门禁；构建入口绝不能免登录
            return
        queue = self.server.build_queue
        if queue is None:
            self.send_error(HTTPStatus.NOT_FOUND, "Auto-build disabled")
            return
        rest = urllib.parse.unquote(url_path[len("/__build"):]).lstrip("/")
        if rest.startswith("status/"):
            info = queue.status(rest[len("status/"):])
            body = json.dumps(info, ensure_ascii=False).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self._write(body)
            return
        real, state = queue.request(rest)
        if real is None:
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
            return
        self.server.audit.write("build", self._client_key(), "%s -> %s" % (real, state))
        if state == "ready":
            self._redirect("/" + urllib.parse.quote(real, safe="") + "/reader/index.html",
                           status=HTTPStatus.SEE_OTHER)
            return
        doc = _build_page(real, state).encode("utf-8")
        self.send_response(HTTPStatus.ACCEPTED if state == "building" else HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(doc)))
        self.end_headers()
        self._write(doc)

    def _route_head_auth(self, url_path):
        """HEAD 登录/登出页：返回同 GET 的头部（不推荐使用，仅为完整性）。"""
        if url_path == "/__auth/login":
            self._route_login_get(urllib.parse.urlsplit(self.path))
        else:
            self._route_logout_get()

    def _issue_csrf(self):
        token = secrets.token_urlsafe(16)
        self._add_header("Set-Cookie", _cookie(CSRF_COOKIE, token, max_age=3600))
        return token

    def _route_login_get(self, split):
        cfg = self.server.cfg
        if cfg.no_auth:
            self._redirect("/")
            return
        qs = urllib.parse.parse_qs(split.query)
        next_url = self._safe_next(qs.get("next", [""])[0])
        csrf = self._issue_csrf()
        doc = render_login(
            next_url, csrf,
            logged_out=qs.get("logged_out", [""])[0] == "1",
            idle_days=cfg.idle_days)
        self._security_headers()
        self._send_html(doc, no_store=True)

    def _route_logout_get(self):
        if self.server.cfg.no_auth:
            self._redirect("/")
            return
        csrf = self._issue_csrf()
        self._security_headers()
        self._send_html(render_logout(csrf), no_store=True)

    def _security_headers(self):
        self._add_header("X-Frame-Options", "DENY")
        self._add_header("Content-Security-Policy", "frame-ancestors 'none'")

    def _read_form(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length > MAX_POST_BODY or length < 0:
            self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Body too large")
            return None
        raw = self.rfile.read(length) if length else b""
        return urllib.parse.parse_qs(raw.decode("utf-8", "replace"))

    def _check_csrf(self, form):
        cookie_token = self._cookies().get(CSRF_COOKIE, "")
        form_token = form.get("csrf", [""])[0]
        return bool(cookie_token) and hmac.compare_digest(cookie_token, form_token)

    def do_POST(self):
        split = urllib.parse.urlsplit(self.path)
        url_path = split.path
        if url_path == "/api" or url_path.startswith("/api/"):
            self._route_api()
            return
        if url_path == "/__auth/login":
            self._route_login_post()
        elif url_path == "/__auth/logout":
            self._route_logout_post()
        else:
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")

    def _route_login_post(self):
        cfg = self.server.cfg
        if cfg.no_auth:
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
            return
        key = self._client_key()
        now = time.time()
        locked = max(self.server.limiter.locked_seconds(key),
                     self.server.global_limiter.locked_seconds(key))
        form = self._read_form()
        if form is None:
            return
        csrf = self._issue_csrf()
        if not self._check_csrf(form):
            self.server.audit.write("csrf_reject", key, self.path)
            self.send_error(HTTPStatus.FORBIDDEN, "Bad CSRF token")
            return
        next_url = self._safe_next(form.get("next", [""])[0])
        keep = form.get("keep", [""])[0] == "1"
        pw = form.get("password", [""])[0]

        if locked > 0:
            self.server.audit.write("login_locked", key, "remain=%ds" % locked)
            self._security_headers()
            self._send_html(render_login(
                next_url, csrf, lock_secs=locked, pw_value=pw,
                keep_checked=keep, idle_days=cfg.idle_days),
                status=HTTPStatus.TOO_MANY_REQUESTS, no_store=True)
            return

        with self.server.login_sem:
            ok = self.server.store.verify(pw)
        if ok:
            self.server.limiter.success(key)
            self.server.global_limiter.success(key)
            sid = secrets.token_hex(16)
            max_days = cfg.max_days if keep else min(cfg.max_days, 1)
            abs_expire = now + max_days * 86400
            token = self.server.signer.sign(sid, now, abs_expire)
            self.server.audit.write("login_ok", key, "sid=%s next=%s" % (sid[:8], next_url))
            self._add_header("Set-Cookie", _cookie(
                SESSION_COOKIE, token, max_age=int(abs_expire - now)))
            self._redirect(next_url, status=HTTPStatus.SEE_OTHER)
            return

        time.sleep(FAIL_DELAY)
        lock_secs = self.server.limiter.fail(key)
        # 全局兜底（阈值 3 倍）：ssh -R 隧道下 IP 维度退化为全局，防分布式爆破
        self.server.global_limiter.fail(key)
        self.server.audit.write("login_fail", key, "next=%s" % next_url)
        self._security_headers()
        self._send_html(render_login(
            next_url, csrf, error="口令不正确，请重试",
            lock_secs=lock_secs, pw_value=pw, keep_checked=keep,
            idle_days=cfg.idle_days),
            status=HTTPStatus.TOO_MANY_REQUESTS if lock_secs else HTTPStatus.UNAUTHORIZED,
            no_store=True)

    def _route_logout_post(self):
        form = self._read_form()
        if form is None:
            return
        key = self._client_key()
        if not self._check_csrf(form):
            self.server.audit.write("csrf_reject", key, self.path)
            self.send_error(HTTPStatus.FORBIDDEN, "Bad CSRF token")
            return
        self.server.audit.write("logout", key, "")
        self._add_header("Set-Cookie", _cookie(SESSION_COOKIE, "", max_age=0))
        self._redirect("/__auth/login?logged_out=1", status=HTTPStatus.SEE_OTHER)

    # 客户端提前断开（移动端切后台/锁屏）很常见，别打印一屏 traceback
    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.log_date_time_string(), fmt % args))


def _cookie(name, value, max_age=None):
    parts = ["%s=%s" % (name, value), "Path=/", "SameSite=Lax", "HttpOnly"]
    if max_age is not None:
        parts.append("Max-Age=%d" % max_age)
    return "; ".join(parts)


# ====================================================================== 登出入口
# 注入到首页右上角的「登出」胶囊。全部为 ASCII 字节 + 数字字符实体（「登出」写作
# &#30331;&#20986;），不依赖页面编码声明。优先插进页面自带的 <header class="topbar">
# 里，成为顶栏最右侧的一项（顶栏 sticky，滚动时始终可见）；找不到 header 时
# 退化为视口右上角的 fixed 版本。
WIDGET_MARKER = b'id="gk-logout-widget"'

_WIDGET_SKIN = (
    b'align-items:center;gap:6px;height:34px;padding:0 13px;'
    b'border-radius:999px;border:1px solid #E8E9EC;background:rgba(255,255,255,.82);'
    b'-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);'
    b'color:#5B5F6A;font:500 13.5px/1 "PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;'
    b'text-decoration:none;letter-spacing:.5px;white-space:nowrap;flex:none;'
    b'box-shadow:0 4px 14px rgba(23,24,28,.08);box-sizing:border-box;'
    b'transition:color .16s,background .16s,border-color .16s,transform .12s,box-shadow .16s}'
    b'#gk-logout-widget:hover,#gk-logout-widget:focus{color:#F66121;background:#FFF1E9;'
    b'border-color:rgba(246,97,33,.35);transform:translateY(-1px);text-decoration:none}'
    b'#gk-logout-widget:focus-visible{outline:none;box-shadow:0 0 0 4px rgba(246,97,33,.16)}'
    b'#gk-logout-widget:active{transform:scale(.97)}'
    b'#gk-logout-widget svg{display:block;flex:none}'
    b'@media (prefers-reduced-motion:reduce){#gk-logout-widget{transition:none}'
    b'#gk-logout-widget:hover,#gk-logout-widget:focus{transform:none}}'
)

_WIDGET_BODY = (
    b'<a id="gk-logout-widget" href="/__auth/logout" title="&#30331;&#20986;" '
    b'aria-label="&#30331;&#20986;">'
    b'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
    b'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
    b'<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>'
    b'<polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>'
    b'<span>&#30331;&#20986;</span></a>'
)

# 顶栏内联版：桌面跟随 flex 行排在搜索框右侧（即最右）；≤640px 顶栏换行、搜索框
# 独占一行时，用 flex order 把按钮排到第一行最右（logo 之后、搜索框之前），
# 既不落到第三行，也不会压住搜索框（断点与页面自身 .search-bar{width:100%} 对齐）
LOGOUT_WIDGET_INLINE = (
    b'<style>#gk-logout-widget{display:inline-flex;' + _WIDGET_SKIN +
    b'@media (max-width:640px){#gk-logout-widget{order:1;margin-left:auto;'
    b'height:32px;padding:0 11px}.topbar-inner>.search-bar{order:2}}'
    b'</style>' + _WIDGET_BODY
)

# 兜底版：页面没有 header 时固定在视口右上角
LOGOUT_WIDGET_FIXED = (
    b'<style>#gk-logout-widget{position:fixed;right:16px;'
    b'top:calc(12px + env(safe-area-inset-top,0px));z-index:2147483000;display:inline-flex;'
    + _WIDGET_SKIN +
    b'</style>' + _WIDGET_BODY
)


_DIV_TAG_RE = re.compile(rb"<div\b|</div\s*>", re.I)


def _topbar_inner_end(raw):
    """定位 <header> 里 .topbar-inner 这个 div 的闭合标签起点（找不到返回 -1）。

    按钮要成为顶栏 flex 容器的最后一个子项才能排到最右，所以必须精确找到它的
    闭合位置；用标签深度匹配而不是正则找 '</div>'，避免嵌套 div 误判。
    """
    head = raw.find(b"<header")
    if head < 0:
        return -1
    marker = raw.find(b"topbar-inner", head)
    if marker < 0:
        return -1
    start = raw.rfind(b"<div", head, marker)
    if start < 0:
        return -1
    depth = 0
    for tag in _DIV_TAG_RE.finditer(raw, start):
        if tag.group(0).startswith(b"</"):
            depth -= 1
            if depth == 0:
                return tag.start()
        else:
            depth += 1
    return -1


def inject_logout_widget(raw):
    """把「登出」按钮插进页面字节串（幂等）。优先顶栏右侧，其次 </body>，最后 EOF。"""
    if WIDGET_MARKER in raw:
        return raw
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):     # UTF-16：字节替换无意义，放弃
        return raw
    idx = _topbar_inner_end(raw)
    if idx >= 0:
        return raw[:idx] + LOGOUT_WIDGET_INLINE + raw[idx:]
    idx = raw.rfind(b"</header>")
    if idx >= 0:
        return raw[:idx] + LOGOUT_WIDGET_FIXED + raw[idx:]
    idx = raw.rfind(b"</body>")
    if idx < 0:
        idx = raw.rfind(b"</BODY>")
    if idx >= 0:
        return raw[:idx] + LOGOUT_WIDGET_FIXED + raw[idx:]
    return raw + LOGOUT_WIDGET_FIXED


def _build_page(name, state):
    """构建占位页：自动轮询构建状态，完成后跳转阅读器。"""
    esc = html.escape(name)
    reader = "/" + urllib.parse.quote(name, safe="") + "/reader/index.html"
    status_url = "/__build/status/" + urllib.parse.quote(name, safe="")
    if state == "failed":
        title, note, poll = "生成失败", "可以直接重试；若反复失败，请查看服务端日志。", "false"
    else:
        title = "正在生成阅读器…"
        note = "首次打开这门课程会自动生成，通常需要几十秒，请保持页面打开。"
        poll = "true"
    tpl = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ · __COURSE__</title>
<style>
  :root{--ink:#17181c;--sub:#6b7280;--line:#e7e8ec;--bg:#f7f7f5;--accent:#f66121;--card:#fff}
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       padding:24px;background:var(--bg);color:var(--ink);
       font:15px/1.7 -apple-system,"Segoe UI","Noto Sans SC",sans-serif}
  main{width:min(560px,100%);background:var(--card);border:1px solid var(--line);
       border-radius:18px;padding:32px 30px;box-shadow:0 18px 44px rgba(23,24,28,.07)}
  h1{margin:0 0 6px;font-size:21px;letter-spacing:.2px}
  .course{margin:0 0 14px;color:var(--sub);font-size:14px}
  .note{margin:0 0 10px;color:var(--sub)}
  .detail{min-height:1.4em;margin:0 0 18px;color:var(--accent);font-size:13.5px}
  .btn{display:inline-block;margin:0 10px 0 0;padding:9px 16px;border-radius:10px;
       background:var(--accent);color:#fff;text-decoration:none;font-size:14px}
  .btn.ghost{background:#f2f3f5;color:var(--ink)}
</style>
</head>
<body>
<main>
  <h1>__TITLE__</h1>
  <p class="course">__COURSE__</p>
  <p class="note">__NOTE__</p>
  <p class="detail" id="detail"></p>
  <p><a class="btn" href="__READER__" id="go">进入阅读器</a>
     <a class="btn ghost" href="/">返回目录</a></p>
</main>
<script>
(function () {
  var STATUS = __STATUS_JSON__, READER = __READER_JSON__;
  var detail = document.getElementById('detail');
  function tick() {
    fetch(STATUS, { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.state === 'ready') { location.replace(READER); return; }
      if (d.state === 'failed') {
        detail.textContent = '失败：' + (d.detail || '未知原因');
        return;
      }
      detail.textContent = d.elapsed ? ('已用 ' + d.elapsed + ' 秒…') : '正在排队…';
      setTimeout(tick, 2000);
    }).catch(function () { setTimeout(tick, 3000); });
  }
  if (__POLL__) { tick(); }
})();
</script>
</body>
</html>
"""
    return (tpl.replace("__TITLE__", title)
               .replace("__COURSE__", esc)
               .replace("__NOTE__", note)
               .replace("__READER__", html.escape(reader, quote=True))
               .replace("__STATUS_JSON__", json.dumps(status_url, ensure_ascii=False))
               .replace("__READER_JSON__", json.dumps(reader, ensure_ascii=False))
               .replace("__POLL__", poll))


# ====================================================================== 装配
def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="极客时间离线资料服务（带口令门禁）")
    ap.add_argument("port", nargs="?", type=int, default=8888)
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--data-dir", default=None,
                    help="课程文件夹所在目录（只放各门课程的一级子目录）；"
                         "默认与 root 相同，也可用环境变量 GK_DATA_DIR 指定")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--quiet", action="store_true", help="不打印访问日志")
    ap.add_argument("--auth-file", default=None, help="默认 <root>/auth.json")
    ap.add_argument("--no-auth", action="store_true",
                    help="调试专用：关闭全部鉴权（启动时打印醒目告警）")
    ap.add_argument("--trust-proxy", action="store_true",
                    help="对端为回环地址时信任 X-Forwarded-For（供前置 nginx 用）")
    ap.add_argument("--session-idle-days", type=int, default=7, help="空闲滑动超时（天）")
    ap.add_argument("--session-max-days", type=int, default=30, help="绝对有效期（天）")
    ap.add_argument("--lock-fail-count", type=int, default=5, help="窗口内失败几次即锁定")
    ap.add_argument("--lock-window-sec", type=int, default=900, help="失败统计窗口（秒）")
    ap.add_argument("--lock-base-sec", type=int, default=600, help="首次锁定时长（秒），指数退避")
    ap.add_argument("--lock-max-sec", type=int, default=3600, help="锁定上限（秒）")
    ap.add_argument("--set-password", action="store_true", help="交互式设置/修改口令后退出")
    ap.add_argument("--gen-password", action="store_true", help="打印强随机口令后退出")
    ap.add_argument("--revoke-sessions", action="store_true", help="作废所有会话后退出")
    ap.add_argument("--manifest", default=None,
                    help="课程清单，默认 <root>/template/nav/course_manifest.json")
    ap.add_argument("--no-autobuild", action="store_true",
                    help="关闭「首次点击按需生成阅读器」")
    ap.add_argument("--build-jobs", type=int, default=4, help="构建阅读器的并行线程数")
    ap.add_argument("--build-timeout", type=int, default=1800, help="单门课程构建超时（秒）")
    cfg = ap.parse_args(argv)
    cfg.idle_days = cfg.session_idle_days      # 短别名，业务代码用
    cfg.max_days = cfg.session_max_days
    return cfg


def create_server(cfg):
    """按 cfg 装配服务。测试与 main 共用。cfg 需含 root/auth_file 等全部字段。"""
    root = os.path.abspath(cfg.root)
    # 优先级：--data-dir > 环境变量 GK_DATA_DIR > root。
    # 口令管理子命令不经过这里，因此不必存在课程目录也能设置口令。
    data_arg = getattr(cfg, "data_dir", None)
    if data_arg is None:
        data_arg = os.environ.get("GK_DATA_DIR") or None
    data_dir = os.path.abspath(data_arg or root)
    if not os.path.isdir(data_dir):
        sys.stderr.write(
            "\n  [data] 课程数据目录不存在：%s\n"
            "         请用 --data-dir 指定包含各门课程子目录的文件夹\n\n" % data_dir)
        raise SystemExit(2)
    if not cfg.auth_file:
        cfg.auth_file = os.path.join(root, "auth.json")
    store = AuthStore(cfg.auth_file)

    if not store.exists and not cfg.no_auth:
        sys.stderr.write(
            "\n  [auth] 未找到 %s\n"
            "         请先运行： python serve.py --set-password\n\n" % store.path)
        raise SystemExit(2)

    os.chdir(root)
    # 显式固定静态服务基准，避免依赖请求发生时的 CWD（测试会恢复 CWD）。
    httpd = ThreadingHTTPServer(
        (cfg.bind, cfg.port), functools.partial(Handler, directory=root))
    httpd.daemon_threads = True
    httpd.cfg = cfg
    httpd.root = root
    httpd.data_dir = data_dir
    httpd.store = store
    httpd.signer = SessionSigner(store)
    httpd.limiter = RateLimiter(cfg.lock_fail_count, cfg.lock_window_sec,
                                cfg.lock_base_sec, cfg.lock_max_sec)
    httpd.global_limiter = RateLimiter(cfg.lock_fail_count * 3, cfg.lock_window_sec,
                                       cfg.lock_base_sec, cfg.lock_max_sec)
    httpd.audit = AuditLog(os.path.join(root, "auth.log"))
    httpd.login_sem = threading.Semaphore(LOGIN_SEM_LIMIT)
    # 手机端 JSON API：独立模块，未就绪时不影响静态服务
    httpd.api = None
    try:
        import mobile_api
        httpd.api = mobile_api.MobileAPI(
            root=root, store=store, signer=httpd.signer,
            limiter=httpd.limiter, global_limiter=httpd.global_limiter,
            audit=httpd.audit, login_sem=httpd.login_sem, cfg=cfg,
            data_dir=data_dir)
    except Exception as exc:
        sys.stderr.write("  [api] 未启用：%s\n" % exc)
    # 登出按钮注入目标：绝对路径归一化，/ 与 /index.html 自动都命中。
    # 只注入导航首页；catalog.html 是官网课程元数据的 HTML 片段（供 build_catalog.py 解析、
    # 不对外访问），注入既无意义也会污染存档，故不在名单内。
    httpd.page_inject_targets = frozenset(
        [os.path.normcase(os.path.abspath(os.path.join(root, "index.html")))])

    # 动态首页 + 按需构建：模板/清单缺失时自动退化为纯静态服务（测试临时目录走这条分支）
    httpd.site = None
    httpd.build_queue = None
    if os.path.isfile(os.path.join(root, "template", "nav", "build_catalog.py")):
        try:
            site = site_generator.SiteGenerator(
                root, manifest_path=getattr(cfg, "manifest", None),
                data_dir=data_dir)
        except Exception as exc:
            sys.stderr.write("  [site] 未启用动态首页：%s\n" % exc)
        else:
            if site.available:
                httpd.site = site
                if not getattr(cfg, "no_autobuild", False):
                    httpd.build_queue = site_generator.BuildQueue(
                        root, jobs=getattr(cfg, "build_jobs", 4),
                        timeout=getattr(cfg, "build_timeout", 1800),
                        on_done=site.invalidate, data_dir=data_dir)
    return httpd


def main(argv=None):
    import getpass
    cfg = parse_args(argv)

    if cfg.gen_password:
        print(secrets.token_urlsafe(18))
        return 0

    root = os.path.abspath(cfg.root)
    if not cfg.auth_file:
        cfg.auth_file = os.path.join(root, "auth.json")
    store = AuthStore(cfg.auth_file)

    if cfg.set_password:
        first = getpass.getpass("输入访问口令: ")
        if len(first) < 6:
            print("口令太短（至少 6 位）", file=sys.stderr)
            return 2
        second = getpass.getpass("再次输入确认: ")
        if first != second:
            print("两次输入不一致", file=sys.stderr)
            return 2
        existed = store.exists
        store.set_password(first)
        print("口令已%s，auth.json: %s" % ("更新" if existed else "创建", store.path))
        print("注意：旧口令的所有会话已立即失效。")
        return 0

    if cfg.revoke_sessions:
        if not store.exists:
            print("auth.json 不存在", file=sys.stderr)
            return 2
        store.revoke_sessions()
        print("已作废所有会话（epoch=%d）" % store.epoch)
        return 0

    if cfg.no_auth:
        print("=" * 60)
        print("  警告：--no-auth 已开启，任何人都能访问资料！")
        print("=" * 60)

    httpd = create_server(cfg)
    if cfg.quiet:
        Handler.log_message = lambda *a, **k: None
    print("serving %s on http://%s:%d/  auth=%s" % (
        os.path.abspath(cfg.root), cfg.bind, cfg.port,
        "off" if cfg.no_auth else "on"), flush=True)
    if os.path.normcase(httpd.data_dir) != os.path.normcase(httpd.root):
        print("courses: %s (external data dir)" % httpd.data_dir, flush=True)
    if getattr(httpd, "site", None) is not None:
        print("home: dynamic (%d 门课程)  autobuild=%s" % (
            len(httpd.site.courses()), "on" if httpd.build_queue else "off"), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
