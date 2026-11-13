package com.gk.reader

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 加载骨架屏：用灰块拼出最终版式的大致轮廓，比「正在加载…」更少跳变。
 * 呼吸动画挂在容器上，容器随内容一起被移除，不需要手动 stop。
 */
object Skeleton {

    private fun block(
        ctx: Context,
        width: Int,
        heightDp: Int,
        radiusDp: Float = 6f
    ): View = View(ctx).apply {
        background = Ui.roundBg(ctx, ctx.c(R.color.surface_alt), radiusDp)
        layoutParams = LinearLayout.LayoutParams(width, ctx.dp(heightDp))
    }

    private fun blockW(ctx: Context, ratio: Float, heightDp: Int, radiusDp: Float = 6f): View =
        block(ctx, (ctx.resources.displayMetrics.widthPixels * ratio).toInt(), heightDp, radiusDp)

    private fun pulse(root: View) {
        val anim = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 780
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        anim.addUpdateListener { root.alpha = it.animatedValue as Float }
        anim.start()
        // 无限循环动画必须随 View 离窗一起停，否则骨架被移除后仍在空转
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                anim.cancel()
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    /** 课程目录骨架：章节条 + 若干讲次行，和真实目录同一版式。 */
    fun syllabus(ctx: Context, chapters: Int = 3, rows: Int = 4): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        repeat(chapters) { c ->
            box.addView(block(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 40, 0f).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(if (c == 0) 6 else 12)
            })
            repeat(rows) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(16), ctx.dp(15), ctx.dp(16), ctx.dp(15))
                }
                row.addView(blockW(ctx, 0.68f, 13))
                row.addView(blockW(ctx, 0.32f, 10).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(10)
                })
                box.addView(row)
            }
        }
        pulse(box)
        return box
    }

    /** 课程卡片骨架：左侧封面块 + 三行文字块。 */
    fun cards(ctx: Context, count: Int = 7): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), 0)
        }
        repeat(count) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ctx.dp(11), 0, ctx.dp(11))
            }
            row.addView(block(ctx, ctx.dp(72), 98, 9f))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), ctx.dp(4), 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(blockW(ctx, 0.42f, 14))
            col.addView(blockW(ctx, 0.28f, 10).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(10)
            })
            col.addView(blockW(ctx, 0.34f, 8, 4f).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(12)
            })
            row.addView(col)
            box.addView(row)
        }
        pulse(box)
        return box
    }
}
