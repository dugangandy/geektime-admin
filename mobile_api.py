#!/usr/bin/env python3
"""手机端 JSON API：极客时间本地课程库。

由 serve.py 在 /api/* 上分发，复用同一套 auth.json / 会话签名 / 限流 / 审计。
不依赖任何第三方库；所有写操作只落在 <root>/.mobile/ 下（点目录 + .json 已被静态门禁拦截）。

接口一览（除 ping / login 外都需要 gk_sess）：
    GET  /api/ping
    POST /api/auth/login           {"password": "...", "keep": true}
    GET  /api/auth/session
    POST /api/auth/logout
    GET  /api/courses
    GET  /api/courses/{id}
    GET  /api/courses/{id}/lessons/{idx}
    GET  /api/progress             /api/progress/{id}
    POST /api/progress             {"course": "...", "lesson": 1, "pct": 42, "pos": 10,
                                    "dur": 900, "read": false, "listen_sec": 30}
                                   阅读端传 pos/dur（滚动像素），音频端传
                                   audio_pos/audio_dur（毫秒）；缺省字段保留原值。
    GET  /api/stats
    GET  /api/search?q=...
"""
import hashlib
import hmac
import json
import mmap
import os
import re
import secrets
import shutil
import sys
import threading
import time
import urllib.parse
from collections import OrderedDict
from datetime import date, datetime, timedelta
from http import HTTPStatus

API_VERSION = 1
SERVER_NAME = "geektime-offline"
SESSION_COOKIE = "gk_sess"
MAX_API_BODY = 64 * 1024
READ_THRESHOLD = 88.0
AUDIO_MAX_MS = 86400 * 1000.0
FAIL_DELAY = 0.3

ID_RE = re.compile(r"^[0-9a-f]{12}$")
DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
LESSON_RE = re.compile(
    r'<script type="application/json" id="lesson-data">(.*?)</script>', re.S)
AUDIO_KEY_RE = re.compile(rb'"audio":"([^"\\]*)"')
PDF_KEY_RE = re.compile(rb'"pdf":"([^"\\]*)"')

CATEGORY_LABELS = {
    "ai": "人工智能",
    "backend": "后端/架构",
    "bigdata": "大数据",
    "sec": "安全",
    "cs": "计算机基础",
    "front": "前端/移动",
    "opstest": "运维/测试",
    "lead": "技术领导力",
    "growth": "产品/增长",
    "life": "多元成长",
}


class APIError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _cookie(name, value, max_age=None):
    parts = ["%s=%s" % (name, value), "Path=/", "SameSite=Lax", "HttpOnly"]
    if max_age is not None:
        parts.append("Max-Age=%d" % max_age)
    return "; ".join(parts)


def _clamp_int(value, low, high, default=0):
    try:
        value = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, value))


def _clamp_float(value, low, high, default=0.0):
    try:
        value = float(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, value))


def parse_catalog_js(text):
    """window.COURSE = {...}; → dict；格式异常抛 ValueError。"""
    if not text:
        raise ValueError("empty catalog")
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("catalog object not found")
    data = json.loads(text[start:end + 1])
    if not isinstance(data, dict):
        raise ValueError("catalog is not an object")
    chapters = []
    flat = []
    for chapter in data.get("chapters") or []:
        if not isinstance(chapter, dict):
            continue
        items = []
        for item in chapter.get("items") or []:
            if not isinstance(item, dict):
                continue
            try:
                idx = int(item.get("i"))
            except (TypeError, ValueError):
                continue
            row = {
                "idx": idx,
                "label": str(item.get("label") or ""),
                "date": str(item.get("date") or ""),
                "dur": str(item.get("dur") or ""),
                "chapter": str(chapter.get("name") or ""),
            }
            items.append(row)
            flat.append(row)
        if items:
            chapters.append({"name": str(chapter.get("name") or ""), "items": items})
    total = data.get("total")
    try:
        total = int(total)
    except (TypeError, ValueError):
        total = 0
    if total <= 0:
        total = len(flat)
    return {
        "title": str(data.get("title") or ""),
        "author": str(data.get("author") or ""),
        "total": total,
        "chapters": chapters,
        "items": flat,
    }


