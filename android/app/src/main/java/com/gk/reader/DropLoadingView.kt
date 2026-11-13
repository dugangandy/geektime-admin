package com.gk.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * 极客听书 · 水滴加载动画（方案 B：水滴石穿）。
 *
 * 设计：顶部凝聚一滴水 → 加速下落 → 触线激起涟漪 → 循环。
 * 只动 translate / scale / alpha，无模糊、无阴影、无粒子，适配低性能设备。
 *
 * 性能与降级：
 * - 用单条 ValueAnimator 驱动 phase(0..1)，每帧仅画 2 个简单图形；
 * - 进入后台 / 从窗口移除 / 不可见时自动停止动画；
 * - 系统开启「减少动态效果」(ANIMATOR_DURATION_SCALE=0) 时降级为静态图标。
 */
class DropLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
    }

    private var dropColor = context.c(R.color.brand_mint)
    private var rippleColor = context.c(R.color.brand_mint)
    private var accentColor = context.c(R.color.brand_coral)

    private val fall = AccelerateInterpolator()
    private val rise = DecelerateInterpolator()

    private var animator: ValueAnimator? = null
    private var phase = 0f          // 0..1 循环；-1 表示静态降级
    private var visible = true

    init {
        dropPaint.color = dropColor
        ripplePaint.color = rippleColor
    }

    /** 自定义配色（如放在深墨蓝启动页上，可用默认品牌色）。 */
    fun setColors(drop: Int, ripple: Int, accent: Int) {
        dropColor = drop; rippleColor = ripple; accentColor = accent
        dropPaint.color = drop; ripplePaint.color = ripple
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
        visible = visibility == View.VISIBLE
        if (visible) play() else stop()
    }

    private fun reduceMotion(): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Settings.SettingNotFoundException) {
            false
        }
    }

    private fun play() {
        if (!visible) return
        if (reduceMotion()) {
            // 降级：静态水滴 + 一圈淡涟漪，不做循环
            phase = -1f
            invalidate()
            return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
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
        val cx = width / 2f
        val top = paddingTop + context.dp(6f)
        // drawCircle 需要 Float，这里统一成 Float 免得每处再转换
        val baseline = (height - paddingBottom - context.dp(6f)).toFloat()
        val r = context.dp(6f).toFloat()

        // 珊瑚橙凝聚点（静态点睛）
        dropPaint.color = accentColor
        dropPaint.alpha = 255
        canvas.drawCircle(cx, top + r * 0.4f, context.dp(1.5f).toFloat(), dropPaint)

        if (phase < 0f) {
            // 静态降级
            drawDrop(canvas, cx, (top + baseline) / 2f, r, 1f)
            ripplePaint.alpha = (0.25f * 255).toInt()
            canvas.drawCircle(cx, baseline, r * 1.4f, ripplePaint)
            ripplePaint.alpha = 255
            return
        }

        val t = phase
        if (t < 0.5f) {
            // 前半程：下落 + 末段淡出
            val p = t / 0.5f
            val e = fall.getInterpolation(p)
            val y = top + (baseline - r * 1.6f - top) * e
            val a = if (p < 0.6f) 1f else 1f - (p - 0.6f) / 0.4f
            drawDrop(canvas, cx, y, r, a.coerceIn(0f, 1f))
        }
        if (t >= 0.5f) {
            // 后半程：涟漪扩散 + 淡出
            val rp = (t - 0.5f) / 0.5f
            val e = rise.getInterpolation(rp)
            val radius = r * 0.6f + (r * 2.2f) * e
            val a = (1f - rp) * 0.8f
            ripplePaint.alpha = (a * 255).toInt()
            canvas.drawCircle(cx, baseline, radius, ripplePaint)
            ripplePaint.alpha = 255
        }
    }

    private fun drawDrop(canvas: Canvas, cx: Float, tipY: Float, r: Float, alpha: Float) {
        dropPaint.color = dropColor
        dropPaint.alpha = (alpha * 255).toInt()
        // 水滴：尖端三角 + 底部半圆
        canvas.drawCircle(cx, tipY + r * 1.2f, r, dropPaint)
        canvas.save()
        canvas.translate(cx, tipY)
        // 三角（尖端在 0,0，底在 y=r*1.2 两侧）
        canvas.drawPath(
            android.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(-r, r * 1.2f)
                lineTo(r, r * 1.2f)
                close()
            }, dropPaint
        )
        canvas.restore()
        dropPaint.alpha = 255
    }
}
