package com.gk.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val STATE_EXPANDED = "state_expanded"
        private const val STATE_SCROLL = "state_scroll"

        fun open(ctx: Context, courseId: String, courseName: String) {
            ctx.startActivity(
                Intent(ctx, DetailActivity::class.java)
                    .putExtra(EXTRA_ID, courseId)
                    .putExtra(EXTRA_NAME, courseName)
            )
        }
    }

    private val api: Api get() = (application as GkApp).api
    private lateinit var prefs: Prefs
    private lateinit var topTitle: TextView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var mini: MiniPlayer
    private lateinit var progressLine: ProgressLine
    private lateinit var progressText: TextView
    private lateinit var continueButton: TextView

    private var courseId = ""
    private var courseName = ""
    private var detail: CourseDetail? = null
    /** 按章节序号记展开状态：同名章节（如「结束语」）用名字做键会互相串。 */
    private val expanded = HashSet<Int>()
    private var firstResume = true
    private var built = false
    private var restoreScroll = -1
    private val rowMarks = HashMap<Int, Pair<LinearLayout, View>>()
    private var playingIdx = -1
    private var playObserver: ((PlayState) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as GkApp).prefs
        courseId = intent.getStringExtra(EXTRA_ID).orEmpty()
        courseName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (courseId.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            savedInstanceState.getIntegerArrayList(STATE_EXPANDED)?.let { expanded.addAll(it) }
            restoreScroll = savedInstanceState.getInt(STATE_SCROLL, -1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
        }
        val bar = Ui.topBar(this, courseName.ifEmpty { "课程详情" }) { finish() }
        topTitle = bar.tag as TextView
        root.addView(bar)
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        mini = MiniPlayer(this).apply { attach() }
        root.addView(mini, MiniPlayer.layoutParams(this))
        setContentView(root)
        built = true
        playObserver = PlayerController.observe { syncPlaying(it) }

        showLoading()
        load(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(STATE_EXPANDED, ArrayList(expanded))
        if (built) outState.putInt(STATE_SCROLL, scroll.scrollY)
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        mini.refresh()
        if (firstResume) {
            firstResume = false
            return
        }
        load(true)
    }

    private fun showLoading() {
        body.removeAllViews()
        body.addView(Ui.emptyState(this, "正在加载课程…"))
    }

    private fun load(silent: Boolean) {
        if (!silent) showLoading()
        lifecycleScope.launch {
            try {
                val data = api.course(courseId)
                withContext(Dispatchers.Main) {
                    detail = data
                    topTitle.text = data.name
                    render(data)
                }
            } catch (e: AuthExpired) {
                startActivity(
                    Intent(this@DetailActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (detail == null) {
                        body.removeAllViews()
                        body.addView(Ui.emptyState(this@DetailActivity, "加载失败：${e.message}", "重试") {
                            load(false)
                        })
                    } else {
                        Ui.toast(this@DetailActivity, "刷新失败，请检查网络")
                    }
                }
            }
        }
    }

    private fun render(data: CourseDetail) {
        rowMarks.clear()
        playingIdx = -1
        body.removeAllViews()
        body.addView(header(data))
        body.addView(chapterList(data))
        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(24))
        })
        syncPlaying(PlayerController.state())
        if (restoreScroll >= 0) {
            val target = restoreScroll
            restoreScroll = -1
            scroll.post { scroll.scrollTo(0, target) }
        }
    }

    private fun syncPlaying(state: PlayState) {
        highlightPlaying(if (state.hasItem && state.courseId == courseId) state.lessonIdx else -1)
    }

    private fun header(data: CourseDetail): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Ui.roundBg(this@DetailActivity, Ui.orangeSoft(this@DetailActivity, 0x0F), 0f)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        top.addView(CoverView(this, 96, 130, 10f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(130))
            load(data.coverUrl, data.name)
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tagsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        if (data.badge.isNotEmpty()) {
            tagsRow.addView(Ui.tag(this, data.badge).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
            })
        }
        if (data.videoCourse) tagsRow.addView(Ui.videoTag(this))
        if (tagsRow.childCount > 0) col.addView(tagsRow)
        col.addView(Ui.text(this, data.name, 21f, c(R.color.text), true, 2))
        val byline = listOf(data.author, data.identity).filter { it.isNotBlank() }.joinToString(" · ")
        if (byline.isNotEmpty()) {
            col.addView(Ui.text(this, byline, 12f, c(R.color.text_dim), false, 2).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        val meta = buildString {
            append(data.total).append(" 讲")
            if (data.learners.isNotBlank()) append(" · ").append(data.learners)
        }
        col.addView(Ui.text(this, meta, 12f, c(R.color.text_dim), false, 1).apply {
            setPadding(0, dp(4), 0, 0)
        })
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        progressText = Ui.text(this, if (data.read > 0) "已学完 ${data.pct}%" else "未开始",
            12f, c(R.color.orange), true, 1)
        progressLine = ProgressLine(this, 4f).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginStart = dp(8) }
            pct = data.pct.toFloat()
        }
        progressRow.addView(progressText)
        progressRow.addView(progressLine)
        col.addView(progressRow)
        top.addView(col)
        box.addView(top)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val target = continueLesson(data)
        continueButton = Ui.primaryButton(
            this,
            if (target != null) "继续学习 · ${target.label}" else "开始学习"
        ) {
            val item = continueLesson(detail ?: return@primaryButton) ?: return@primaryButton
            openReader(item.idx)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(10) }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        val fromStart = Ui.secondaryButton(this, "从头开始") {
            val first = (detail ?: return@secondaryButton).lessons.firstOrNull() ?: return@secondaryButton
            openReader(first.idx)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        buttons.addView(continueButton)
        buttons.addView(fromStart)
        box.addView(buttons)
        return box
    }

    private fun continueLesson(data: CourseDetail): LessonItem? {
        val last = data.last?.lesson
        if (last != null) {
            data.lessons.firstOrNull { it.idx == last && !it.read }?.let { return it }
            data.lessons.firstOrNull { it.idx == last }?.let { return it }
        }
        return data.lessons.firstOrNull { !it.read } ?: data.lessons.firstOrNull()
    }

    private fun chapterList(data: CourseDetail): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        head.addView(Ui.text(this, "课程目录（${data.total}）", 16f, c(R.color.text), true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(head)

        if (expanded.isEmpty() && data.chapters.isNotEmpty()) expanded.add(0)
        for ((index, chapter) in data.chapters.withIndex()) {
            val open = expanded.contains(index)
            val rowsBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (open) View.VISIBLE else View.GONE
            }
            var filled = false
            fun fill() {
                if (filled) return
                filled = true
                for (item in chapter.items) rowsBox.addView(lessonRow(item))
                highlightPlaying(playingIdx, force = true)
            }
            val chevron = Ui.icon(
                this,
                if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                c(R.color.text_dim), 16
            )
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = Ui.roundBg(this@DetailActivity, c(R.color.surface_alt), 0f)
                isClickable = true
                // 只开合这一章的容器，别整页重绘（会重新拉封面、丢滚动位置）
                setOnClickListener {
                    val nowOpen = !expanded.contains(index)
                    if (nowOpen) {
                        expanded.add(index)
                        fill()
                        rowsBox.visibility = View.VISIBLE
                    } else {
                        expanded.remove(index)
                        rowsBox.visibility = View.GONE
                    }
                    chevron.setImageResource(
                        if (nowOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
                    )
                }
            }
            header.addView(Ui.text(this, chapter.name, 13f, c(R.color.text), true, 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(Ui.text(this, "${chapter.items.size} 讲", 12f, c(R.color.text_dim), false, 1).apply {
                setPadding(0, 0, dp(6), 0)
            })
            header.addView(chevron)
            box.addView(header)
            box.addView(rowsBox)
            if (open) fill()
        }
        return box
    }

    /** 播放中的讲次高亮：换讲时只改两行，不重绘目录。 */
    private fun highlightPlaying(idx: Int, force: Boolean = false) {
        if (!force && idx == playingIdx) return
        rowMarks[playingIdx]?.let { (row, mark) ->
            row.background = null
            mark.visibility = View.GONE
        }
        playingIdx = idx
        rowMarks[idx]?.let { (row, mark) ->
            row.background = Ui.roundBg(this, Ui.orangeSoft(this, 0x0F), 0f)
            mark.visibility = View.VISIBLE
        }
    }

    private fun lessonRow(item: LessonItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(12), dp(11))
            isClickable = true
            setOnClickListener { openReader(item.idx) }
        }
        val mark = View(this).apply {
            setBackgroundColor(Ui.orange(this@DetailActivity))
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(26)).apply { marginEnd = dp(8) }
            visibility = View.GONE
        }
        row.addView(mark)
        rowMarks[item.idx] = row to mark
        row.addView(Ui.text(this, "%02d".format(item.idx), 12f, c(R.color.text_dim), false, 1).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(Ui.text(this, item.label, 14f, c(R.color.text), false, 2).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (item.dur.isNotBlank()) {
            row.addView(Ui.text(this, item.dur, 11f, c(R.color.text_dim), false, 1).apply {
                setPadding(dp(6), 0, dp(6), 0)
            })
        }
        if (item.hasAudio) {
            row.addView(Ui.icon(this, R.drawable.ic_headphone, c(R.color.text_dim), 15, "播放 ${item.label}") {
                playAudio(item)
            })
        }
        if (item.hasPdf && item.pdfUrl.isNotEmpty()) {
            row.addView(Ui.icon(this, R.drawable.ic_pdf, c(R.color.text_dim), 15, "查看 ${item.label} 的 PDF 原稿") {
                Pdf.open(this@DetailActivity, item.pdfUrl)
            })
        }
        val status = when {
            item.read -> "已学完"
            item.pct > 0 -> "已学${item.pct.toInt()}%"
            else -> "未开始"
        }
        row.addView(Ui.text(
            this, status, 11f,
            when {
                item.read -> c(R.color.green)
                item.pct > 0 -> c(R.color.orange)
                else -> c(R.color.text_dim)
            },
            item.read || item.pct > 0, 1
        ).apply {
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        return row
    }

    private fun playAudio(item: LessonItem) {
        val data = detail ?: return
        if (!item.hasAudio || item.audioUrl.isEmpty()) return
        val queue = data.lessons.filter { it.hasAudio && it.audioUrl.isNotEmpty() }.map {
            QueueItem(courseId, data.name, data.coverUrl, it.idx, it.label, it.audioUrl)
        }
        val index = queue.indexOfFirst { it.idx == item.idx }.coerceAtLeast(0)
        PlayerController.play(this, queue, index)
        openReader(item.idx)
    }

    private fun openReader(idx: Int) {
        val data = detail
        val item = data?.lessons?.firstOrNull { it.idx == idx }
        ReaderActivity.open(this, courseId, idx, data?.name ?: courseName, item?.label.orEmpty())
    }

    override fun onDestroy() {
        playObserver?.let { PlayerController.unobserve(it) }
        playObserver = null
        if (built) mini.detach()
        super.onDestroy()
    }
}
