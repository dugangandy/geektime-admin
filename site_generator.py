#!/usr/bin/env python3
"""站点首页生成与「首次点击按需构建阅读器」调度（纯标准库）。

serve.py 只负责路由与鉴权，数据、缓存、构建都放在这里：

- ``SiteGenerator``：把本地课程子目录与 115 门清单合并成首页数据，按 mtime 缓存，
  并在缓存失效时节流落盘一份静态 index.html 供 ``file://`` 双击使用。
- ``BuildQueue``：首次点击「待生成」课程时后台构建该门 reader；单锁串行、幂等去重、
  失败可重试、超时可控，构建完成后回调让首页缓存失效。
"""

import importlib.util
import json
import logging
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

log = logging.getLogger("gk.site")

_SKIP_PREFIX = ("_", ".")
_CAT_KEY_RE = re.compile(r"[\s\-_—–·・.、,，:：|丨｜()（）／/\\]+")


def _cat_key(name):
    return _CAT_KEY_RE.sub("", name).lower()


def load_catalog(root):
    """按路径加载 ``template/nav/build_catalog.py``（非包目录，不用 import 语句）。"""
    path = Path(root) / "template" / "nav" / "build_catalog.py"
    spec = importlib.util.spec_from_file_location("gk_build_catalog", path)
    if spec is None or spec.loader is None:
        raise ImportError("无法加载 %s" % path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class SiteGenerator:
    """首页数据 + 渲染 + 静态副本落盘。"""

    def __init__(self, root, manifest_path=None, title=None, color="#f66121",
                 static_name="index.html", static_min_interval=30.0,
                 data_dir=None):
        self.root = Path(root).resolve()
        # 课程一级目录可以与模板/清单/封面分开放置；None 表示沿用旧布局。
        self.data_dir = Path(data_dir).resolve() if data_dir else self.root
        self.catalog = load_catalog(self.root)
        self.manifest_path = (Path(manifest_path).resolve() if manifest_path
                              else self.root / "template" / "nav" / "course_manifest.json")
        self.title = title or ("%s · 课程总目录" % self.root.name)
        self.color = color
        self.static_name = static_name
        self.static_min_interval = float(static_min_interval)
        self.available = (self.root / "template" / "nav" / "catalog_template.html").is_file()

        self._lock = threading.Lock()
        self._key = None
        self._courses = []
        self._dirty = True
        self._static_key = None
        self._last_static = 0.0

    # ---------------------------------------------------------------- 数据
    def _signature(self):
        try:
            names = tuple(sorted(p.name for p in self.data_dir.iterdir() if p.is_dir()))
        except OSError:
            names = ()
        stamps = []
        for f in (self.manifest_path, self.root / "catalog.html"):
            try:
                stamps.append(f.stat().st_mtime_ns)
            except OSError:
                stamps.append(0)
        metas = []
        for n in names:
            try:
                metas.append((self.data_dir / n / "course.json").stat().st_mtime_ns)
            except OSError:
                metas.append(0)
        return names, tuple(stamps), tuple(metas)

    def courses(self, build_states=None):
        """课程记录；带 build_states（构建中/失败）时跳过缓存，保证状态实时。"""
        key = self._signature()
        with self._lock:
            if not build_states and key == self._key:
                return list(self._courses)
        data = self.catalog.collect_courses(
            self.root, self.manifest_path, "html", build_states,
            course_root=self.data_dir)
        with self._lock:
            self._key, self._courses = key, data
        return list(data)

    def invalidate(self):
        with self._lock:
            self._key = None
            self._dirty = True

    # ---------------------------------------------------------------- 渲染
    def render(self, build_states=None, offline=False):
        return self.catalog.render_index(self.courses(build_states), self.title,
                                         self.color, offline=offline)

    def home_html(self, build_states=None):
        """动态首页 HTML；顺带节流刷新静态副本。"""
        courses = self.courses(build_states)
        html = self.catalog.render_index(courses, self.title, self.color, offline=False)
        self._refresh_static(courses)
        return html

    def _refresh_static(self, courses):
        now = time.time()
        with self._lock:
            if not self._dirty and self._static_key == self._key:
                return
            if now - self._last_static < self.static_min_interval:
                return
            self._dirty = False
            self._last_static = now
            self._static_key = self._key
        try:
            # 静态副本供 file:// 双击，课程链接要指到外置数据目录；
            # 在线首页的 /<课程名>/... URL 由 serve.py 的双根映射处理，无需前缀。
            prefix = self.catalog.course_href_prefix(self.root, self.data_dir)
            html = self.catalog.render_index(
                courses, self.title, self.color, offline=True,
                course_prefix=prefix)
            self.catalog.write_static_index(self.root, html, self.static_name)
        except Exception as exc:                       # 落盘失败不影响在线首页
            log.warning("静态首页落盘失败：%s", exc)


class BuildQueue:
    """按需构建队列：单锁串行、幂等、状态可查。"""

    def __init__(self, root, build_py=None, jobs=4, timeout=1800, on_done=None,
                 data_dir=None):
        self.root = Path(root).resolve()
        # 课程目录与构建器模板可以分属两处：reader/ 产物写回 data_dir。
        self.data_dir = Path(data_dir).resolve() if data_dir else self.root
        self.build_py = (Path(build_py) if build_py
                         else self.root / "template" / "reader" / "build.py")
        self.jobs = max(1, int(jobs))
        self.timeout = max(60, int(timeout))
        self.on_done = on_done
        self._lock = threading.Lock()
        self._states = {}

    # ---------------------------------------------------------------- 白名单
    def allowed(self):
        """本地一级课程子目录（跳过规则与 build_catalog.scan 保持一致）。"""
        names = set()
        try:
            entries = list(self.data_dir.iterdir())
        except OSError:
            return names
        for p in entries:
            if not p.is_dir():
                continue
            if p.name.startswith(_SKIP_PREFIX) or p.name.endswith("_old") or p.name in ("assets", "template"):
                continue
            names.add(p.name)
        return names

    def resolve(self, name):
        """白名单校验 + 防路径穿越；返回真实目录名或 None。"""
        if not name:
            return None
        name = name.strip()
        if len(name) > 120 or name in (".", ".."):
            return None
        if any(ch in name for ch in ("/", "\\", "\x00", ":")):
            return None
        allowed = self.allowed()
        if name in allowed:
            return name
        key = _cat_key(name)
        for real in allowed:
            if _cat_key(real) == key:
                return real
        return None

    def reader_index(self, name):
        return self.data_dir / name / "reader" / "index.html"

    # ---------------------------------------------------------------- 状态
    def status(self, name):
        real = self.resolve(name)
        if real is None:
            return {"name": name, "state": "invalid",
                    "detail": "课程不在本地目录中", "elapsed": 0}
        with self._lock:
            rec = dict(self._states.get(real) or {})
        state = "ready" if self.reader_index(real).is_file() else (rec.get("state") or "pending")
        return {
            "name": real,
            "state": state,
            "detail": rec.get("detail", ""),
            "elapsed": (round(time.time() - rec["started"], 1) if rec.get("started") else 0),
        }

    def request(self, name):
        """幂等触发构建；返回 (真实目录名, 状态)。"""
        real = self.resolve(name)
        if real is None:
            return None, "invalid"
        if self.reader_index(real).is_file():
            return real, "ready"
        with self._lock:
            if (self._states.get(real) or {}).get("state") == "building":
                return real, "building"
            self._states[real] = {"state": "building", "detail": "",
                                  "started": time.time()}
            thread = threading.Thread(target=self._run, args=(real,),
                                      name="gk-build", daemon=True)
        thread.start()
        return real, "building"

    def states(self):
        with self._lock:
            return {n: s.get("state") for n, s in self._states.items()}

    # ---------------------------------------------------------------- 构建
    def _run(self, real):
        started = time.time()
        detail = ""
        ok = False
        try:
            if not self.build_py.is_file():
                raise RuntimeError("找不到构建器：%s" % self.build_py)
            # 课程目录里的 course.json 可覆写阅读器标题（默认用目录名）
            title = real
            meta_file = self.data_dir / real / "course.json"
            if meta_file.is_file():
                try:
                    title = (json.loads(meta_file.read_text(encoding="utf-8")).get("title")
                             or real)
                except (OSError, ValueError):
                    pass
            cmd = [sys.executable, str(self.build_py), str(self.data_dir / real),
                   "--title", title, "--jobs", str(self.jobs)]
            log.info("构建开始：%s", real)
            proc = subprocess.run(cmd, cwd=str(self.build_py.parent),
                                  capture_output=True, timeout=self.timeout)
            stdout = (proc.stdout or b"").decode("utf-8", "replace")
            stderr = (proc.stderr or b"").decode("utf-8", "replace")
            ok = proc.returncode == 0 and self.reader_index(real).is_file()
            if not ok:
                detail = (stderr.strip() or stdout.strip()
                          or "exit %d" % proc.returncode)[-400:]
        except subprocess.TimeoutExpired:
            detail = "构建超时（%d 秒）" % self.timeout
        except Exception as exc:
            detail = "%s: %s" % (type(exc).__name__, exc)

        with self._lock:
            self._states[real] = {"state": "ready" if ok else "failed",
                                  "detail": detail, "started": started}
        log.info("构建%s：%s（%.1fs）%s", "完成" if ok else "失败", real,
                 time.time() - started, detail[:200])
        if ok and self.on_done:
            try:
                self.on_done()
            except Exception:
                log.exception("on_done 回调失败")
