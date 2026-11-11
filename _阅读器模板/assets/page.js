/* ============================================================
   正文页渲染器
   ------------------------------------------------------------
   pages/NNN.html 只是一个数据壳：
     <script type="application/json" id="lesson-data">…</script>
     <script src="../assets/page.js"></script>
   本文件负责把数据渲染成整页 DOM，并接管音频播放器。
   改 UI（结构 / 播放器 / 迷你播放器）只需改这里和 article.css，
   然后跑 sync_ui.py 同步到所有阅读器，无需重新构建课程。
   ============================================================ */
(function () {
  'use strict';

  /* 加载失败时给可见提示，避免整页白屏却没有任何线索 */
  function fail(msg) {
    document.body.innerHTML =
      '<div class="page-fail">正文加载失败：' +
      String(msg).replace(/&/g, '&amp;').replace(/</g, '&lt;') +
      '<br>常见原因是 <code>assets/page.js</code> 没有同步到本课程，' +
      '请在「极客时间」目录跑一次 <code>python _阅读器模板/sync_ui.py</code>，然后刷新。</div>';
  }

  var dataEl = document.getElementById('lesson-data');
  if (!dataEl) {
    fail('页面里找不到 #lesson-data 数据块');
    return;
  }

  var D;
  try {
    D = JSON.parse(dataEl.textContent || '{}');
  } catch (e) {
    fail((e && e.message) || e);
    return;
  }

  /* 数据结构版本：缺失按 v1 兼容；版本不符说明页面是旧模板生成的，给明显提示 */
  var schemaWarn = (D.v && D.v !== 1)
    ? '<div class="page-fail">数据版本不匹配（v=' + D.v + '，当前为 1），请重新生成：' +
      'python _阅读器模板/build.py "课程目录"</div>'
    : '';

  /* ---------------------------------------------------- 图标 */
  var IC_PLAY =
    '<svg viewBox="0 0 24 24" class="ic ic-play"><path d="M8 5v14l11-7z"/></svg>' +
    '<svg viewBox="0 0 24 24" class="ic ic-pause"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></svg>';
  var IC_VOLUME =
    '<svg viewBox="0 0 24 24" class="ic"><path d="M4 9v6h4l5 4V5L8 9H4zm12.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4z"/></svg>';
  var IC_DOWNLOAD =
    '<svg viewBox="0 0 24 24" class="ic"><path d="M12 3v10l3.5-3.5 1.4 1.4L12 16.8 7.1 10.9l1.4-1.4L12 13V3zM5 19h14v2H5z"/></svg>';
  var IC_ARC_BACK =
    '<svg viewBox="0 0 24 24" class="mp-arc" aria-hidden="true"><path d="M12 5V1L7 6l5 5V7c3.3 0 6 2.7 6 6s-2.7 6-6 6-6-2.7-6-6H4c0 4.4 3.6 8 8 8s8-3.6 8-8-3.6-8-8-8z"/></svg>';
  var IC_ARC_FWD =
    '<svg viewBox="0 0 24 24" class="mp-arc" aria-hidden="true"><path d="M12 5V1l5 5-5 5V7c-3.3 0-6 2.7-6 6s2.7 6 6 6 6-2.7 6-6h2c0 4.4-3.6 8-8 8s-8-3.6-8-8 3.6-8 8-8z"/></svg>';
  var IC_CARET =
    '<svg viewBox="0 0 24 24" class="mp-caret" aria-hidden="true"><path d="M7 10l5 5 5-5" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  var IC_DROP =
    '<svg viewBox="0 0 24 24" class="rate-caret" aria-hidden="true"><path d="M7 10l5 5 5-5" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  var hasAudio = !!(D.audio && D.audio !== '#');
  var hasPdf = !!(D.pdf && D.pdf !== '#');
  var duration = D.duration || '--:--';

  /* ---------------------------------------------------- 片段 */
  function playerSection() {
    if (!hasAudio) {
      return '<section class="player audio-ph">\n' +
        '  <div class="player-main"><span class="audio-ph-txt">本讲音频暂未提供，播放器位置已预留</span></div>\n' +
        '</section>';
    }
    return '<section class="player" id="player">\n' +
      '  <div class="player-main">\n' +
      '    <button class="btn-play" id="btnPlay" type="button" aria-label="播放">' + IC_PLAY + '</button>\n' +
      '    <span class="chip" id="chipCur">' + duration + '</span>\n' +
      '    <div class="track" id="track">\n' +
      '      <div class="track-bg"></div>\n' +
      '      <div class="track-loaded" id="loaded"></div>\n' +
      '      <div class="track-play" id="played"><span class="knob"></span></div>\n' +
      '    </div>\n' +
      '    <span class="time" id="timeTxt">00:00 / ' + duration + '</span>\n' +
      '    <div class="rate-wrap" id="rateWrap">\n' +
      '      <button class="rate" id="rate" type="button" title="切换倍速" aria-haspopup="listbox"' +
      ' aria-expanded="false"><span id="rateTxt">1.0x</span>' + IC_DROP + '</button>\n' +
      '      <div class="rate-menu" id="rateMenu" role="listbox" aria-label="播放速度"></div>\n' +
      '    </div>\n' +
      '    <button class="icon-act" id="btnMute" type="button" title="静音">' + IC_VOLUME + '</button>\n' +
      '    <a class="icon-act" href="' + D.audio + '" download title="下载音频">' + IC_DOWNLOAD + '</a>\n' +
      '    <a class="pdf-act"' + (hasPdf ? ' href="' + D.pdf + '"' : ' hidden href="#"') +
      ' target="_blank" rel="noopener" title="打开 PDF">PDF</a>\n' +
      '  </div>\n' +
      '  <div class="player-sub">' + (D.sub || '') + '</div>\n' +
      '  <audio id="audio" preload="metadata"></audio>\n' +
      '</section>';
  }

  function miniHtml() {
    if (!hasAudio) return '';
    return '<div class="mini-player" id="miniPlayer" aria-hidden="true">\n' +
      '  <button class="mp-btn" id="mpBack" type="button" title="后退 15 秒" aria-label="后退 15 秒">' +
      IC_ARC_BACK + '<span class="mp-num">15</span></button>\n' +
      '  <button class="mp-play" id="mpPlay" type="button" aria-label="播放">' + IC_PLAY + '</button>\n' +
      '  <button class="mp-btn" id="mpFwd" type="button" title="前进 15 秒" aria-label="前进 15 秒">' +
      IC_ARC_FWD + '<span class="mp-num">15</span></button>\n' +
      '  <div class="mp-rate-wrap" id="mpRateWrap">\n' +
        '    <button class="mp-rate" id="mpRate" type="button" title="切换倍速" aria-haspopup="listbox"' +
        ' aria-expanded="false"><span id="mpRateTxt">1.0x</span>' + IC_CARET + '</button>\n' +
        '    <div class="rate-menu mp-rate-menu" id="mpRateMenu" role="listbox" aria-label="播放速度"></div>\n' +
        '  </div>\n' +
      '  <div class="mp-track" id="mpTrack" title="点击跳转">\n' +
      '    <div class="mp-played" id="mpPlayed"></div>\n' +
      '  </div>\n' +
      '</div>';
  }

  function navItem(dir, rec, emptyText) {
    var url = rec ? rec.url : 'javascript:void(0)';
    var title = rec ? rec.title : emptyText;
    return '<a class="nav-item ' + dir + (rec ? '' : ' off') + '" href="' + url + '">\n' +
      '    <span class="nav-dir">' + (dir === 'prev' ? '上一篇' : '下一篇') + '</span>\n' +
      '    <span class="nav-title">' + title + '</span>\n' +
      '  </a>';
  }

  /* ---------------------------------------------------- 渲染 */
  document.body.className = 'article-page ' + (D.kind || 'kind-simple');
  document.title = D.title + ' - ' + D.course;
  if (D.style) {
    var styleEl = document.createElement('style');
    styleEl.textContent = D.style;
    document.head.appendChild(styleEl);
  }

  document.body.innerHTML = [
    schemaWarn,
    '<header class="art-head">',
    '  <h1 class="art-title">' + D.title + '</h1>',
    '  <div class="art-meta">' + (D.meta || '') + '</div>',
    '</header>',
    D.cover || '',
    playerSection(),
    '<article class="gk-content" id="content">',
    D.body || '',
    '</article>',
    '<nav class="art-nav">',
    navItem('prev', D.prev, '已经是第一讲了'),
    navItem('next', D.next, '已经是最后一讲了'),
    '</nav>',
    '<div class="art-foot">',
    '  <span>共 ' + D.total + ' 讲 · 第 ' + (D.idx + 1) + ' 讲</span>',
    '  <a href="javascript:void(0)" id="toTop">回到顶部</a>',
    '</div>',
    miniHtml()
  ].join('\n');

  /* ---------------------------------------------------- 与外壳通信 */
  function toParent(msg) {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(msg, '*');
    }
  }

  var idx = D.idx | 0;

  function announce() {
    toParent({
      type: 'ready',
      idx: idx,
      total: D.total,
      title: D.title,
      hasAudio: hasAudio
    });
  }
  // 等外壳脚本挂好监听再上报，避免 ready 消息丢失
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', announce);
  } else {
    announce();
  }

  /* ---------------------------------------------------- 阅读进度上报 */
  var ticking = false;
  function reportProgress() {
    var doc = document.documentElement;
    var max = doc.scrollHeight - doc.clientHeight;
    var y = window.pageYOffset || doc.scrollTop || 0;
    var pct = max > 0 ? Math.round(Math.min(100, (y / max) * 100)) : 100;
    toParent({ type: 'progress', idx: idx, pct: pct });
    updateMini();
    ticking = false;
  }
  window.addEventListener('scroll', function () {
    if (!ticking) {
      ticking = true;
      window.requestAnimationFrame(reportProgress);
    }
  }, { passive: true });

  /* ---------------------------------------------------- 回到顶部 / 键盘翻页 */
  var toTop = document.getElementById('toTop');
  if (toTop) {
    toTop.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.target && /input|textarea/i.test(e.target.tagName)) return;
    if (e.key === 'ArrowLeft') {
      var p = document.querySelector('.art-nav .prev:not(.off)');
      if (p) location.href = p.getAttribute('href');
    } else if (e.key === 'ArrowRight') {
      var n = document.querySelector('.art-nav .next:not(.off)');
      if (n) location.href = n.getAttribute('href');
    }
  });

  /* ---------------------------------------------------- 音频播放器 */
  var audio = document.getElementById('audio');
  var player = document.getElementById('player');
  var mini = document.getElementById('miniPlayer');
  var mpPlay = document.getElementById('mpPlay');
  var mpBack = document.getElementById('mpBack');
  var mpFwd = document.getElementById('mpFwd');
  var mpRate = document.getElementById('mpRate');
  var mpRateTxt = document.getElementById('mpRateTxt');
  var mpTrack = document.getElementById('mpTrack');
  var mpPlayed = document.getElementById('mpPlayed');
  var SKIP_SEC = 15;
  var playerInView = true;
  var total = NaN;

  var btnPlay, track, played, loaded, timeTxt, chipCur, rateBtn, rateTxt, btnMute, subDur;

  function fmt(sec) {
    if (!isFinite(sec) || sec < 0) return '00:00';
    sec = Math.floor(sec);
    var m = Math.floor(sec / 60);
    var s = sec % 60;
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  function paint() {
    if (!audio) return;
    var d = isFinite(audio.duration) ? audio.duration : total;
    var pct = d ? (audio.currentTime / d) * 100 : 0;
    if (played) played.style.width = pct + '%';
    if (mpPlayed) mpPlayed.style.width = pct + '%';
    if (timeTxt) timeTxt.textContent = fmt(audio.currentTime) + ' / ' + fmt(d);
    if (isFinite(d) && chipCur && /--:--/.test(chipCur.textContent)) {
      chipCur.textContent = fmt(d);
    }
    if (loaded && audio.buffered && audio.buffered.length) {
      loaded.style.width = ((audio.buffered.end(audio.buffered.length - 1) / (d || 1)) * 100) + '%';
    }
  }

  function togglePlay() {
    if (!audio) return;
    if (audio.paused) {
      audio.play().catch(function () {});
    } else {
      audio.pause();
    }
  }

  function updateMini() {
    if (!mini) return;
    var y = window.pageYOffset || document.documentElement.scrollTop || 0;
    var on = !playerInView && y > 4;
    mini.classList.toggle('is-on', on);
    mini.setAttribute('aria-hidden', on ? 'false' : 'true');
  }

  function seekBy(delta) {
    var d = isFinite(audio.duration) && audio.duration > 0 ? audio.duration : Infinity;
    audio.currentTime = Math.max(0, Math.min(d, (audio.currentTime || 0) + delta));
    paint();
  }

  /* ---------------------------------------------------- 倍速（下拉选择）
     主播放器与迷你播放器共用一份倍速列表和状态：点按钮展开，点选项切换 */
  var RATES = [0.75, 1, 1.25, 1.5, 2, 2.5, 3];
  var curRate = 1;
  var rateMenus = [];

  function rateLabel(v) {
    var n = Math.round(v * 100);
    return (n % 10 === 0 ? (n / 100).toFixed(1) : (n / 100).toFixed(2)) + 'x';
  }

  var IC_CHECK =
    '<svg viewBox="0 0 24 24" class="rate-check" aria-hidden="true">' +
    '<path d="M20 6L9 17l-5-5" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  function closeRates() {
    for (var i = 0; i < rateMenus.length; i++) {
      rateMenus[i].wrap.classList.remove('is-open');
      rateMenus[i].btn.setAttribute('aria-expanded', 'false');
    }
  }

  function paintRate() {
    var txt = rateLabel(curRate);
    if (rateTxt) rateTxt.textContent = txt;
    if (mpRateTxt) mpRateTxt.textContent = txt;
    for (var i = 0; i < rateMenus.length; i++) {
      var items = rateMenus[i].box.querySelectorAll('.rate-item');
      for (var j = 0; j < items.length; j++) {
        var on = parseFloat(items[j].getAttribute('data-rate')) === curRate;
        items[j].classList.toggle('is-on', on);
        items[j].setAttribute('aria-selected', on ? 'true' : 'false');
      }
    }
  }

  function setRate(v) {
    curRate = v;
    if (audio) audio.playbackRate = v;
    paintRate();
  }

  function buildRate(btn, box, wrap) {
    if (!btn || !box || !wrap) return;
    box.innerHTML = RATES.map(function (v) {
      return '<button class="rate-item" type="button" role="option" data-rate="' + v + '">' +
        '<span>' + rateLabel(v) + '</span>' + IC_CHECK + '</button>';
    }).join('');
    rateMenus.push({ wrap: wrap, box: box, btn: btn });
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = wrap.classList.contains('is-open');
      closeRates();
      if (!open) {
        wrap.classList.add('is-open');
        btn.setAttribute('aria-expanded', 'true');
      }
    });
    box.addEventListener('click', function (e) {
      var item = e.target && e.target.closest ? e.target.closest('.rate-item') : null;
      if (!item) return;
      setRate(parseFloat(item.getAttribute('data-rate')));
      closeRates();
    });
  }

  document.addEventListener('click', closeRates);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeRates();
  });

  window.addEventListener('message', function (e) {
    var d = e.data || {};
    if (d.type === 'scrollTop') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } else if (d.type === 'togglePlay') {
      togglePlay();
    }
  });

  if (!audio || !player || !hasAudio) return;

  audio.src = D.audio;
  btnPlay = document.getElementById('btnPlay');
  track = document.getElementById('track');
  played = document.getElementById('played');
  loaded = document.getElementById('loaded');
  timeTxt = document.getElementById('timeTxt');
  chipCur = document.getElementById('chipCur');
  rateBtn = document.getElementById('rate');
  rateTxt = document.getElementById('rateTxt');
  btnMute = document.getElementById('btnMute');
  subDur = document.getElementById('subDur');

  btnPlay.addEventListener('click', togglePlay);
  audio.addEventListener('play', function () {
    player.classList.add('playing');
    if (mini) mini.classList.add('playing');
  });
  audio.addEventListener('pause', function () {
    player.classList.remove('playing');
    if (mini) mini.classList.remove('playing');
  });
  audio.addEventListener('loadedmetadata', function () {
    total = audio.duration;
    if (subDur && isFinite(audio.duration) && /--:--/.test(subDur.textContent)) {
      subDur.textContent = '时长：' + fmt(audio.duration);
    }
    paint();
  });
  audio.addEventListener('timeupdate', paint);
  audio.addEventListener('progress', paint);
  audio.addEventListener('ended', function () {
    player.classList.remove('playing');
    if (mini) mini.classList.remove('playing');
    audio.currentTime = 0;
    paint();
  });

  track.addEventListener('click', function (e) {
    var rect = track.getBoundingClientRect();
    var ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    var d = isFinite(audio.duration) ? audio.duration : total;
    if (d) {
      audio.currentTime = ratio * d;
      paint();
    }
  });

  buildRate(
    rateBtn,
    document.getElementById('rateMenu'),
    document.getElementById('rateWrap')
  );
  buildRate(
    mpRate,
    document.getElementById('mpRateMenu'),
    document.getElementById('mpRateWrap')
  );
  paintRate();

  btnMute.addEventListener('click', function () {
    audio.muted = !audio.muted;
    btnMute.style.color = audio.muted ? '#fa8919' : '';
  });

  /* ---------------------------------------------------- 悬浮迷你播放器 */
  if (mini && mpPlay && mpBack && mpFwd && mpRate && mpTrack) {
    mpPlay.addEventListener('click', togglePlay);
    mpBack.addEventListener('click', function () { seekBy(-SKIP_SEC); });
    mpFwd.addEventListener('click', function () { seekBy(SKIP_SEC); });
    mpTrack.addEventListener('click', function (e) {
      var rect = mpTrack.getBoundingClientRect();
      var ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
      var d = isFinite(audio.duration) ? audio.duration : total;
      if (d) {
        audio.currentTime = ratio * d;
        paint();
      }
    });

    if ('IntersectionObserver' in window) {
      var io = new IntersectionObserver(function (entries) {
        playerInView = entries[0].isIntersecting;
        updateMini();
      }, { threshold: 0.01 });
      io.observe(player);
    }
  }

  paint();
})();
