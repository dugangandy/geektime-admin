package com.gk.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 极客听书 · 声波呼吸加载（替代水滴，与全屏过渡页的声波标同一套语言）。
 *
 * 三段薄荷绿声波条错峰起伏，像正在播放/正在听；只通过绘制高度变化模拟 scaleY，
 * 无 blur / 阴影 / 粒子。不可见或切后台自动停帧；系统「减少动态效果」时降级为静态三格。
 * 同一控件按自身尺寸等比缩放，可用于 48dp 独立占位，也可塞进更小的容器。
 */
class WaveLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var barColor = context.c(R.color.brand_mint)

    private var animator: ValueAnimator? = null
    private var phase = 0f          // 0..1 循环
    private var reduced = false     // 减少动态效果：静态三格

    init {
        paint.color = barColor
    }

    /** 自定义条色（默认品牌薄荷绿）。 */
    fun setBarColor(value: Int) {
        barColor = value
        paint.color = value
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = context.dp(48)
        setMeasuredDimension(
            resolveSize(size, widthSpec),
            resolveSize(size, heightSpec)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        play()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 用自身可见性判断：父容器变更时回调参数是「变化视图」的可见性
        if (this.visibility == View.VISIBLE) play() else stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // 切后台时自身可见性不变，只有窗口不可见，不在这里停会一直空转
        if (visibility == View.VISIBLE && this.visibility == View.VISIBLE) play() else stop()
    }

    private fun reduceMotion(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    private fun play() {
        if (visibility != View.VISIBLE) return
        if (reduceMotion()) {
            reduced = true
            invalidate()
            return
        }
        if (animator?.isRunning == true) return
        reduced = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = minOf(w, h)
        val barW = size * 0.13f
        val gap = size * 0.09f
        val maxH = size * 0.52f
        val minH = maxH * 0.34f
        val totalW = barW * 3 + gap * 2
        val left = (w - totalW) / 2f
        val bottom = h / 2f + maxH / 2f
        paint.color = barColor

        for (i in 0 until 3) {
            val level = if (reduced) {
                0.62f
            } else {
                val p = (phase + i * 0.22f) % 1f
                val wave = (sin(p * 2.0 * PI) + 1.0) / 2.0
                0.32f + 0.68f * wave.toFloat()
            }
            val barH = minH + (maxH - minH) * level
            val x = left + i * (barW + gap)
            val r = barW / 2f
            canvas.drawRoundRect(x, bottom - barH, x + barW, bottom, r, r, paint)
        }
    }
}
