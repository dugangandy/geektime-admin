package com.gk.reader

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

/** 课程卡片列表：封面 + 标题 + 作者 + 讲数/人数 + 进度。 */
class CourseAdapter(
    private val ctx: Context,
    private val onClick: (CourseSummary) -> Unit,
    private val onContinue: (CourseSummary) -> Unit
) : RecyclerView.Adapter<CourseAdapter.Holder>() {

    private val items = ArrayList<CourseSummary>()

    fun submit(list: List<CourseSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder()

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    private fun buildCard(): LinearLayout = Ui.card(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(16)
            bottomMargin = ctx.dp(12)
        }
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        background = Ui.roundBg(ctx, ctx.c(R.color.surface), 12f, ctx.c(R.color.line), 1f)
        isClickable = true
    }

    /** 子 View 只建一次，bind 只改文本和可见性；否则每次滑动都要重建整列并重新测量。 */
    inner class Holder : RecyclerView.ViewHolder(buildCard()) {
        private val cover = CoverView(ctx, 76, 102, 8f).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(76), ctx.dp(102))
        }
        private val badge = Ui.tag(ctx, "").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = ctx.dp(4) }
        }
        private val videoTag = Ui.videoTag(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        private val tagsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(4) }
            addView(badge)
            addView(videoTag)
        }
        private val name = Ui.text(ctx, "", 16f, ctx.c(R.color.text), true, 2)
        private val author = Ui.text(ctx, "", 12f, ctx.c(R.color.text_dim), false, 1).apply {
            setPadding(0, ctx.dp(4), 0, 0)
        }
        private val meta = Ui.text(ctx, "", 12f, ctx.c(R.color.text_dim), false, 1).apply {
            setPadding(0, ctx.dp(4), 0, ctx.dp(8))
        }
        private val notStarted = Ui.text(ctx, "未开始", 12f, ctx.c(R.color.text_dim), false, 1)
        private val line = ProgressLine(ctx, 3f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ctx.dp(3), 1f)
        }
        private val pctText = Ui.text(ctx, "", 12f, ctx.c(R.color.orange), true, 1).apply {
            setPadding(ctx.dp(8), 0, 0, 0)
        }
        private val go = Ui.text(ctx, "继续", 12f, ctx.c(R.color.orange), true, 1).apply {
            setPadding(ctx.dp(10), 0, 0, 0)
            isClickable = true
            setOnClickListener { bound?.let(onContinue) }
        }
        private var bound: CourseSummary? = null

        init {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(notStarted)
                addView(line)
                addView(pctText)
                addView(go)
            }
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(tagsRow)
                addView(name)
                addView(author)
                addView(meta)
                addView(row)
            }
            val card = itemView as LinearLayout
            card.addView(cover)
            card.addView(column)
            card.setOnClickListener { bound?.let(onClick) }
        }

        fun bind(course: CourseSummary) {
            bound = course
            cover.load(course.coverUrl, course.name)

            badge.text = course.badge
            badge.visibility = if (course.badge.isEmpty()) View.GONE else View.VISIBLE
            videoTag.visibility = if (course.videoCourse) View.VISIBLE else View.GONE
            tagsRow.visibility = if (course.badge.isEmpty() && !course.videoCourse) View.GONE else View.VISIBLE
            name.text = course.name

            val by = listOf(course.author, course.identity)
                .filter { it.isNotBlank() }.joinToString(" · ")
            author.text = by
            author.visibility = if (by.isEmpty()) View.GONE else View.VISIBLE

            meta.text = buildString {
                append(course.total).append(" 讲")
                if (course.learners.isNotBlank()) append(" · ").append(course.learners)
            }

            notStarted.visibility = if (course.started) View.GONE else View.VISIBLE
            val shown = if (course.started) View.VISIBLE else View.GONE
            line.visibility = shown
            pctText.visibility = shown
            line.pct = course.pct.toFloat()
            pctText.text = "${course.pct}%"
            go.visibility = if (course.started && course.pct < 100) View.VISIBLE else View.GONE
        }
    }
}
