package com.gk.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_COURSE = "courseId"
        private const val EXTRA_IDX = "idx"
        private const val EXTRA_COURSE_NAME = "courseName"
        private const val EXTRA_TITLE = "title"
        private const val STATE_IDX = "state_idx"
        private const val STATE_POS = "state_pos"

        fun open(ctx: Context, courseId: String, idx: Int, courseName: String = "", title: String = "") {
            ctx.startActivity(
                Intent(ctx, ReaderActivity::class.java)
                    .putExtra(EXTRA_COURSE, courseId)
                    .putExtra(EXTRA_IDX, idx)
                    .putExtra(EXTRA_COURSE_NAME, courseName)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private val api: Api get() = (application as GkApp).api
    private lateinit var prefs: Prefs
    private lateinit var web: ReaderWebView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var progressLine: ProgressLine
    private lateinit var videoBar: LinearLayout
    private lateinit var videoSub: TextView
    private lateinit var audioBar: LinearLayout
    private lateinit var audioCover: CoverView
    private lateinit var audioTitle: TextView
    private lateinit var audioSub: TextView
    private lateinit var audioToggle: android.widget.ImageView
    private lateinit var audioSeek: SlidingSeek
    private var lastAudioCover = ""
    private lateinit var loading: DropLoadingView

    private var courseId = ""
    private var idx = 0
    private var courseName = ""
    private var lesson: LessonDetail? = null
    private var progressMap: Map<Int, LessonProgress> = emptyMap()
    private var savedPos = 0.0
    private var restored = false
    private var pageReady = false
    private var built = false
    /** >=0 时优先于服务端进度：旋转重建或「刷新正文」要求留在原位。 */
    private var restorePos = -1
    private var lastReport = 0L
    private var completedShown = false
    private var observer: ((PlayState) -> Unit)? = null
    private var detailCache: CourseDetail? = null
    /** 每次提交新文档 +1；onPageFinished 记下就绪的那一代，防止旧页滚动报到新讲次上。 */
    private var docGen = 0
    private var readyGen = -1
    /** 最后一次上报必须活过 onDestroy，lifecycleScope 会在 finishing 时把它取消掉。 */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as GkApp).prefs
        courseId = intent.getStringExtra(EXTRA_COURSE).orEmpty()
        idx = intent.getIntExtra(EXTRA_IDX, 0)
        courseName = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
        if (courseId.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            idx = savedInstanceState.getInt(STATE_IDX, idx)
            restorePos = savedInstanceState.getInt(STATE_POS, -1)
        }
        buildUi()
        loadProgress()
        loadLesson(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_IDX, idx)
        if (built) outState.putInt(STATE_POS, web.scrollOffset())
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        web.onResume()
        audioBar.post { renderAudio(PlayerController.state()) }
    }

    override fun onPause() {
        if (built) web.onPause()
        super.onPause()
    }

    override fun onStop() {
        // 只在 onStop 强制上报一次：返回键 + onPause 会重复 POST，且最后位置最关键
        if (built) report(true)
        super.onStop()
    }

    override fun onDestroy() {
        observer?.let { PlayerController.unobserve(it) }
        observer = null
        if (built) web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 布局
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bar.addView(Ui.icon(this, R.drawable.ic_back, c(R.color.text), 24, "返回") {
            report(true)
            finish()
        })
        titleText = Ui.text(this, "阅读", 15f, c(R.color.text), true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(6), 0)
        }
        bar.addView(titleText)
        bar.addView(Ui.text(this, "Aa", 17f, c(R.color.text), true, 1).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            contentDescription = "字号与排版"
            setOnClickListener { showTextDialog() }
        })
        bar.addView(Ui.icon(this, R.drawable.ic_more, c(R.color.text), 22, "更多") { showMore() })
        root.addView(bar)

        progressLine = ProgressLine(this, 2f).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        }
        root.addView(progressLine)

        metaText = Ui.text(this, "", 12f, c(R.color.text_dim), false, 1).apply {
            setPadding(dp(16), dp(4), dp(16), dp(6))
            text = courseName
        }
        root.addView(metaText)

        videoBar = buildVideoBar()
        root.addView(videoBar)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        web = ReaderWebView(this).apply {
            setBackgroundColor(c(R.color.bg))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleUrl(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading.visibility = View.GONE
                    readyGen = docGen
                    pageReady = true
                    tryRestore()
                    updateProgress()
                }
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        loading = DropLoadingView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER }
        }
        frame.addView(loading)
        root.addView(frame)
        web.viewTreeObserver.addOnScrollChangedListener { updateProgress() }

        audioBar = buildAudioBar()
        root.addView(audioBar)
        setContentView(root)

        observer = PlayerController.observe { state -> renderAudio(state) }
        // 提前装好 cookie：setCookie 是异步的，紧贴 loadData 会让首批图片请求裸奔 401
        injectCookies()
        built = true
    }

    /** 视频讲次：正文上方一条「播放视频」入口，点开走原生播放器（不在 WebView 里播）。 */
    private fun buildVideoBar(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = Ui.ripple(this@ReaderActivity, c(R.color.surface_alt), 10f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(4), dp(16), dp(2)) }
            setOnClickListener { openVideo() }
        }
        box.addView(Ui.icon(this, R.drawable.ic_play, Ui.orange(this), 26))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        col.addView(Ui.text(this, "播放视频", 14f, c(R.color.text), true, 1))
        videoSub = Ui.text(this, "", 11f, c(R.color.text_dim), false, 1).apply {
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(videoSub)
        box.addView(col)
        box.addView(Ui.icon(this, R.drawable.ic_chevron_right, c(R.color.text_dim), 20))
        return box
    }

    /** 从上次播放位置续播；位置与播放器共用 audio_pos，不动阅读滚动位置。 */
    private fun openVideo() {
        val data = lesson ?: return
        if (data.videoUrl.isEmpty()) return
        VideoActivity.open(
            this, courseId, data.idx, data.videoUrl, data.title,
            listOf(data.course, data.author).filter { it.isNotBlank() }.joinToString(" · "),
            progressMap[data.idx]?.audioPos ?: 0L,
            data.videoWidth, data.videoHeight
        )
    }

    /**
     * 底栏播放器：与列表页 MiniPlayer 同一套样式（封面 + 标题/副标题 + 播放 + 关闭），
     * 进度条放在播放条上方，手感与全屏播放页一致。
     */
    private fun buildAudioBar(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(c(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        audioSeek = SlidingSeek(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)
            ).apply { marginStart = dp(10); marginEnd = dp(10) }
            onSeek = { ratio ->
                val s = PlayerController.state()
                if (s.hasItem && s.durationMs > 0) {
                    PlayerController.seekTo((s.durationMs * ratio).toLong())
                }
            }
        }
        box.addView(audioSeek)
        box.addView(View(this).apply {
            setBackgroundColor(c(R.color.line))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            minimumHeight = dp(56)
            isClickable = true
            setOnClickListener { openPlayer() }
        }
        audioCover = CoverView(this, 34, 34, 6f)
        row.addView(audioCover)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        audioTitle = Ui.text(this, "讲述", 13f, c(R.color.text), true, 1)
        audioSub = Ui.text(this, "", 11f, c(R.color.text_dim), false, 1)
        col.addView(audioTitle)
        col.addView(audioSub)
        row.addView(col)
        audioToggle = Ui.icon(this, R.drawable.ic_play, c(R.color.text), 22, "播放讲述") { toggleAudio() }
        row.addView(audioToggle)
        row.addView(Ui.icon(this, R.drawable.ic_close, c(R.color.text_dim), 18, "停止播放") {
            PlayerController.stopPlayback()
        })
        box.addView(row)
        return box
    }

    // ------------------------------------------------------------ 数据
    private fun loadProgress() {
        lifecycleScope.launch {
            try {
                val progress = api.progressCourse(courseId)
                withContext(Dispatchers.Main) {
                    progressMap = progress.lessons
                    // 进度可能晚于正文到达，补齐后再尝试恢复，避免savedPos 恒为 0
                    if (!restored && restorePos < 0) {
                        savedPos = progress.lessons[idx]?.pos ?: 0.0
                        tryRestore()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 正文就绪且拿到有效位置后才落位；两者任一未到都等下一次触发。 */
    private fun tryRestore() {
        if (!pageReady || restored || savedPos <= 0) return
        restored = true
        web.scrollTo(0, savedPos.toInt())
    }

    private fun loadLesson(switchIdx: Boolean) {
        val target = idx
        loading.visibility = View.VISIBLE
        restored = false
        pageReady = false
        completedShown = false
        savedPos = if (restorePos >= 0) restorePos.toDouble()
        else progressMap[target]?.pos ?: 0.0
        restorePos = -1
        lifecycleScope.launch {
            try {
                val data = api.lesson(courseId, target)
                // 拼串可达数 MB，必须在 IO 线程做，否则每次加载/改字号都卡主线程
                val html = buildHtml(data)
                val base = if (data.pageUrl.isNotEmpty()) api.absolute(data.pageUrl) else api.baseUrl
                withContext(Dispatchers.Main) {
                    if (target != idx) return@withContext
                    lesson = data
                    titleText.text = "${data.idx} | ${data.title}"
                    metaText.text = listOf(data.course, data.author, data.duration)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    audioBar.visibility = if (data.audioUrl.isNotEmpty()) View.VISIBLE else View.GONE
                    videoBar.visibility = if (data.videoUrl.isNotEmpty()) View.VISIBLE else View.GONE
                    videoSub.text = listOf(data.course, data.duration)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    audioTitle.text = data.title.ifEmpty { "讲述" }
                    audioSub.text = listOf("讲述", data.duration)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    if (data.coverUrl.isNotEmpty() && data.coverUrl != lastAudioCover) {
                        lastAudioCover = data.coverUrl
                        audioCover.load(data.coverUrl, data.course.ifEmpty { data.title })
                    }
                    setAudioIcon(false)
                    docGen++
                    web.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
                }
            } catch (e: AuthExpired) {
                startActivity(
                    Intent(this@ReaderActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading.visibility = View.GONE
                    Ui.toast(this@ReaderActivity, "正文加载失败：${e.message}")
                }
            }
        }
    }

    private fun injectCookies() {
        val cookies = api.cookieHeader().split("; ").filter { it.contains("=") }
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (pair in cookies) manager.setCookie(api.baseUrl, pair)
        manager.flush()
    }

    private fun buildHtml(data: LessonDetail): String {
        val css = assets.open("article.css").bufferedReader().use { it.readText() }
        val dark = isDark()
        val fs = prefs.fontSp
        val lh = when (prefs.lineSpacing) {
            0 -> 1.5
            2 -> 1.95
            else -> 1.75
        }
        val bg = if (dark) "#121212" else "#FFFFFF"
        val fg = if (dark) "#E6E6E6" else "#333333"
        val dim = if (dark) "#9AA0A6" else "#8A8F99"
        val line = if (dark) "#2C2C30" else "#E8E8E8"
        val soft = if (dark) "#1C1C1E" else "#F7F7F9"
        val code = if (dark) "#1E1E1E" else "#F6F7F9"
        val override = """
            html{scroll-behavior:auto;}
            body.article-page{background:$bg;color:$fg;font-size:${fs}px;line-height:$lh;padding:0 16px 32px;}
            .gk-content{max-width:100%;padding-left:0;padding-right:0;margin:0;}
            .kind-simple .gk-content{color:$fg;font-size:${fs}px;line-height:$lh;}
            .kind-simple .gk-content h1,.kind-simple .gk-content h2,
            .kind-simple .gk-content h3,.kind-simple .gk-content h4{color:$fg;}
            .kind-simple .gk-content p{margin:0 0 1.15em;}
            .gk-content a,.kind-simple .gk-content a{color:${if (dark) "#FF7A3D" else "#F66121"};}
            .gk-content img,.gk-content video{max-width:100%;height:auto;${if (dark) "filter:brightness(.9);" else ""}}
            .kind-simple .gk-content blockquote{background:$soft;border-left:3px solid ${if (dark) "#FF7A3D" else "#F66121"};color:$fg;padding:10px 14px;}
            .kind-simple .gk-content pre{background:$code;color:$fg;overflow-x:auto;padding:12px;}
            .kind-simple .gk-content code{background:$code;color:$fg;}
            .kind-simple .gk-content th{background:$soft;color:$fg;}
            .kind-simple .gk-content th,.kind-simple .gk-content td{border-color:$line;}
            .kind-simple .gk-content hr{border-top-color:$line;}
            .kind-simple .gk-content table{display:block;overflow-x:auto;}
            .gk-nav{margin:30px 0 6px;display:flex;flex-direction:column;gap:10px;}
            .gk-nav a{display:block;padding:12px 14px;border:1px solid $line;border-radius:10px;text-decoration:none;color:$fg;}
            .gk-nav .t{display:block;font-size:12px;color:$dim;margin-bottom:4px;}
            .gk-nav .e{color:$dim;font-size:13px;padding:6px 2px;}
        """.trimIndent()

        val nav = StringBuilder("<div class=\"gk-nav\">")
        val prev = data.prev
        val next = data.next
        if (prev != null) {
            nav.append("<a href=\"gk://prev/${prev.idx}\"><span class=\"t\">上一讲</span>")
                .append(escape(prev.title)).append("</a>")
        } else {
            nav.append("<div class=\"e\">已经是第一讲</div>")
        }
        if (next != null) {
            nav.append("<a href=\"gk://next/${next.idx}\"><span class=\"t\">下一讲</span>")
                .append(escape(next.title)).append("</a>")
        } else {
            nav.append("<div class=\"e\">已经是最后一讲</div>")
        }
        nav.append("</div>")

        return """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=3">
            <style>$css
$override</style>
            </head><body class="article-page">
            <div class="kind-simple"><div class="gk-content">${data.bodyHtml}</div></div>
            $nav
            </body></html>
        """.trimIndent()
    }

    private fun escape(value: String): String = TextUtils.htmlEncode(value)

    // ------------------------------------------------------------ 链接
    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("gk://")) {
            val uri = Uri.parse(url)
            val target = uri.lastPathSegment?.toIntOrNull() ?: return true
            report(true)
            idx = target
            loading.visibility = View.VISIBLE
            loadLesson(true)
            return true
        }
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            // mailto:/tel:/intent: 一律吞掉就是死链，交给系统处理
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return true
        }
        val server = Uri.parse(api.baseUrl)
        val sameHost = uri.host == server.host && uri.port == server.port
        val path = uri.path.orEmpty()
        val page = Regex("/pages/(\\d{1,4})\\.html").find(path)
        if (sameHost && page != null) {
            val currentDir = Uri.parse(api.absolute(lesson?.pageUrl.orEmpty()))
                .path.orEmpty().substringBeforeLast('/')
            // 只有同一课程目录下的讲次才能复用当前 courseId
            if (currentDir.isNotEmpty() && currentDir == path.substringBeforeLast('/')) {
                report(true)
                idx = page.groupValues[1].toInt()
                loading.visibility = View.VISIBLE
                loadLesson(true)
            } else {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }
        if (sameHost) {
            val current = Uri.parse(web.url.orEmpty())
            // 同文档锚点交回 WebView 自己滚动，否则目录跳转全部失效
            if (current.path == path && uri.fragment != null) return false
            // 站内其它页面（目录/PDF 等）交给浏览器会丢登录态，直接忽略
            return true
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        return true
    }

    // ------------------------------------------------------------ 进度
    /* 用 WebView 的滚动三件套：range/extent/offset 单位自洽，
       不像 contentHeight(CSS px) 与 height/scrollY(设备 px) 混算，也不依赖已废弃的 scale。 */
    private fun maxScroll(): Int =
        (web.scrollRange() - web.scrollExtent()).coerceAtLeast(0)

    private fun currentPct(): Double {
        val max = maxScroll()
        // 内容不足一屏时无法从滚动推断读完，交给「标记已学」显式处理，避免零阅读即判已学完
        if (max <= dp(8)) return 0.0
        return (web.scrollOffset() * 100.0 / max).coerceIn(0.0, 100.0)
    }

    private fun updateProgress() {
        if (readyGen != docGen) return
        val pct = currentPct().toFloat()
        progressLine.pct = pct
        if (pct >= 88f && !completedShown && lesson != null) {
            completedShown = true
            Ui.toast(this, "已学完本讲")
        }
        report(false)
    }

    private fun report(force: Boolean, markRead: Boolean = false) {
        val data = lesson ?: return
        // 新文档尚未提交完成时，滚动位置还属于上一讲，不能报到 data.idx 上
        if (!markRead && readyGen != docGen) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReport < 3000) return
        lastReport = now
        val max = maxScroll()
        val pos = web.scrollOffset().toDouble()
        val pct = currentPct()
        val read = markRead || pct >= 88.0
        reportScope.launch {
            runCatching {
                api.postProgress(courseId, data.idx, pct, pos, max.toDouble(), read, 0)
            }
        }
    }

    // ------------------------------------------------------------ 音频
    private fun toggleAudio() {
        val data = lesson ?: return
        val state = PlayerController.state()
        if (state.hasItem && state.courseId == courseId && state.lessonIdx == data.idx) {
            PlayerController.toggle()
        } else {
            playQueue()
        }
    }

    private fun openPlayer() {
        val data = lesson ?: return
        val state = PlayerController.state()
        if (!state.hasItem || state.courseId != courseId || state.lessonIdx != data.idx) {
            playQueue()
        } else {
            PlayerActivity.open(this)
        }
    }

    private fun playQueue() {
        val data = lesson ?: return
        if (data.audioUrl.isEmpty()) return
        lifecycleScope.launch {
            try {
                val detail = detailCache ?: api.course(courseId).also { detailCache = it }
                val queue = detail.lessons.filter { it.hasAudio && it.audioUrl.isNotEmpty() }.map {
                    QueueItem(courseId, detail.name, detail.coverUrl, it.idx, it.label, it.audioUrl)
                }
                val index = queue.indexOfFirst { it.idx == data.idx }.coerceAtLeast(0)
                withContext(Dispatchers.Main) {
                    PlayerController.play(this@ReaderActivity, queue, index)
                }
            } catch (e: AuthExpired) {
                startActivity(
                    Intent(this@ReaderActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Ui.toast(this@ReaderActivity, "无法开始播放：${e.message}") }
            }
        }
    }

    private fun setAudioIcon(playing: Boolean) {
        audioToggle.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        audioToggle.contentDescription = if (playing) "暂停讲述" else "播放讲述"
        audioToggle.setColorFilter(c(R.color.text))
    }

    private fun renderAudio(state: PlayState) {
        val data = lesson ?: return
        val mine = state.hasItem && state.courseId == courseId && state.lessonIdx == data.idx
        if (mine) {
            setAudioIcon(state.playing)
            val dur = if (state.durationMs > 0) state.durationMs else durationMsOf(data)
            audioTitle.text = state.title.ifEmpty { data.title }
            audioSub.text = when {
                state.buffering -> "缓冲中… · ${state.course}"
                else -> buildString {
                    append(state.course.ifEmpty { data.course })
                    if (dur > 0) {
                        append("  ").append(Ui.formatMs(state.positionMs))
                            .append(" / ").append(Ui.formatMs(dur))
                    }
                }
            }
            audioSeek.ratio = if (dur > 0) state.positionMs.toFloat() / dur else 0f
            if (state.coverUrl.isNotEmpty() && state.coverUrl != lastAudioCover) {
                lastAudioCover = state.coverUrl
                audioCover.load(state.coverUrl, state.course.ifEmpty { data.title })
            }
        } else {
            setAudioIcon(false)
            audioSeek.ratio = 0f
            audioTitle.text = data.title.ifEmpty { "讲述" }
            audioSub.text = listOf("讲述", data.duration)
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    private fun durationMsOf(data: LessonDetail): Long {
        val parts = data.duration.split(":")
        return when (parts.size) {
            2 -> ((parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)) * 1000L
            3 -> ((parts[0].toIntOrNull() ?: 0) * 3600 +
                (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)) * 1000L
            else -> 0L
        }
    }

    // ------------------------------------------------------------ 菜单
    private fun showTextDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val fontLabel = Ui.text(this, "正文字号 ${prefs.fontSp}sp", 13f, c(R.color.text_dim), false, 1)
        box.addView(fontLabel)
        var pendingFont = prefs.fontSp
        box.addView(SeekBar(this).apply {
            max = 7
            progress = (prefs.fontSp - 15).coerceIn(0, 7)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pendingFont = progress + 15
                    fontLabel.text = "正文字号 ${pendingFont}sp"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        box.addView(Ui.text(this, "行距", 13f, c(R.color.text_dim), false, 1).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        val spacingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var pendingSpacing = prefs.lineSpacing
        val spacingLabels = arrayOf("紧凑", "标准", "宽松")
        val spacingChips = ArrayList<TextView>()
        for (i in 0 until 3) {
            val chip = Ui.chip(this, spacingLabels[i], i == pendingSpacing).apply {
                setOnClickListener {
                    pendingSpacing = i
                    for (j in 0 until 3) {
                        val on = j == i
                        spacingChips[j].setTextColor(if (on) Color.WHITE else c(R.color.text))
                        spacingChips[j].background = if (on) Ui.roundBg(this@ReaderActivity, Ui.orange(this@ReaderActivity), 16f)
                        else Ui.roundBg(this@ReaderActivity, c(R.color.surface_alt), 16f)
                    }
                }
            }
            spacingChips.add(chip)
            spacingRow.addView(chip)
        }
        box.addView(spacingRow)

        AlertDialog.Builder(this)
            .setTitle("排版")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val fontChanged = pendingFont != prefs.fontSp
                val spacingChanged = pendingSpacing != prefs.lineSpacing
                prefs.fontSp = pendingFont
                prefs.lineSpacing = pendingSpacing
                if (fontChanged || spacingChanged) {
                    val keep = web.scrollOffset()
                    val data = lesson ?: return@setPositiveButton
                    lifecycleScope.launch {
                        val html = buildHtml(data)
                        val base = if (data.pageUrl.isNotEmpty()) api.absolute(data.pageUrl)
                        else api.baseUrl
                        withContext(Dispatchers.Main) {
                            restored = false
                            pageReady = false
                            savedPos = keep.toDouble()
                            docGen++
                            web.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
                        }
                    }
                }
            }
            .show()
    }

    private fun showMore() {
        val data = lesson
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (data != null && data.pdfUrl.isNotEmpty()) {
            labels.add("PDF 原稿")
            actions.add { Pdf.open(this, data.pdfUrl) }
        }
        labels.add("标记为已学完")
        actions.add {
            report(true, markRead = true)
            progressLine.pct = 100f
            Ui.toast(this, "已标记为学完")
        }
        labels.add("刷新正文")
        actions.add {
            restorePos = web.scrollOffset()
            loadLesson(true)
        }
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which]()
            }
            .show()
    }
}

/** View 的滚动三件套是 protected，但只有它们单位自洽，能算出正确的阅读百分比。 */
class ReaderWebView(context: Context) : WebView(context) {
    fun scrollRange(): Int = computeVerticalScrollRange()
    fun scrollExtent(): Int = computeVerticalScrollExtent()
    fun scrollOffset(): Int = computeVerticalScrollOffset()
}
