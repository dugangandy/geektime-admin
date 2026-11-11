/* ============================================================
   外壳脚本：目录渲染 / 路由 / 阅读进度 / 搜索 / 工具条
   ============================================================ */
(function () {
  'use strict';

  var COURSE = window.COURSE;
  if (!COURSE) return;

  /* 每门课一份本地状态：已读 / 收藏 / 点赞 / 笔记 / 上次位置互不影响。
     课程标识优先取 reader 的上级目录名（…/<课程>/reader/index.html），
     标题被 --title 改成同名时也不会串。 */
  var COURSE_KEY = (function () {
    var parts = [];
    try {
      parts = decodeURIComponent(location.pathname).split('/');
    } catch (e) {}
    var dir = parts.length >= 3 ? parts[parts.length - 3] : '';
    return (dir || COURSE.title || 'course').replace(/[\\/:*?"<>|]/g, '_');
  })();

  var LS_READ = 'dpm:' + COURSE_KEY + ':read';
  var LS_LAST = 'dpm:' + COURSE_KEY + ':last';
  var LS_MARK = 'dpm:' + COURSE_KEY + ':mark';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  var viewer = $('#viewer');
  var tocEl = $('#toc');
  var sidebar = $('#sidebar');
  var readbar = $('#readbar');
  var toastEl = $('#toast');
  var narrow = window.matchMedia('(max-width: 900px)');

  /* ------------------------------------------------------- 课程信息（壳无占位符，全部数据来自 catalog.js） */
  (function fillCourseInfo() {
    var meta = [COURSE.author, COURSE.role].filter(Boolean).join(' · ');
    document.title = COURSE.title + ' - 离线阅读器';
    if ($('#courseTitle')) $('#courseTitle').textContent = COURSE.title;
    if ($('#courseRole')) $('#courseRole').textContent = meta;
    if ($('#topTitle')) $('#topTitle').textContent = COURSE.title;
    if ($('#statTotal')) $('#statTotal').textContent = COURSE.total;
    if ($('#totalNum')) $('#totalNum').textContent = COURSE.total;
  })();

  /* ------------------------------------------------------- 本地状态 */
  function load(key, def) {
    try {
      var v = localStorage.getItem(key);
      return v ? JSON.parse(v) : def;
    } catch (e) { return def; }
  }
  function save(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  var read = load(LS_READ, {});
  var marks = load(LS_MARK, { fav: {}, like: {}, note: {} });

  /* ------------------------------------------------------- 目录索引 */
  var flat = [];        // 全部讲
  var indexOf = {};     // idx -> {item, chapterIdx, el}
  var chapters = [];

  COURSE.chapters.forEach(function (ch, ci) {
    var items = ch.items.map(function (it) {
      var rec = { it: it, ci: ci };
      flat.push(rec);
      indexOf[it.i] = rec;
      return rec;
    });
    chapters.push({ data: ch, ci: ci, items: items });
  });

  /* ------------------------------------------------------- 渲染目录 */
  function renderToc() {
    var frag = document.createDocumentFragment();

    chapters.forEach(function (ch, ci) {
      var wrap = document.createElement('section');
      wrap.className = 'chapter';
      wrap.setAttribute('data-ci', ci);

      var btn = document.createElement('button');
      btn.className = 'chapter-row';
      btn.type = 'button';
      btn.innerHTML =
        '<span class="ch-num">' + ch.data.items.length + '讲</span>' +
        '<span class="ch-name"></span>' +
        '<span class="ch-ring" data-pct="0%"></span>' +
        '<svg class="ic ch-arrow" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>';
      $('.ch-name', btn).textContent = ch.data.name;
      wrap.appendChild(btn);

      var list = document.createElement('div');
      list.className = 'items';
      ch.items.forEach(function (rec) {
        var a = document.createElement('a');
        a.className = 'item';
        a.href = 'javascript:void(0)';
        a.setAttribute('data-idx', rec.it.i);
        a.setAttribute('data-label', rec.it.label);
        a.innerHTML =
          '<span class="it-label"></span>' +
          '<span class="it-sub"><span class="dur"></span><span class="dt"></span></span>';
        $('.it-label', a).textContent = rec.it.label;
        $('.dur', a).textContent = rec.it.dur ? '时长 ' + rec.it.dur : '';
        $('.dt', a).textContent = rec.it.date || '';
        list.appendChild(a);
      });
      wrap.appendChild(list);

      btn.addEventListener('click', function () { wrap.classList.toggle('open'); });
      frag.appendChild(wrap);
    });

    tocEl.appendChild(frag);
  }

  function chapterEl(ci) { return tocEl.querySelector('.chapter[data-ci="' + ci + '"]'); }

  function refreshProgress() {
    var done = 0;
    chapters.forEach(function (ch) {
      var local = 0;
      ch.items.forEach(function (rec) { if (read[rec.it.i]) local++; });
      done += local;
      var el = chapterEl(ch.ci);
      if (!el) return;
      var pct = ch.items.length ? Math.round((local / ch.items.length) * 100) : 0;
      var ring = $('.ch-ring', el);
      ring.setAttribute('data-pct', pct + '%');
      ring.style.background = pct > 0
        ? 'conic-gradient(var(--orange) ' + (pct * 3.6) + 'deg, #ececec 0deg)'
        : '#ececec';
    });
    $('#readNum').textContent = done;
    $('#totalNum').textContent = flat.length;
    $('#statTotal').textContent = flat.length;
  }

  function markItemDone(a) {
    if (!a) return;
    a.classList.add('done');
    var dt = a.querySelector('.dt');
    if (dt) dt.textContent = '✓ 已读';
  }

  function markRead(idx) {
    var a = tocEl.querySelector('.item[data-idx="' + idx + '"]');
    if (read[idx]) { markItemDone(a); return; }
    read[idx] = 1;
    save(LS_READ, read);
    markItemDone(a);
    refreshProgress();
  }

  function applyReadClass() {
    $$('.item', tocEl).forEach(function (a) {
      if (read[a.getAttribute('data-idx')]) markItemDone(a);
    });
  }

  /* ------------------------------------------------------- 路由 */
  var current = -1;

  function idxFromHash() {
    var m = /#\/p\/(\d+)/.exec(location.hash);
    return m ? parseInt(m[1], 10) : null;
  }

  /* file:// 下 history.replaceState 会因 origin 为 null 抛异常，需降级 */
  function setHash(idx, replace) {
    var h = '#/p/' + idx;
    if (replace) {
      try { history.replaceState(null, '', h); return; } catch (e) { /* fallthrough */ }
    }
    if (location.hash !== h) location.hash = h;
  }

  function go(idx, replaceHash) {
    if (idx == null || idx < 0 || idx >= flat.length) return;
    current = idx;
    var url = 'pages/' + String(idx).padStart(3, '0') + '.html';
    var iframeSrc = viewer.getAttribute('src') || '';
    if (iframeSrc.indexOf(url) === -1) {
      viewer.setAttribute('src', url);
    }
    save(LS_LAST, idx);
    setHash(idx, replaceHash);
    highlight(idx);
    if (narrow.matches) document.body.classList.remove('side-shown');
  }

  function highlight(idx) {
    $$('.item', tocEl).forEach(function (a) {
      a.classList.toggle('active', a.getAttribute('data-idx') === String(idx));
    });
    var rec = indexOf[idx];
    if (rec) {
      var el = chapterEl(rec.ci);
      if (el && !el.classList.contains('open')) el.classList.add('open');
      var a = tocEl.querySelector('.item[data-idx="' + idx + '"]');
      if (a && a.scrollIntoView) {
        var r = a.getBoundingClientRect();
        var sr = sidebar.getBoundingClientRect();
        if (r.top < sr.top + 60 || r.bottom > sr.bottom - 20) {
          a.scrollIntoView({ block: 'center' });
        }
      }
      var item = rec.it;
      document.title = item.label + ' - ' + COURSE.title;
    }
    readbar.style.width = '0%';
    syncRail();
  }

  tocEl.addEventListener('click', function (e) {
    var a = e.target.closest ? e.target.closest('.item') : null;
    if (!a) return;
    go(parseInt(a.getAttribute('data-idx'), 10), false);
  });

  window.addEventListener('hashchange', function () {
    var idx = idxFromHash();
    if (idx != null && idx !== current) go(idx, true);
  });

  /* ------------------------------------------------------- 与正文页通信 */
  window.addEventListener('message', function (e) {
    var d = e.data || {};
    if (d.type === 'ready') {
      current = d.idx;
      save(LS_LAST, d.idx);
      if (idxFromHash() !== d.idx) setHash(d.idx, true);
      highlight(d.idx);
    } else if (d.type === 'progress') {
      if (d.idx === current) readbar.style.width = d.pct + '%';
      if (d.pct >= 88) markRead(d.idx);
    }
  });

  /* ------------------------------------------------------- 搜索 */
  var searchInput = $('#search');
  var noResult = null;

  function doSearch(q) {
    q = (q || '').trim().toLowerCase();
    if (!q) {
      $$('.chapter', tocEl).forEach(function (c) { c.classList.remove('hidden'); });
      $$('.item', tocEl).forEach(function (a) { a.classList.remove('hidden'); });
      if (noResult) { noResult.remove(); noResult = null; }
      return;
    }
    var hits = 0;
    chapters.forEach(function (ch) {
      var el = chapterEl(ch.ci);
      var local = 0;
      ch.items.forEach(function (rec) {
        var a = tocEl.querySelector('.item[data-idx="' + rec.it.i + '"]');
        var ok = rec.it.label.toLowerCase().indexOf(q) >= 0;
        if (ok) { a.classList.remove('hidden'); local++; } else { a.classList.add('hidden'); }
      });
      hits += local;
      if (local > 0) { el.classList.remove('hidden'); el.classList.add('open'); }
      else el.classList.add('hidden');
    });
    if (hits === 0) {
      if (!noResult) {
        noResult = document.createElement('div');
        noResult.className = 'no-result';
        noResult.textContent = '没有匹配的讲次';
        tocEl.appendChild(noResult);
      }
    } else if (noResult) {
      noResult.remove();
      noResult = null;
    }
  }

  searchInput.addEventListener('input', function () { doSearch(this.value); });
  searchInput.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { this.value = ''; doSearch(''); }
  });

  /* ------------------------------------------------------- 工具条 */
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { toastEl.classList.remove('show'); }, 1800);
  }

  function syncRail() {
    var i = current;
    $$('.rail-btn').forEach(function (b) {
      var act = b.getAttribute('data-act');
      b.classList.toggle('on', !!(marks[act] && marks[act][i]));
    });
    var favCount = Object.keys(marks.fav || {}).length;
    var badge = $('#badgeFav');
    if (favCount) { badge.hidden = false; badge.textContent = favCount; }
    else badge.hidden = true;
  }

  $$('.rail-btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var act = btn.getAttribute('data-act');
      if (act === 'top') {
        if (viewer.contentWindow) {
          viewer.contentWindow.postMessage({ type: 'scrollTop' }, '*');
        }
        return;
      }
      if (act === 'share') {
        var url = location.href.split('#')[0] + '#/p/' + current;
        var ok = false;
        try {
          var ta = document.createElement('textarea');
          ta.value = url;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          ok = document.execCommand('copy');
          document.body.removeChild(ta);
        } catch (e) { ok = false; }
        toast(ok ? '链接已复制，可直接分享本讲' : '复制失败，请手动复制地址栏链接');
        return;
      }
      if (act === 'info') {
        toast('离线阅读器：目录 + 正文 + 音频均取自本地保存的课程文件');
        return;
      }
      marks[act] = marks[act] || {};
      if (marks[act][current]) delete marks[act][current];
      else marks[act][current] = 1;
      save(LS_MARK, marks);
      syncRail();
      toast({ fav: '收藏', like: '点赞', note: '笔记' }[act] + (marks[act][current] ? '成功' : '已取消'));
    });
  });

  $('#btnDetail').addEventListener('click', function () {
    toast(COURSE.title + ' · ' + COURSE.author + ' · 共 ' + flat.length + ' 讲');
  });

  $('#btnSound').addEventListener('click', function () {
    if (viewer.contentWindow) viewer.contentWindow.postMessage({ type: 'togglePlay' }, '*');
  });

  /* ------------------------------------------------------- 侧栏开关 */
  function toggleSide() {
    if (narrow.matches) {
      document.body.classList.toggle('side-shown');
      document.body.classList.remove('side-hidden');
    } else {
      document.body.classList.toggle('side-hidden');
    }
  }
  narrow.addEventListener('change', function () {
    document.body.classList.remove('side-hidden', 'side-shown');
  });
  $('#btnSide').addEventListener('click', toggleSide);
  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') {
      e.preventDefault();
      toggleSide();
    }
  });

  /* ------------------------------------------------------- 启动 */
  renderToc();
  applyReadClass();
  refreshProgress();

  var start = idxFromHash();
  if (start == null) {
    var last = load(LS_LAST, 0);
    start = (typeof last === 'number' && last < flat.length) ? last : 0;
  }
  go(start, true);
})();
