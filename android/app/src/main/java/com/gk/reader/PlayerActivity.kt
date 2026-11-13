package com.gk.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 全屏播放页：封面 / 讲次标题 / 倍速与定时 / 进度 / 播放控制 / 目录·收藏·讲义。
 * 目录走全屏弹层（CourseSheet），和详情页共用同一套渲染。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private val api get() = (application as GkApp).api
    private lateinit var prefs: Prefs
    private lateinit var cover: CoverView
    private lateinit var titleText: TextView
    private lateinit var bylineText: TextView
    private lateinit var seek: SlidingSeek
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var toggle: ImageView
    private lateinit var speedLabel: TextView
    private lateinit var sleepLabel: TextView
    private lateinit var nextRow: TextView
    private lateinit var favAction: LinearLayout
    private lateinit var favIcon: ImageView
    private lateinit var emptyBox: LinearLayout
    private lateinit var mainBox: LinearLayout

    private var dragging = false
    private var observer: ((PlayState) -> Unit)? = null
    private var lastCover = ""
    private val detailCache = HashMap<String, CourseDetail>()
    /** 拉过一次就不再自动重试，否则播放时每秒都会打一次失败的请求。 */
    private val detailTried = HashSet<String>()
    private var loadingDetail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as GkApp).prefs
        PlayerController.ensure(this)
        buildUi()
        observer = PlayerController.observe { render(it) }
        render(PlayerController.state())
    }

    override fun onDestroy() {
        observer?.let { PlayerController.unobserve(it) }
        observer = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 布局
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            setPadding(dp(20), 0, dp(20), dp(10))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        top.addView(Ui.icon(this, R.drawable.ic_chevron_down, c(R.color.text), 26, "收起") { finish() })
        top.addView(Ui.spacer(this))
        top.addView(Ui.icon(this, R.drawable.ic_queue, c(R.color.text), 22, "课程目录") { openSheet() })
        root.addView(top)

        mainBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        mainBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.32f)
        })

        titleText = Ui.text(this, "正在播放", 20f, c(R.color.text), true, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        mainBox.addView(titleText)

        bylineText = Ui.text(this, "", 12f, c(R.color.text_dim), false, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        mainBox.addView(bylineText)

        // 封面同时受宽度和高度约束：矮屏上宁可缩小封面，也不能把控制按钮挤出屏幕
        // 减去的是标题/倍速/进度/控制条/底部入口这些固定高度约 420dp + 80dp 留白
        val metrics = resources.displayMetrics
        val screenH = metrics.heightPixels / metrics.density
        val sideDp = minOf(
            276,
            (metrics.widthPixels / metrics.density - 64).toInt(),
            (screenH - 500).toInt()
        ).coerceAtLeast(120)
        cover = CoverView(this, sideDp, sideDp, 10f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sideDp), dp(sideDp)).apply { topMargin = dp(22) }
        }
        mainBox.addView(cover)

        mainBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.4f)
        })

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val speedPill = Ui.pill(this, "1.0x", 0) { showSpeed() }
        speedLabel = speedPill.tag as TextView
        chips.addView(speedPill)
        val sleepPill = Ui.pill(this, "定时", R.drawable.ic_timer) { showSleep() }
        sleepLabel = sleepPill.tag as TextView
        chips.addView(sleepPill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        mainBox.addView(chips)

        seek = SlidingSeek(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
            ).apply { topMargin = dp(10) }
            onSeekingChange = { on ->
                dragging = on
                if (!on) renderTime(PlayerController.state())
            }
            onSeek = { ratio ->
                val dur = PlayerController.state().durationMs
                if (dur > 0) PlayerController.seekTo((dur * ratio).toLong())
            }
        }
        mainBox.addView(seek)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        posText = Ui.text(this, "00:00", 11f, c(R.color.text_dim), false, 1)
        durText = Ui.text(this, "00:00", 11f, c(R.color.text_dim), false, 1)
        times.addView(posText)
        times.addView(Ui.spacer(this))
        times.addView(durText)
        mainBox.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        controls.addView(Ui.icon(this, R.drawable.ic_replay15, c(R.color.text), 28, "后退 15 秒") {
            PlayerController.seekBy(-15_000)
        })
        val playBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                marginStart = dp(44)
                marginEnd = dp(44)
            }
        }
        toggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = Ui.roundBg(this@PlayerActivity, Ui.orange(this@PlayerActivity), 32f)
            isClickable = true
            setOnClickListener { PlayerController.toggle() }
            contentDescription = "播放/暂停"
        }
        playBox.addView(toggle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        controls.addView(playBox)
        controls.addView(Ui.icon(this, R.drawable.ic_forward15, c(R.color.text), 28, "前进 15 秒") {
            PlayerController.seekBy(15_000)
        })
        mainBox.addView(controls)

        nextRow = Ui.text(this, "", 11f, c(R.color.text_dim), false, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener { PlayerController.next() }
        }
        mainBox.addView(nextRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val dirAction = Ui.iconAction(this, R.drawable.ic_queue, "目录", c(R.color.text_dim)) { openSheet() }
        actions.addView(dirAction, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        favAction = Ui.iconAction(this, R.drawable.ic_star_outline, "收藏", c(R.color.text_dim)) { toggleFav() }
        favIcon = favAction.getChildAt(0) as ImageView
        actions.addView(favAction, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pdfAction = Ui.iconAction(this, R.drawable.ic_pdf, "讲义", c(R.color.text_dim)) { openHandout() }
        actions.addView(pdfAction, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mainBox.addView(actions)
        root.addView(mainBox)

        emptyBox = Ui.emptyState(this, "还没有播放内容", "去课程库") {
            MainActivity.start(this)
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(emptyBox)
        setContentView(root)
    }

    // ------------------------------------------------------------ 渲染
    private fun render(state: PlayState) {
        if (!state.hasItem) {
            mainBox.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
            return
        }
        mainBox.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        titleText.text = state.title.ifEmpty { "正在播放" }
        bylineText.text = when {
            state.error != null -> "播放失败，检查网络或登录状态"
            state.buffering -> "缓冲中…   —— ${state.course} ——"
            state.course.isEmpty() -> ""
            else -> "—— ${state.course} ——"
        }
        bylineText.setTextColor(c(if (state.error != null) R.color.red else R.color.text_dim))
        toggle.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = if (state.playing) "暂停" else "播放"
        speedLabel.text = formatSpeed(state.speed)
        if (state.coverUrl != lastCover) {
            lastCover = state.coverUrl
            cover.load(state.coverUrl, state.course.ifEmpty { state.title })
        }
        renderTime(state)
        sleepLabel.text = PlayerController.sleepLabel() ?: "定时"
        val titles = PlayerController.queueTitles()
        val index = PlayerController.currentIndex()
        nextRow.text = if (index + 1 in titles.indices) "下一讲 · ${titles[index + 1]}" else ""
        nextRow.visibility = if (nextRow.text.isEmpty()) View.GONE else View.VISIBLE
        renderFav(state)
        ensureDetail(state.courseId)
    }

    private fun renderTime(state: PlayState) {
        val dur = state.durationMs
        durText.text = if (dur > 0) Ui.formatMs(dur) else "00:00"
        if (!dragging) {
            posText.text = Ui.formatMs(state.positionMs)
            seek.ratio = if (dur > 0) (state.positionMs.toFloat() / dur) else 0f
        } else {
            posText.text = Ui.formatMs((dur * seek.ratio).toLong())
        }
    }

    private fun renderFav(state: PlayState) {
        val on = state.lessonIdx >= 0 && prefs.isFavorite(favKey(state))
        favIcon.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_outline)
        favIcon.setColorFilter(c(if (on) R.color.orange else R.color.text_dim))
        (favAction.tag as? TextView)?.let {
            it.text = if (on) "已收藏" else "收藏"
            it.setTextColor(c(if (on) R.color.orange else R.color.text_dim))
        }
    }

    private fun formatSpeed(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

    // ------------------------------------------------------------ 目录 / 收藏 / 讲义
    private fun openSheet() {
        val state = PlayerController.state()
        if (!state.hasItem) {
            Ui.toast(this, "还没有播放内容")
            return
        }
        CourseSheet.show(this, state.courseId, state.course, state.lessonIdx) { item ->
            playLesson(item)
        }
    }

    private fun ensureDetail(courseId: String) {
        if (courseId.isEmpty() || loadingDetail) return
        if (detailCache.containsKey(courseId) || detailTried.contains(courseId)) return
        detailTried.add(courseId)
        loadingDetail = true
        lifecycleScope.launch {
            runCatching { api.course(courseId) }.onSuccess { detailCache[courseId] = it }
            loadingDetail = false
        }
    }

    private fun playLesson(item: LessonItem) {
        if (!item.hasAudio || item.audioUrl.isEmpty()) {
            Ui.toast(this, "本讲没有音频")
            return
        }
        val state = PlayerController.state()
        val detail = detailCache[state.courseId]
        if (detail != null && detail.id == state.courseId) {
            val queue = detail.lessons.filter { it.hasAudio && it.audioUrl.isNotEmpty() }.map {
                QueueItem(detail.id, detail.name, detail.coverUrl, it.idx, it.label, it.audioUrl)
            }
            val index = queue.indexOfFirst { it.idx == item.idx }
            if (index >= 0) {
                PlayerController.play(this, queue, index)
                return
            }
        }
        // 详情没加载到：等它回来再切，避免点一下没反应
        lifecycleScope.launch {
            val detail = runCatching { api.course(state.courseId) }.getOrNull() ?: return@launch
            detailCache[detail.id] = detail
            val queue = detail.lessons.filter { it.hasAudio && it.audioUrl.isNotEmpty() }.map {
                QueueItem(detail.id, detail.name, detail.coverUrl, it.idx, it.label, it.audioUrl)
            }
            val index = queue.indexOfFirst { it.idx == item.idx }
            if (index >= 0) PlayerController.play(this@PlayerActivity, queue, index)
        }
    }

    private fun favKey(state: PlayState): String = "${state.courseId}|${state.lessonIdx}"

    private fun toggleFav() {
        val state = PlayerController.state()
        if (!state.hasItem || state.lessonIdx < 0) return
        val on = prefs.toggleFavorite(favKey(state))
        renderFav(state)
        Ui.toast(this, if (on) "已收藏本讲" else "已取消收藏")
    }

    /** 讲义 = 这一讲的正文页（HTML），不是 PDF 附件。 */
    private fun openHandout() {
        val state = PlayerController.state()
        if (!state.hasItem || state.lessonIdx < 0) {
            Ui.toast(this, "还没有播放内容")
            return
        }
        ReaderActivity.open(
            this,
            state.courseId,
            state.lessonIdx,
            state.course,
            state.title
        )
    }

    // ------------------------------------------------------------ 弹窗
    private fun showSpeed() {
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = values.map { formatSpeed(it) }.toTypedArray()
        val actual = PlayerController.state().speed
        // 非预设倍速时返回 -1（不勾选），别用 coerceAtLeast(0) 假装选中了 0.75x
        val current = values.indexOfFirst { kotlin.math.abs(it - actual) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PlayerController.setSpeed(this@PlayerActivity, values[which])
                speedLabel.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showSleep() {
        SleepSheet.show(this) { render(PlayerController.state()) }
    }
}
