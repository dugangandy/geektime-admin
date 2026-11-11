package com.gk.reader

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

data class FilterState(
    val category: String = "",
    val sort: Int = 0,        // 0 最近学习 1 名称 2 讲数
    val progress: Int = 0     // 0 全部 1 未开始 2 进行中 3 已学完
)

object Filters {
    private val collator = java.text.Collator.getInstance(Locale.CHINA)

    fun apply(courses: List<CourseSummary>, f: FilterState): List<CourseSummary> {
        var list = if (f.category.isEmpty()) courses
        else courses.filter { it.category == f.category }
        list = when (f.progress) {
            1 -> list.filter { !it.started }
            2 -> list.filter { it.started && it.pct < 100 }
            3 -> list.filter { it.pct >= 100 }
            else -> list
        }
        return when (f.sort) {
            1 -> list.sortedWith(Comparator { a, b -> collator.compare(a.name, b.name) })
            2 -> list.sortedWith(Comparator { a, b ->
                val byLessons = b.total.compareTo(a.total)
                if (byLessons != 0) byLessons else collator.compare(a.name, b.name)
            })
            else -> list.sortedWith(compareByDescending<CourseSummary> { it.last?.at ?: 0L })
        }
    }
}

/** 底部筛选弹层：方向 / 排序 / 进度，三页签共享一份 FilterState。 */
object FilterSheet {
    fun show(
        ctx: Context,
        categories: List<Category>,
        initial: FilterState,
        total: Int,
        countOf: (FilterState) -> Int,
        onApply: (FilterState) -> Unit
    ) {
        var state = initial
        var tab = 0

        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(16))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(ctx, "筛选", 18f, ctx.c(R.color.text), true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val reset = Ui.text(ctx, "重置", 13f, ctx.c(R.color.text_dim), false, 1).apply {
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            isClickable = true
        }
        header.addView(reset)
        header.addView(Ui.icon(ctx, R.drawable.ic_close, ctx.c(R.color.text_dim), 20, "关闭筛选") { dialog.dismiss() })
        root.addView(header)

        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(12))
        }
        val tabLabels = arrayOf("方向", "排序", "进度")
        val tabViews = ArrayList<TextView>()
        var render: () -> Unit = {}
        for ((i, label) in tabLabels.withIndex()) {
            val tv = Ui.chip(ctx, label, i == tab).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)
                ).apply { marginEnd = ctx.dp(8) }
                setOnClickListener {
                    tab = i
                    render()
                }
            }
            tabViews.add(tv)
            tabs.addView(tv)
        }
        root.addView(tabs)

        val body = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(280)
            )
        }
        val bodyBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(bodyBox)
        root.addView(body)

        val apply = Ui.primaryButton(ctx, "", {
            onApply(state)
            dialog.dismiss()
        }).apply {
            setPadding(0, ctx.dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(44)
            ).apply { topMargin = ctx.dp(12) }
        }
        root.addView(apply)

        fun row(label: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                minimumHeight = ctx.dp(46)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = Ui.roundBg(
                    ctx,
                    if (selected) Ui.orangeSoft(ctx, 0x14) else 0x00000000,
                    8f
                )
                isClickable = true
                setOnClickListener { onClick() }
            }
            wrap.addView(Ui.text(ctx, label, 14f, if (selected) ctx.c(R.color.orange) else ctx.c(R.color.text), selected, 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selected) {
                wrap.addView(Ui.icon(ctx, R.drawable.ic_check, ctx.c(R.color.orange), 16))
            }
            return wrap
        }

        render = {
            for (i in 0 until 3) {
                val tv = tabViews[i]
                val selected = i == tab
                tv.setTextColor(if (selected) android.graphics.Color.WHITE else ctx.c(R.color.text))
                tv.background = if (selected) Ui.roundBg(ctx, Ui.orange(ctx), 16f)
                else Ui.roundBg(ctx, ctx.c(R.color.surface_alt), 16f)
                tv.setTypeface(tv.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            bodyBox.removeAllViews()
            bodyBox.setPadding(0, 0, 0, ctx.dp(4))
            when (tab) {
                0 -> {
                    bodyBox.addView(row("全部领域", state.category.isEmpty()) {
                        state = state.copy(category = "")
                        render()
                    })
                    for (c in categories) {
                        val label = if (c.count > 0) "${c.label}（${c.count}）" else c.label
                        bodyBox.addView(row(label, state.category == c.key) {
                            state = state.copy(category = c.key)
                            render()
                        })
                    }
                }
                1 -> {
                    val options = arrayOf("最近学习", "课程名称", "讲数从多到少")
                    for ((i, label) in options.withIndex()) {
                        bodyBox.addView(row(label, state.sort == i) {
                            state = state.copy(sort = i)
                            render()
                        })
                    }
                }
                else -> {
                    val options = arrayOf("全部", "未开始", "进行中", "已学完")
                    for ((i, label) in options.withIndex()) {
                        bodyBox.addView(row(label, state.progress == i) {
                            state = state.copy(progress = i)
                            render()
                        })
                    }
                }
            }
            val n = countOf(state).coerceAtMost(total)
            apply.text = "查看 $n 门课程"
        }

        reset.setOnClickListener {
            state = FilterState()
            tab = 0
            render()
        }

        render()
        dialog.setContentView(root)
        dialog.show()
    }
}
