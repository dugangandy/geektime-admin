package com.gk.reader

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(buildCard())

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    private fun buildCard(): LinearLayout {
        val card = Ui.card(ctx).apply {
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
        val cover = CoverView(ctx, 76, 102, 8f).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(76), ctx.dp(102))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(cover)
        card.addView(column)
        return card
    }

    inner class Holder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        private val cover = card.getChildAt(0) as CoverView
        private val column = card.getChildAt(1) as LinearLayout
        private var bound: CourseSummary? = null

        init {
            card.setOnClickListener {
                bound?.let(onClick)
            }
        }

        fun bind(course: CourseSummary) {
            bound = course
            cover.load(course.coverUrl, course.name)
            column.removeAllViews()

            if (course.badge.isNotEmpty()) {
                column.addView(Ui.tag(ctx, course.badge).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = ctx.dp(4) }
                })
            }
            column.addView(Ui.text(ctx, course.name, 16f, ctx.c(R.color.text), true, 2))
            val author = listOf(course.author, course.identity)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (author.isNotEmpty()) {
                column.addView(Ui.text(ctx, author, 12f, ctx.c(R.color.text_dim), false, 1).apply {
                    setPadding(0, ctx.dp(4), 0, 0)
                })
            }
            val meta = buildString {
                append(course.total).append(" 讲")
                if (course.learners.isNotBlank()) append(" · ").append(course.learners)
            }
            column.addView(Ui.text(ctx, meta, 12f, ctx.c(R.color.text_dim), false, 1).apply {
                setPadding(0, ctx.dp(4), 0, ctx.dp(8))
            })
            column.addView(progressRow(course))
        }

        private fun progressRow(course: CourseSummary): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (!course.started) {
                row.addView(Ui.text(ctx, "未开始", 12f, ctx.c(R.color.text_dim), false, 1))
                row.addView(Ui.spacer(ctx))
                return row
            }
            val line = ProgressLine(ctx, 3f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ctx.dp(3), 1f)
                pct = course.pct.toFloat()
            }
            row.addView(line)
            val pct = Ui.text(ctx, "${course.pct}%", 12f, ctx.c(R.color.orange), true, 1).apply {
                setPadding(ctx.dp(8), 0, 0, 0)
            }
            row.addView(pct)
            if (course.pct < 100) {
                val go = Ui.text(ctx, "继续", 12f, ctx.c(R.color.orange), true, 1).apply {
                    setPadding(ctx.dp(10), 0, 0, 0)
                    isClickable = true
                    setOnClickListener { onContinue(course) }
                }
                row.addView(go)
            }
            return row
        }
    }
}
