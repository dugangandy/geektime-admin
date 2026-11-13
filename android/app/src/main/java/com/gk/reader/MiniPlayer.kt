package com.gk.reader

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 全局迷你播放条：有播放内容时出现，点开全屏播放器。
 * 右侧固定是「播放 / 停止」，最后一个按钮按页面决定：首页是关闭，详情页是课程目录。
 */
class MiniPlayer(context: Context, private val showQueue: Boolean = false) : LinearLayout(context) {
    private val cover = CoverView(context, 34, 34, 6f)
    private val title = Ui.text(context, "", 13f, context.c(R.color.text), true, 1)
    private val sub = Ui.text(context, "", 11f, context.c(R.color.text_dim), false, 1)
    private val toggle = Ui.icon(context, R.drawable.ic_play, context.c(R.color.text), 22, "播放") {
        PlayerController.toggle()
    }
    private val stop = Ui.icon(context, R.drawable.ic_stop, context.c(R.color.text_dim), 20, "停止播放") {
        PlayerController.stopPlayback()
    }
    private val queue = Ui.icon(context, R.drawable.ic_queue, context.c(R.color.text_dim), 20, "课程目录") {
        openCourseSheet()
    }
    private val close = Ui.icon(context, R.drawable.ic_close, context.c(R.color.text_dim), 18, "关闭播放条") {
        PlayerController.stopPlayback()
    }
    private var observer: ((PlayState) -> Unit)? = null
    private var lastCover = ""

    init {
        orientation = VERTICAL
        visibility = GONE
        setBackgroundColor(context.c(R.color.surface))
        addView(View(context).apply {
            setBackgroundColor(context.c(R.color.line))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1))
        })

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(4), 0)
            minimumHeight = context.dp(56)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { open() }
        }
        row.addView(cover)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), 0, context.dp(6), 0)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(title)
            addView(sub)
        }
        row.addView(column)
        row.addView(toggle)
        row.addView(stop)
        row.addView(if (showQueue) queue else close)
        addView(row)
    }

    fun attach() {
        if (observer != null) return
        observer = PlayerController.observe { render(it) }
    }

    fun detach() {
        observer?.let { PlayerController.unobserve(it) }
        observer = null
    }

    private fun render(state: PlayState) {
        if (!state.hasItem) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        title.text = state.title.ifEmpty { "正在播放" }
        sub.text = when {
            state.error != null -> "播放失败，检查网络/登录"
            state.buffering -> "缓冲中…"
            else -> state.course
        }
        sub.setTextColor(context.c(if (state.error != null) R.color.red else R.color.text_dim))
        toggle.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = if (state.playing) "暂停" else "播放"
        toggle.setColorFilter(context.c(R.color.text))
        if (state.coverUrl != lastCover) {
            lastCover = state.coverUrl
            cover.load(state.coverUrl, state.course.ifEmpty { state.title })
        }
        sub.visibility = if (sub.text.isNullOrEmpty()) GONE else VISIBLE
    }

    private fun open() {
        if (!PlayerController.state().hasItem) return
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    /** 目录按钮：看整门课的讲次，选一讲就进那一讲的正文。 */
    private fun openCourseSheet() {
        val state = PlayerController.state()
        if (!state.hasItem || state.courseId.isEmpty()) return
        CourseSheet.show(context, state.courseId, state.course, state.lessonIdx) { item ->
            ReaderActivity.open(context, state.courseId, item.idx, state.course, item.label)
        }
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observer == null) attach()
    }

    fun refresh() {
        render(PlayerController.state())
    }

    companion object {
        fun layoutParams(ctx: Context): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
    }
}
