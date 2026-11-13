package com.gk.reader

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 目录渲染的统一实现：课程详情页与播放页的目录弹层共用，避免两套样式漂移。
 * 章节条是灰底分组头；讲次行是「标题 / 时长 + 状态 / 右侧播放圆钮」，可增量切换当前讲高亮。
 */
object Syllabus {

    fun chapterBar(
        ctx: Context,
        name: String,
        count: Int,
        open: Boolean,
        onToggle: () -> Unit
    ): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(14), ctx.dp(11))
            background = Ui.roundBg(ctx, ctx.c(R.color.surface_alt), 0f)
            isClickable = true
            setOnClickListener { onToggle() }
        }
        bar.addView(Ui.text(ctx, name, 13f, ctx.c(R.color.text_dim), false, 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.text(ctx, "(${count}讲)", 12f, ctx.c(R.color.text_dim), false, 1).apply {
            setPadding(ctx.dp(6), 0, ctx.dp(8), 0)
        })
        bar.addView(Ui.icon(
            ctx,
            if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            ctx.c(R.color.text_dim), 14
        ))
        return bar
    }
}

/** 目录里的一讲：标题两行、下方「时长 / 学习状态」，右侧可选播放圆钮。 */
class LessonRowView(
    context: Context,
    val item: LessonItem,
    onPlay: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) : LinearLayout(context) {

    private val mark = View(context)
    private val titleView: android.widget.TextView
    private var activeState = false

    var active: Boolean
        get() = activeState
        set(value) {
            if (activeState == value) return
            activeState = value
            applyActive()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(13), context.dp(13), context.dp(14), context.dp(13))
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }

        // 当前讲：左侧橙色竖条 + 浅橙底，和官方「正在播放」标识一致
        mark.setBackgroundColor(Ui.orange(context))
        mark.layoutParams = LayoutParams(context.dp(3), context.dp(30))
        mark.visibility = INVISIBLE
        addView(mark)

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(11), 0, context.dp(10), 0)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = Ui.text(context, item.label, 14f, context.c(R.color.text), false, 2)
        column.addView(titleView)

        val meta = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(6), 0, 0)
        }
        val parts = ArrayList<String>()
        if (item.dur.isNotBlank()) parts.add("时长 ${item.dur}")
        if (item.hasVideo) parts.add("视频")
        if (parts.isNotEmpty()) {
            meta.addView(Ui.text(context, parts.joinToString("  "), 11f, context.c(R.color.text_dim), false, 1))
        }
        val status = when {
            item.read -> "已学完"
            item.pct > 0 -> "已学 ${item.pct.toInt()}%"
            else -> "未学习"
        }
        meta.addView(Ui.text(
            context, status, 11f,
            if (item.read || item.pct > 0) context.c(R.color.orange) else context.c(R.color.text_dim),
            false, 1
        ).apply { setPadding(context.dp(8), 0, 0, 0) })
        column.addView(meta)
        addView(column)

        if (onPlay != null && item.hasAudio) {
            addView(Ui.circleIcon(
                context, R.drawable.ic_play, 28, context.c(R.color.text_dim), "播放 ${item.label}", onPlay
            ))
        }

        applyActive()
    }

    private fun applyActive() {
        mark.visibility = if (activeState) VISIBLE else INVISIBLE
        background = if (activeState) Ui.roundBg(context, Ui.orangeSoft(context, 0x0C), 0f) else null
        titleView.setTextColor(context.c(if (activeState) R.color.orange else R.color.text))
    }
}