class CourseIndex:
    """课程清单 + catalog.js 解析缓存；只收录磁盘上已有阅读器的课程。"""

    def __init__(self, root, manifest_path=None, data_dir=None):
        self.root = os.path.abspath(root)
        # 课程目录可与服务根分离；清单与封面仍在 root。
        self.data_dir = os.path.abspath(data_dir) if data_dir else self.root
        self.manifest_path = manifest_path or os.path.join(
            self.root, "_导航模版", "course_manifest.json")
        self._lock = threading.RLock()
        self._manifest_mtime = None
        self._courses = []
        self._by_id = {}
        self._catalog = {}
        self.ensure()

    def ensure(self):
        try:
            mtime = os.stat(self.manifest_path).st_mtime
        except OSError:
            mtime = 0.0
        if self._manifest_mtime == mtime and self._courses:
            return
        with self._lock:
            if self._manifest_mtime == mtime and self._courses:
                return
            self._reload(mtime)

    def _reload(self, mtime):
        try:
            with open(self.manifest_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except OSError:
            data = {}                           # 没有清单时 API 仍可用，只是没有课程
        except ValueError as exc:
            sys.stderr.write("[api] 清单解析失败：%s\n" % exc)
            data = {}
        if not isinstance(data, dict):
            data = {}
        courses, by_id, seen = [], {}, set()
        for entry in data.get("courses") or []:
            if not isinstance(entry, dict):
                continue
            name = str(entry.get("name") or "").strip()
            if not name:
                continue
            course_dir = os.path.join(self.data_dir, name)
            catalog_path = os.path.join(course_dir, "reader", "data", "catalog.js")
            if not os.path.isfile(catalog_path):
                continue
            cid = hashlib.sha1(name.encode("utf-8")).hexdigest()[:12]
            if cid in seen:
                continue
            seen.add(cid)
            cover = str(entry.get("cover") or "")
            cover_file = self._cover_file(cover, name)
            category = str(entry.get("dir") or "")
            course = {
                "id": cid,
                "name": name,
                "path": course_dir,
                "category": category,
                "category_label": CATEGORY_LABELS.get(category, category or "其他"),
                "cover_url": ("/assets/covers/" + urllib.parse.quote(cover_file, safe="")
                              if cover_file else ""),
                "badge": str(entry.get("badge") or ""),
                "author": str(entry.get("author") or ""),
                "identity": str(entry.get("identity") or ""),
                "learners": str(entry.get("learners_text") or ""),
                "manifest_lessons": _clamp_int(entry.get("lessons"), 0, 99999, 0),
                "catalog_path": catalog_path,
            }
            courses.append(course)
            by_id[cid] = course
        with self._lock:
            self._courses = courses
            self._by_id = by_id
            self._catalog.clear()
            self._manifest_mtime = mtime

    def _cover_file(self, cover, name):
        if not cover:
            return ""
        candidates = [cover]
        if not cover.lower().endswith(".webp"):
            candidates.append(name + ".webp")
        covers = os.path.join(self.root, "assets", "covers")
        for candidate in candidates:
            if os.path.isfile(os.path.join(covers, candidate)):
                return candidate
        return ""

    def courses(self):
        self.ensure()
        return list(self._courses)

    def get(self, cid):
        self.ensure()
        return self._by_id.get(cid)

    def catalog(self, course):
        path = course["catalog_path"]
        try:
            mtime = os.stat(path).st_mtime
        except OSError:
            return {"title": course["name"], "author": course["author"],
                    "total": 0, "chapters": [], "items": []}
        with self._lock:
            cached = self._catalog.get(course["id"])
            if cached and cached[0] == mtime:
                return cached[1]
        try:
            with open(path, "r", encoding="utf-8") as f:
                parsed = parse_catalog_js(f.read())
        except (OSError, ValueError) as exc:
            sys.stderr.write("[api] catalog 解析失败 %s：%s\n" % (path, exc))
            parsed = {"title": course["name"], "author": course["author"],
                      "total": 0, "chapters": [], "items": []}
        with self._lock:
            self._catalog[course["id"]] = (mtime, parsed)
        return parsed

    @staticmethod
    def page_path(course, idx):
        return os.path.join(course["path"], "reader", "pages", "%03d.html" % idx)


class ProgressStore:
    """<root>/.mobile/progress.json：原子写、时间戳合并、日统计。"""

    def __init__(self, path):
        self.path = os.path.abspath(path)
        self._lock = threading.RLock()
        self._data = None
        self._mtime = None

    def _empty(self):
        return {"v": 1, "courses": {}, "daily": {}}

    def _load(self):
        try:
            mtime = os.stat(self.path).st_mtime
        except OSError:
            mtime = None
        if self._data is not None and self._mtime == mtime:
            return self._data
        data = None
        if mtime is not None:
            try:
                with open(self.path, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                if isinstance(loaded, dict):
                    data = loaded
            except (OSError, ValueError):
                data = None
            if data is None:
                try:
                    shutil.copy2(self.path, self.path + ".corrupt")
                except OSError:
                    pass
        if not isinstance(data, dict):
            data = self._empty()
        data.setdefault("v", 1)
        data.setdefault("courses", {})
        data.setdefault("daily", {})
        self._data, self._mtime = data, mtime
        return data

    def _save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        if os.path.isfile(self.path):
            try:
                shutil.copy2(self.path, self.path + ".bak")
            except OSError:
                pass
        os.replace(tmp, self.path)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass
        self._mtime = os.stat(self.path).st_mtime

    def snapshot(self):
        with self._lock:
            data = self._load()
            return json.loads(json.dumps(data, ensure_ascii=False))

    def update(self, course_id, lesson, pct, pos, dur, read, listen_sec,
               audio_pos=None, audio_dur=None):
        now = int(time.time())
        with self._lock:
            data = self._load()
            courses = data["courses"]
            course = courses.setdefault(course_id, {"lessons": {}, "last": None})
            lessons = course.setdefault("lessons", {})
            key = str(lesson)
            rec = lessons.get(key) or {
                "pct": 0.0, "pos": 0.0, "dur": 0.0, "read": False, "touched": 0}
            old_read = bool(rec.get("read"))
            new_read = old_read or bool(read) or pct >= READ_THRESHOLD
            merged = {
                "pct": round(max(float(rec.get("pct") or 0), pct), 1),
                "read": new_read,
                "touched": now,
            }
            # pos/dur 是阅读滚动像素，audio_pos/audio_dur 是音频毫秒，单位不同不可互相覆盖；
            # 传 None 表示本次上报不涉及该字段，保留原值。
            if pos is not None:
                merged["pos"] = round(pos, 1)
            if dur is not None:
                merged["dur"] = round(dur, 1)
            if audio_pos is not None:
                merged["audio_pos"] = round(audio_pos, 1)
            if audio_dur is not None:
                merged["audio_dur"] = round(audio_dur, 1)
            rec.update(merged)
            lessons[key] = rec
            course["last"] = {"lesson": lesson, "at": now}
            day = datetime.now().strftime("%Y-%m-%d")
            daily = data["daily"].setdefault(day, {"read": 0, "seconds": 0})
            if new_read and not old_read:
                daily["read"] = int(daily.get("read") or 0) + 1
            if listen_sec:
                daily["seconds"] = int(daily.get("seconds") or 0) + listen_sec
            self._prune_daily(data)
            self._save()
            return {"lesson": lesson, "pct": rec["pct"], "read": new_read, "at": now}

    @staticmethod
    def _prune_daily(data):
        daily = data.get("daily") or {}
        if len(daily) <= 400:
            return
        keep = sorted(daily.keys())[-400:]
        data["daily"] = {k: daily[k] for k in keep}


class MobileAPI:
    def __init__(self, root, store, signer, limiter, global_limiter, audit,
                 login_sem, cfg, data_dir=None):
        self.root = os.path.abspath(root)
        # 课程与媒体文件在 data_dir；清单、封面、学习进度仍在 root。
        self.data_dir = os.path.abspath(data_dir) if data_dir else self.root
        self.store = store
        self.signer = signer
        self.limiter = limiter
        self.global_limiter = global_limiter
        self.audit = audit
        self.login_sem = login_sem
        self.cfg = cfg
        self.index = CourseIndex(self.root, getattr(cfg, "manifest", None),
                                 data_dir=self.data_dir)
        self.progress = ProgressStore(os.path.join(self.root, ".mobile", "progress.json"))
        self._lesson_lock = threading.RLock()
        self._lesson_cache = OrderedDict()      # path -> (mtime, dict, bytes)
        self._lesson_bytes = 0
        self._media_cache = {}                  # (path, mtime) -> {audio_url, pdf_url}

    # ------------------------------------------------------------ 入口
    def handle(self, h):
        try:
            split = urllib.parse.urlsplit(h.path)
            path, qs, method = split.path, urllib.parse.parse_qs(split.query), h.command
            if path == "/api/ping" and method in ("GET", "HEAD"):
                return self._ping(h)
            if path == "/api/auth/login":
                if method != "POST":
                    raise APIError(405, "method_not_allowed", "use POST")
                return self._login(h)
            if not self._require_auth(h):
                return
            if path == "/api/auth/session" and method == "GET":
                return self._session(h)
            if path == "/api/auth/logout" and method == "POST":
                return self._logout(h)
            if path == "/api/courses" and method == "GET":
                return self._courses(h, qs)
            m = re.match(r"^/api/courses/([0-9a-f]{12})$", path)
            if m and method == "GET":
                return self._course(h, m.group(1))
            m = re.match(r"^/api/courses/([0-9a-f]{12})/lessons/(\d{1,4})$", path)
            if m and method == "GET":
                return self._lesson(h, m.group(1), int(m.group(2)))
            if path == "/api/progress":
                if method == "GET":
                    return self._progress_all(h)
                if method == "POST":
                    return self._progress_update(h)
            m = re.match(r"^/api/progress/([0-9a-f]{12})$", path)
            if m and method == "GET":
                return self._progress_course(h, m.group(1))
            if path == "/api/stats" and method == "GET":
                return self._stats(h)
            if path == "/api/search" and method == "GET":
                return self._search(h, qs)
            raise APIError(404, "not_found", "unknown api route")
        except APIError as exc:
            self._error(h, exc.status, exc.code, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            h.close_connection = True
        except Exception as exc:                # 不让单次异常拖垮服务
            sys.stderr.write("[api] 未处理异常 %s: %s\n" % (type(exc).__name__, exc))
            self._error(h, 500, "server_error", "internal error")

    @staticmethod
    def _error(h, status, code, message, extra=None):
        body = {"error": {"code": code, "message": message}}
        if extra:
            body["error"].update(extra)
        # 出错时请求体可能还没读完（如 Content-Type 不对、体积超限）；
        # 直接关连接，避免残留字节污染同一条 keep-alive 的后续请求。
        if h.command == "POST":
            h.close_connection = True
        h._send_json(body, status=status)

    # ------------------------------------------------------------ 鉴权
    def _ping(self, h):
        authenticated = bool(getattr(self.cfg, "no_auth", False))
        if not authenticated:
            token = h._cookies().get(SESSION_COOKIE, "")
            if token:
                payload, _ = self.signer.verify(
                    token, time.time(),
                    getattr(self.cfg, "idle_days", 7) * 86400,
                    getattr(self.cfg, "max_days", 30) * 86400)
                authenticated = payload is not None
        h._send_json({
            "ok": True,
            "name": SERVER_NAME,
            "version": API_VERSION,
            "authenticated": authenticated,
            "courses": len(self.index.courses()),
        })

    def _require_auth(self, h):
        if getattr(self.cfg, "no_auth", False):
            h._api_payload = None
            return True
        now = time.time()
        token = h._cookies().get(SESSION_COOKIE, "")
        if token:
            payload, reissue = self.signer.verify(
                token, now,
                getattr(self.cfg, "idle_days", 7) * 86400,
                getattr(self.cfg, "max_days", 30) * 86400)
            if payload is not None:
                h._api_payload = payload
                if reissue:
                    max_age = max(0, int(payload.get("abs", now) - now))
                    h._add_header("Set-Cookie", _cookie(SESSION_COOKIE, reissue, max_age))
                return True
        self._error(h, HTTPStatus.UNAUTHORIZED, "unauthenticated", "login required")
        return False

    def _device_key(self, h):
        ip = h.client_address[0] if h.client_address else "-"
        dev = (h.headers.get("X-GK-Device") or "").strip()
        if DEVICE_RE.match(dev):
            return "dev:" + hashlib.sha256((ip + "|" + dev).encode()).hexdigest()[:20]
        return "ip:" + ip

    @staticmethod
    def _origin_ok(h):
        origin = h.headers.get("Origin")
        if not origin:
            return True
        try:
            netloc = urllib.parse.urlsplit(origin).netloc
        except ValueError:
            return False
        host = h.headers.get("Host", "")
        return bool(netloc) and netloc.lower() == host.lower()

    @staticmethod
    def _read_json(h):
        ctype = (h.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if ctype != "application/json":
            raise APIError(415, "unsupported_media_type", "Content-Type must be application/json")
        try:
            length = int(h.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_API_BODY:
            raise APIError(400, "bad_request", "invalid body length")
        raw = h.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise APIError(400, "bad_request", "invalid json")
        if not isinstance(data, dict):
            raise APIError(400, "bad_request", "json object required")
        return data

    def _issue_session(self, h, keep):
        now = time.time()
        sid = secrets.token_hex(16)
        max_days = (getattr(self.cfg, "max_days", 30) if keep else 1)
        abs_expire = now + max_days * 86400
        token = self.signer.sign(sid, now, abs_expire)
        h._add_header("Set-Cookie", _cookie(
            SESSION_COOKIE, token, max_age=int(abs_expire - now)))
        return sid, int(abs_expire)

    def _login(self, h):
        if not self._origin_ok(h):
            raise APIError(403, "forbidden", "origin not allowed")
        if (h.headers.get("X-GK-Client") or "").lower() != "android":
            raise APIError(403, "forbidden", "android client header required")
        data = self._read_json(h)
        password = data.get("password")
        keep = bool(data.get("keep", True))
        if not isinstance(password, str) or not password or len(password) > 256:
            raise APIError(400, "bad_request", "password required")
        if getattr(self.cfg, "no_auth", False):
            h._send_json({"ok": True, "expires_at": 0,
                          "courses": len(self.index.courses()), "no_auth": True})
            return

        key = self._device_key(h)
        global_key = "api-global"
        locked = max(self.limiter.locked_seconds(key),
                     self.global_limiter.locked_seconds(global_key))
        if locked > 0:
            self.audit.write("api_login_locked", h._client_key(), "remain=%ds" % locked)
            self._error(h, HTTPStatus.TOO_MANY_REQUESTS, "locked",
                        "too many attempts", {"retry_after": locked})
            return

        with self.login_sem:
            ok = self.store.verify(password)
        if ok:
            self.limiter.success(key)
            self.global_limiter.success(global_key)
            sid, expires_at = self._issue_session(h, keep)
            self.audit.write("api_login_ok", h._client_key(), "sid=%s" % sid[:8])
            h._send_json({"ok": True, "expires_at": expires_at,
                          "courses": len(self.index.courses())})
            return

        time.sleep(FAIL_DELAY)
        lock_secs = self.limiter.fail(key)
        self.global_limiter.fail(global_key)
        self.audit.write("api_login_fail", h._client_key(), "")
        if lock_secs:
            self._error(h, HTTPStatus.TOO_MANY_REQUESTS, "locked",
                        "too many attempts", {"retry_after": lock_secs})
        else:
            self._error(h, HTTPStatus.UNAUTHORIZED, "bad_password", "password incorrect")

    def _session(self, h):
        payload = getattr(h, "_api_payload", None) or {}
        h._send_json({
            "ok": True,
            "expires_at": int(payload.get("abs", 0)),
            "courses": len(self.index.courses()),
        })

    def _logout(self, h):
        h._add_header("Set-Cookie", _cookie(SESSION_COOKIE, "", max_age=0))
        self.audit.write("api_logout", h._client_key(), "")
        h._send_json({"ok": True})

    # ------------------------------------------------------------ 课程
    def _progress_for(self, cid, snap=None):
        # snapshot() 会整份深拷贝进度库，列表接口必须先取一次再复用
        courses = snap if snap is not None else self.progress.snapshot()["courses"]
        entry = courses.get(cid) or {}
        return entry.get("lessons") or {}, entry.get("last")

    def _course_row(self, course, snap):
        catalog = self.index.catalog(course)
        lessons, last = self._progress_for(course["id"], snap)
        read = sum(1 for item in lessons.values() if item.get("read"))
        total = catalog["total"] or course["manifest_lessons"]
        last_row = None
        if last:
            last_row = {"lesson": last.get("lesson"),
                        "title": self._label(catalog, last.get("lesson")),
                        "at": last.get("at", 0)}
        return {
            "id": course["id"],
            "name": course["name"],
            "author": course["author"],
            "identity": course["identity"],
            "category": course["category"],
            "category_label": course["category_label"],
            "badge": course["badge"],
            "learners": course["learners"],
            "total": total,
            "cover_url": course["cover_url"],
            "read": read,
            "pct": int(round(read * 100.0 / total)) if total else 0,
            "started": bool(read or lessons),
            "last": last_row,
        }

    def _courses(self, h, qs):
        self.index.ensure()
        category = (qs.get("category", [""])[0] or "").strip()
        query = (qs.get("q", [""])[0] or "").strip().lower()
        if len(query) > 64:
            raise APIError(400, "bad_request", "query too long")
        counts = {}
        rows = []
        snap = self.progress.snapshot()["courses"]
        for course in self.index.courses():
            counts[course["category"]] = counts.get(course["category"], 0) + 1
            if category and course["category"] != category:
                continue
            if query and query not in course["name"].lower() \
                    and query not in course["author"].lower():
                continue
            rows.append(self._course_row(course, snap))
        rows.sort(key=lambda r: (-(r["last"]["at"] if r["last"] else 0),))
        categories = [
            {"key": key, "label": CATEGORY_LABELS.get(key, key), "count": count}
            for key, count in counts.items() if count
        ]
        categories.sort(key=lambda c: list(CATEGORY_LABELS).index(c["key"])
                        if c["key"] in CATEGORY_LABELS else 99)
        h._send_json({"categories": categories, "courses": rows})

    @staticmethod
    def _label(catalog, idx):
        for item in catalog["items"]:
            if item["idx"] == idx:
                return item["label"]
        return ""

    def _course(self, h, cid):
        if not ID_RE.match(cid):
            raise APIError(400, "bad_request", "bad course id")
        course = self.index.get(cid)
        if course is None:
            raise APIError(404, "not_found", "course not found")
        catalog = self.index.catalog(course)
        lessons, last = self._progress_for(cid)
        chapters = []
        for chapter in catalog["chapters"]:
            items = []
            for item in chapter["items"]:
                media = self._media(course, item["idx"])
                rec = lessons.get(str(item["idx"])) or {}
                items.append({
                    "idx": item["idx"],
                    "label": item["label"],
                    "date": item["date"],
                    "dur": item["dur"],
                    "has_audio": bool(media["audio_url"]),
                    "has_pdf": bool(media["pdf_url"]),
                    "audio_url": media["audio_url"],
                    "pdf_url": media["pdf_url"],
                    "read": bool(rec.get("read")),
                    "pct": round(float(rec.get("pct") or 0), 1),
                    "pos": round(float(rec.get("pos") or 0), 1),
                })
            chapters.append({"name": chapter["name"], "items": items})
        read = sum(1 for item in lessons.values() if item.get("read"))
        total = catalog["total"] or course["manifest_lessons"]
        last_row = None
        if last:
            last_row = {"lesson": last.get("lesson"),
                        "title": self._label(catalog, last.get("lesson")),
                        "at": last.get("at", 0)}
        h._send_json({
            "id": cid,
            "name": course["name"],
            "author": course["author"],
            "identity": course["identity"],
            "category": course["category"],
            "category_label": course["category_label"],
            "badge": course["badge"],
            "learners": course["learners"],
            "cover_url": course["cover_url"],
            "total": total,
            "read": read,
            "pct": int(round(read * 100.0 / total)) if total else 0,
            "chapters": chapters,
            "last": last_row,
        })

    def _media(self, course, idx):
        """一讲有哪些可播/可下载资源。返回已校验过的站内绝对路径。"""
        path = CourseIndex.page_path(course, idx)
        try:
            mtime = os.stat(path).st_mtime
        except OSError:
            return {"audio_url": "", "pdf_url": ""}
        key = (path, mtime)
        with self._lesson_lock:
            cached = self._media_cache.get(key)
        if cached is not None:
            return cached
        # 个别页面的「封面」是 base64 内联图，会把 audio/pdf 键推到 800KB 之后，
        # 所以用 mmap 全页扫描（由 OS 按页换入，不复制整文件）而不是只读前 4KB。
        rel = {"audio": b"", "pdf": b""}
        try:
            with open(path, "rb") as f:
                with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
                    audio = AUDIO_KEY_RE.search(mm)
                    pdf = PDF_KEY_RE.search(mm)
                    if audio:
                        rel["audio"] = audio.group(1)
                    if pdf:
                        rel["pdf"] = pdf.group(1)
        except (OSError, ValueError):
            pass
        result = {
            "audio_url": self._resolve_media(course, rel["audio"].decode("utf-8", "replace")),
            "pdf_url": self._resolve_media(course, rel["pdf"].decode("utf-8", "replace")),
        }
        with self._lesson_lock:
            if len(self._media_cache) > 4000:
                self._media_cache.clear()
            self._media_cache[key] = result
        return result

    def _lesson(self, h, cid, idx):
        course = self.index.get(cid)
        if course is None:
            raise APIError(404, "not_found", "course not found")
        path = CourseIndex.page_path(course, idx)
        if not (0 <= idx <= 9999) or not os.path.isfile(path):
            raise APIError(404, "not_found", "lesson not found")
        data = self._load_lesson(path)
        if data is None:
            raise APIError(404, "not_found", "lesson data missing")
        catalog = self.index.catalog(course)
        item = next((row for row in catalog["items"] if row["idx"] == idx), None)
        pos = next((i for i, row in enumerate(catalog["items"]) if row["idx"] == idx), -1)
        prev_row = catalog["items"][pos - 1] if pos > 0 else None
        next_row = (catalog["items"][pos + 1]
                    if 0 <= pos < len(catalog["items"]) - 1 else None)
        media = self._media(course, idx)
        # 客户端 WebView 用它当 base，正文里的相对路径图片才能解析到讲次所在目录
        page_rel = os.path.relpath(path, self.data_dir).replace(os.sep, "/")
        h._send_json({
            "idx": idx,
            "page_url": "/" + urllib.parse.quote(page_rel, safe="/"),
            "total": int(data.get("total") or catalog["total"]),
            "kind": str(data.get("kind") or ""),
            "title": str(data.get("title") or (item or {}).get("label") or ""),
            "course": course["name"],
            "author": course["author"],
            "date": (item or {}).get("date", ""),
            "duration": str(data.get("duration") or (item or {}).get("dur") or ""),
            "cover_url": course["cover_url"],
            "audio_url": media["audio_url"],
            "pdf_url": media["pdf_url"],
            "body_html": data.get("body") or "",
            "body_bytes": len((data.get("body") or "").encode("utf-8")),
            "prev": ({"idx": prev_row["idx"], "title": prev_row["label"]}
                     if prev_row else None),
            "next": ({"idx": next_row["idx"], "title": next_row["label"]}
                     if next_row else None),
        })

    def _load_lesson(self, path):
        try:
            mtime = os.stat(path).st_mtime
        except OSError:
            return None
        with self._lesson_lock:
            cached = self._lesson_cache.get(path)
            if cached and cached[0] == mtime:
                self._lesson_cache.move_to_end(path)
                return cached[1]
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError:
            return None
        m = LESSON_RE.search(text)
        if not m:
            return None
        try:
            data = json.loads(m.group(1))
        except ValueError:
            return None
        if not isinstance(data, dict):
            return None
        size = len(m.group(1).encode("utf-8"))
        with self._lesson_lock:
            self._lesson_cache[path] = (mtime, data, size)
            self._lesson_bytes += size
            self._lesson_cache.move_to_end(path)
            while self._lesson_cache and (
                    len(self._lesson_cache) > 24 or self._lesson_bytes > 64 * 1024 * 1024):
                _, (_, _, old_size) = self._lesson_cache.popitem(last=False)
                self._lesson_bytes = max(0, self._lesson_bytes - old_size)
        return data

    def _resolve_media(self, course, rel):
        if not isinstance(rel, str) or not rel.strip():
            return ""
        rel = rel.strip()
        if rel.startswith(("http://", "https://", "data:", "//")):
            return ""
        try:
            decoded = urllib.parse.unquote(rel).replace("\\", "/")
        except ValueError:
            return ""
        pages_dir = os.path.join(course["path"], "reader", "pages")
        target = os.path.abspath(os.path.join(pages_dir, decoded))
        course_real = os.path.realpath(course["path"])
        if not os.path.realpath(target).startswith(course_real + os.sep):
            return ""
        if not os.path.isfile(target):
            return ""
        # URL 形如 /<课程名>/...，因此必须以课程数据目录为基准，
        # 否则外置时会算出 ../ 路径，客户端无法访问。
        rel_root = os.path.relpath(target, self.data_dir).replace(os.sep, "/")
        return "/" + urllib.parse.quote(rel_root, safe="/")

    # ------------------------------------------------------------ 进度
    def _progress_all(self, h):
        snap = self.progress.snapshot()
        self.index.ensure()
        out = {}
        for course in self.index.courses():
            lessons = (snap["courses"].get(course["id"]) or {}).get("lessons") or {}
            read = sum(1 for item in lessons.values() if item.get("read"))
            total = self.index.catalog(course)["total"] or course["manifest_lessons"]
            out[course["id"]] = {
                "read": read, "total": total,
                "pct": int(round(read * 100.0 / total)) if total else 0,
            }
        h._send_json({"courses": out})

    def _progress_course(self, h, cid):
        course = self.index.get(cid)
        if course is None:
            raise APIError(404, "not_found", "course not found")
        snap = self.progress.snapshot()["courses"].get(cid) or {}
        h._send_json({
            "lessons": snap.get("lessons") or {},
            "last": snap.get("last"),
        })

    def _progress_update(self, h):
        data = self._read_json(h)
        cid = str(data.get("course") or "")
        if not ID_RE.match(cid) or self.index.get(cid) is None:
            raise APIError(400, "bad_request", "unknown course")
        try:
            lesson = int(data.get("lesson"))
        except (TypeError, ValueError):
            raise APIError(400, "bad_request", "lesson index required")
        course = self.index.get(cid)
        if not os.path.isfile(CourseIndex.page_path(course, lesson)):
            raise APIError(400, "bad_request", "lesson not found")
        pct = _clamp_float(data.get("pct"), 0.0, 100.0)
        pos = _clamp_float(data["pos"], 0.0, 86400 * 24, 0.0) if "pos" in data else None
        dur = _clamp_float(data["dur"], 0.0, 86400 * 24, 0.0) if "dur" in data else None
        audio_pos = (_clamp_float(data["audio_pos"], 0.0, AUDIO_MAX_MS, 0.0)
                     if "audio_pos" in data else None)
        audio_dur = (_clamp_float(data["audio_dur"], 0.0, AUDIO_MAX_MS, 0.0)
                     if "audio_dur" in data else None)
        read = bool(data.get("read"))
        listen_sec = _clamp_int(data.get("listen_sec"), 0, 300, 0)
        result = self.progress.update(cid, lesson, pct, pos, dur, read, listen_sec,
                                      audio_pos, audio_dur)
        h._send_json({"ok": True, **result})

    # ------------------------------------------------------------ 统计 / 搜索
    def _stats(self, h):
        snap = self.progress.snapshot()
        self.index.ensure()
        courses = self.index.courses()
        by_id = {course["id"]: course for course in courses}
        catalogs = {course["id"]: self.index.catalog(course) for course in courses}
        lessons_read = lessons_total = 0
        started = 0
        continue_rows = []
        recent = []
        for cid, course in by_id.items():
            catalog = catalogs[cid]
            total = catalog["total"] or course["manifest_lessons"]
            lessons_total += total
            entry = snap["courses"].get(cid) or {}
            lessons = entry.get("lessons") or {}
            read = sum(1 for item in lessons.values() if item.get("read"))
            lessons_read += read
            if read or lessons:
                started += 1
            last = entry.get("last")
            if last:
                label = self._label(catalog, last.get("lesson"))
                rec = lessons.get(str(last.get("lesson"))) or {}
                continue_rows.append({
                    "id": cid, "name": course["name"],
                    "cover_url": course["cover_url"],
                    "lesson": last.get("lesson"),
                    "title": label,
                    "pct": round(float(rec.get("pct") or 0), 1),
                    "at": last.get("at", 0),
                })
            for key, rec in lessons.items():
                touched = int(rec.get("touched") or 0)
                if touched <= 0:
                    continue
                try:
                    idx = int(key)
                except ValueError:
                    continue
                recent.append({
                    "id": cid, "name": course["name"],
                    "cover_url": course["cover_url"],
                    "lesson": idx,
                    "title": self._label(catalog, idx),
                    "pct": round(float(rec.get("pct") or 0), 1),
                    "read": bool(rec.get("read")),
                    "at": touched,
                })
        continue_rows.sort(key=lambda row: -row["at"])
        recent.sort(key=lambda row: -row["at"])
        daily = snap.get("daily") or {}
        seconds = sum(int(v.get("seconds") or 0) for v in daily.values())
        days_read = sum(1 for v in daily.values() if int(v.get("read") or 0) > 0)
        h._send_json({
            "lessons_read": lessons_read,
            "lessons_total": lessons_total,
            "minutes": seconds // 60,
            "courses_started": started,
            "courses_total": len(courses),
            "days_read": days_read,
            "streak": self._streak(daily),
            "continue": continue_rows[:5],
            "recent": recent[:10],
        })

    @staticmethod
    def _streak(daily):
        active = {day for day, v in daily.items()
                  if int(v.get("read") or 0) > 0 or int(v.get("seconds") or 0) > 0}
        today = date.today()
        start = today if today.isoformat() in active else today - timedelta(days=1)
        streak = 0
        while start.isoformat() in active:
            streak += 1
            start -= timedelta(days=1)
        return streak

    def _search(self, h, qs):
        query = (qs.get("q", [""])[0] or "").strip()
        if not query:
            h._send_json({"courses": [], "lessons": []})
            return
        if len(query) > 64:
            raise APIError(400, "bad_request", "query too long")
        needle = query.lower()
        courses_out, lessons_out = [], []
        snap = self.progress.snapshot()["courses"]
        for course in self.index.courses():
            if needle in course["name"].lower() \
                    or needle in course["author"].lower() \
                    or needle in course["identity"].lower():
                # 与 /api/courses 同一套字段，客户端才能显示搜索结果的真实进度
                courses_out.append(self._course_row(course, snap))
            catalog = self.index.catalog(course)
            for item in catalog["items"]:
                if needle in item["label"].lower():
                    lessons_out.append({
                        "course": course["id"], "course_name": course["name"],
                        "idx": item["idx"], "label": item["label"],
                        "chapter": item["chapter"], "date": item["date"],
                        "dur": item["dur"],
                    })
                    if len(lessons_out) >= 50:
                        break
            if len(lessons_out) >= 50:
                break
        h._send_json({"courses": courses_out[:20], "lessons": lessons_out[:50]})
