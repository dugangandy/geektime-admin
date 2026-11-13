package com.gk.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课程详情：深色课程头（课名 / 作者 / 整体进度）+ 分组目录列表。
 * 目录头部固定，只有讲次列表滚动；「回到上次学习」浮在右下角。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val STATE_SCROLL = "state_scroll"
        /** 头部深色底，和封面遮罩同色；状态栏也跟随它。 */
        private const val HEADER_DARK = 0xFF16171C.toInt()

        fun open(ctx: Context, courseId: String, courseName: String) {
            ctx.startActivity(
                Intent(ctx, DetailActivity::class.java)
                    .putExtra(EXTRA_ID, courseId)
                    .putExtra(EXTRA_NAME, courseName)
            )
        }
    }

    private val api: Api get() = (application as GkApp).api
    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var mini: MiniPlayer
    private lateinit var fab: TextView
    private lateinit var coverBg: CoverView
    private lateinit var headName: TextView
    private lateinit var headByline: TextView
    private lateinit var headMeta: TextView
    private lateinit var headPct: TextView
    private lateinit var headProgress: ProgressLine
    private lateinit var badgeView: TextView
    private lateinit var sortLabel: TextView

    private var courseId = ""
    private var courseName = ""
    private var detail: CourseDetail? = null
    /** 折叠的章节（用对象引用做键：同名章节也不会互相串）。 */
    private val collapsed = HashSet<Chapter>()
    private var sortDesc = false
    private var filter = 0
    private val rows = HashMap<Int, LessonRowView>()
    private var playingIdx = -1
    private var firstResume = true
    private var built = false
    private var restoreScroll = -1
    private var playObserver: ((PlayState) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = intent.getStringExtra(EXTRA_ID).orEmpty()
        courseName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (courseId.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            restoreScroll = savedInstanceState.getInt(STATE_SCROLL, -1)
        }
        window.statusBarColor = HEADER_DARK
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        buildUi()
        built = true
        playObserver = PlayerController.observe { syncPlaying(it) }
        showLoading()
        load(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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

    // ------------------------------------------------------------ 布局
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
        }

        // 课程头：封面铺底 + 深色渐变，保证白色文字在任何封面上都可读
        // 高度必须写死：封面用 MATCH_PARENT 铺底时，wrap_content 会被封面原图尺寸撑到整屏，
        // 下面的内容区就会只剩 0 高（整页看起来只有深色封面）。
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            )
        }
        coverBg = CoverView(this, 1, 1, 0f).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            alpha = 0.45f
        }
        header.addView(coverBg)
        header.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(HEADER_DARK, 0xF016171C.toInt(), 0xC016171C.toInt())
            )
        })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(18), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        titleRow.addView(Ui.icon(this, R.drawable.ic_back, Color.WHITE, 24, "返回") { finish() })
        headName = Ui.text(this, courseName.ifEmpty { "课程详情" }, 18f, Color.WHITE, true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4) }
        }
        titleRow.addView(headName)
        badgeView = Ui.tag(this, "").apply { visibility = View.GONE }
        titleRow.addView(badgeView)
        column.addView(titleRow)

        headByline = Ui.text(this, "", 12f, 0xB3FFFFFF.toInt(), false, 1).apply {
            setPadding(dp(4), 0, 0, 0)
        }
        column.addView(headByline)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), 0, 0)
        }
        headMeta = Ui.text(this, "", 11f, 0x99FFFFFF.toInt(), false, 1)
        metaRow.addView(headMeta)
        metaRow.addView(Ui.spacer(this))
        headPct = Ui.text(this, "", 11f, c(R.color.orange), true, 1)
        metaRow.addView(headPct)
        column.addView(metaRow)

        headProgress = ProgressLine(this, 3f, 0x4DFFFFFF, c(R.color.orange)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { topMargin = dp(9) }
        }
        column.addView(headProgress)
        header.addView(column)
        root.addView(header)

        // 内容区：白色圆角上翻，压住头部底边
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRoundBg(c(R.color.bg), 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = -dp(18) }
        }

        val dirRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        val dirTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openSheet() }
        }
        dirTitle.addView(Ui.text(this, "课程目录", 18f, c(R.color.text), true, 1))
        dirTitle.addView(Ui.icon(this, R.drawable.ic_chevron_down, c(R.color.text_dim), 16).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(6)
        })
        dirRow.addView(dirTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dirRow.addView(Ui.spacer(this))
        val sortPill = Ui.pill(this, "正序", R.drawable.ic_sort) { toggleSort() }
        sortLabel = sortPill.tag as TextView
        dirRow.addView(sortPill)
        dirRow.addView(Ui.icon(this, R.drawable.ic_filter, c(R.color.text_dim), 18, "筛选讲次") {
            showFilter()
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(4) })
        content.addView(dirRow)

        val listFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        listFrame.addView(scroll)

        fab = Ui.text(this, "回到上次学习", 12f, c(R.color.orange), true, 1).apply {
            setPadding(dp(15), dp(10), dp(15), dp(10))
            background = Ui.roundBg(this@DetailActivity, c(R.color.surface), 20f, c(R.color.line), 1f)
            elevation = dp(4).toFloat()
            isClickable = true
            setOnClickListener { continueReading() }
        }
        listFrame.addView(fab, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM
        ).apply { marginEnd = dp(16); bottomMargin = dp(18) })
        content.addView(listFrame)

        root.addView(content)
        mini = MiniPlayer(this).apply { attach() }
        root.addView(mini, MiniPlayer.layoutParams(this))
        setContentView(root)
    }

    private fun topRoundBg(color: Int, radiusDp: Float): GradientDrawable {
        val r = dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(color)
        }
    }

    // ------------------------------------------------------------ 数据
    private fun showLoading() {
        listBox.removeAllViews()
        rows.clear()
        // 统一为水滴加载动画，居中偏上，避免与返回栏贴太近
        listBox.addView(DropLoadingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(64)
            }
        })
    }

    private fun load(silent: Boolean) {
        if (!silent) showLoading()
        lifecycleScope.launch {
            try {
                val data = api.course(courseId)
                withContext(Dispatchers.Main) {
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
                        listBox.removeAllViews()
                        rows.clear()
                        listBox.addView(Ui.emptyState(this@DetailActivity, "加载失败：${e.message}", "重试") {
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
        val first = detail == null
        detail = data
        headName.text = data.name
        val byline = listOf(data.author, data.identity).filter { it.isNotBlank() }.joinToString("  ·  ")
        headByline.text = byline
        headByline.visibility = if (byline.isEmpty()) View.GONE else View.VISIBLE
        headMeta.text = "共 ${data.total} 讲"
        headPct.text = when {
            data.read > 0 -> "已学完 ${data.pct}%"
            else -> "未开始学习"
        }
        headProgress.pct = data.pct.toFloat()
        if (data.badge.isNotEmpty()) {
            badgeView.text = data.badge
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }
        coverBg.load(data.coverUrl, data.name)
        fab.text = fabLabel(data)
        renderChapters()
        // 首次进入：骨架 → 真实目录，淡入而不是硬切；刷新（onResume 回来）不再重播
        if (first) Ui.fadeInUp(scroll, 0, offsetDp = 8)
        if (first && restoreScroll >= 0) {
            val target = restoreScroll
            restoreScroll = -1
            scroll.post { scroll.scrollTo(0, target) }
        }
    }

    private fun fabLabel(data: CourseDetail): String {
        val target = continueLesson(data) ?: return "从头开始"
        return when {
            data.last != null && data.last.lesson == target.idx -> "回到上次学习"
            target.read -> "从头开始"
            else -> "继续学习"
        }
    }

    private fun renderChapters() {
        val data = detail ?: return
        rows.clear()
        playingIdx = -1
        listBox.removeAllViews()
        val chapters = if (sortDesc) data.chapters.reversed() else data.chapters
        var shown = 0
        for (chapter in chapters) {
            val items = chapter.items.filter { matchFilter(it) }
            if (items.isEmpty()) continue
            shown += items.size
            val open = !collapsed.contains(chapter)
            listBox.addView(Syllabus.chapterBar(this, chapter.name, items.size, open) {
                if (open) collapsed.add(chapter) else collapsed.remove(chapter)
                renderChapters()
            })
            if (!open) continue
            for (item in items) {
                val row = LessonRowView(
                    this, item,
                    onPlay = { playAudio(item) },
                    onClick = { openReader(item.idx) }
                )
                rows[item.idx] = row
                listBox.addView(row)
            }
        }
        if (shown == 0) {
            listBox.addView(Ui.emptyState(this, "没有符合条件的讲次"))
        } else {
            listBox.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(76))
            })
        }
        syncPlaying(PlayerController.state(), force = true)
    }

    private fun matchFilter(item: LessonItem): Boolean = when (filter) {
        1 -> !item.read
        2 -> item.read
        else -> true
    }

    private fun toggleSort() {
        sortDesc = !sortDesc
        sortLabel.text = if (sortDesc) "倒序" else "正序"
        renderChapters()
    }

    private fun showFilter() {
        val labels = arrayOf("全部讲次", "只看未学完", "只看已学完")
        AlertDialog.Builder(this)
            .setTitle("筛选讲次")
            .setSingleChoiceItems(labels, filter) { dialog, which ->
                filter = which
                dialog.dismiss()
                renderChapters()
            }
            .show()
    }

    private fun openSheet() {
        val data = detail ?: return
        val current = PlayerController.state().let {
            if (it.hasItem && it.courseId == courseId) it.lessonIdx else -1
        }
        CourseSheet.show(this, courseId, data.name, current) { item ->
            openReader(item.idx)
        }
    }

    private fun syncPlaying(state: PlayState, force: Boolean = false) {
        val idx = if (state.hasItem && state.courseId == courseId) state.lessonIdx else -1
        if (!force && idx == playingIdx) return
        rows[playingIdx]?.active = false
        playingIdx = idx
        rows[idx]?.active = true
    }

    // ------------------------------------------------------------ 讲次操作
    private fun continueLesson(data: CourseDetail): LessonItem? {
        val last = data.last?.lesson
        if (last != null) {
            data.lessons.firstOrNull { it.idx == last && !it.read }?.let { return it }
            data.lessons.firstOrNull { it.idx == last }?.let { return it }
        }
        return data.lessons.firstOrNull { !it.read } ?: data.lessons.firstOrNull()
    }

    private fun continueReading() {
        val data = detail ?: return
        val item = continueLesson(data) ?: return
        openReader(item.idx)
    }

    private fun playAudio(item: LessonItem) {
        val data = detail ?: return
        if (!item.hasAudio || item.audioUrl.isEmpty()) return
        val queue = data.lessons.filter { it.hasAudio && it.audioUrl.isNotEmpty() }.map {
            QueueItem(courseId, data.name, data.coverUrl, it.idx, it.label, it.audioUrl)
        }
        val index = queue.indexOfFirst { it.idx == item.idx }.coerceAtLeast(0)
        PlayerController.play(this, queue, index)
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
