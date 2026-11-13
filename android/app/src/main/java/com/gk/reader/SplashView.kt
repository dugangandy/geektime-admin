package com.gk.reader

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 极客听书 · 全屏品牌过渡页（参考星海 FM 的 #boot / 恢复过渡页）。
 *
 * 结构：整屏深空渐变 + 左上薄荷 / 右下珊瑚两团柔光 + 悬浮声波标 + slogan
 * + 细滑动载入条 + 底部品牌行。
 *
 * 性能：只动 translate / alpha / 一条 shader 平移；柔光用超大半径 radial-gradient
 * 模拟，不用 blur / 位图缩放，低端机首帧也便宜。
 *
 * 用法：作为全屏内容铺满；就绪后 [fadeOut] 320ms 淡出，露出下方界面。
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val logo = ImageView(context)
    private val bar = BootBar(context)
    private var logoFloat: ObjectAnimator? = null

    init {
        // 过渡层期间挡住下方点击，避免点到被盖住的表单
        isClickable = true
        setBackgroundColor(SPLASH_BG)
        addView(BackdropView(context), LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        logo.setImageResource(R.drawable.ic_brand_wave)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))

        col.addView(text("听一课", 34f, 0xFFF2F6FF.toInt(), true).apply {
            setPadding(0, dp(22), 0, 0)
        })
        col.addView(text("即刻成长", 34f, context.c(R.color.brand_mint), true).apply {
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(bar, LinearLayout.LayoutParams(dp(108), dp(3)).apply { topMargin = dp(26) })
        addView(col)

        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(46)
            }
        }
        brand.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_brand_wave)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
        })
        brand.addView(text("极客听书", 12.5f, 0xFF8D9BB6.toInt(), false).apply {
            letterSpacing = 0.18f
        })
        addView(brand)
    }

    fun play() {
        bar.start()
        if (logoFloat?.isRunning == true) return
        logoFloat = ObjectAnimator.ofFloat(logo, "translationY", 0f, -dp(9).toFloat()).apply {
            duration = 3600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** 整屏淡出（320ms），结束后回调，便于移除视图。 */
    fun fadeOut(onEnd: (() -> Unit)? = null) {
        animate().alpha(0f).setDuration(320).withEndAction { onEnd?.invoke() }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        play()
    }

    override fun onDetachedFromWindow() {
        logoFloat?.cancel()
        logoFloat = null
        bar.stop()
        super.onDetachedFromWindow()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 深空渐变 + 两团柔光，纯 gradient 模拟，不花 blur 的钱。 */
    private class BackdropView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var base: LinearGradient? = null
        private var glowA: RadialGradient? = null
        private var glowB: RadialGradient? = null

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (w <= 0 || h <= 0) return
            base = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF0B1E30.toInt(), SplashView.SPLASH_BG, 0xFF071625.toInt()),
                floatArrayOf(0f, 0.66f, 1f), Shader.TileMode.CLAMP
            )
            val r = (maxOf(w, h) * 0.85f).coerceAtLeast(1f)
            glowA = RadialGradient(
                w * 0.02f, -h * 0.08f, r,
                intArrayOf(0x2E59E1B3, 0x0059E1B3), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            glowB = RadialGradient(
                w * 1.04f, h * 1.02f, r,
                intArrayOf(0x24FF8066, 0x00FF8066), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            base?.let { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
            glowA?.let { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
            glowB?.let { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
            paint.shader = null
        }
    }

    /** 细滑动载入条：渐变块按 shader 平移循环，参考 FM 的 .boot-bar。 */
    private class BootBar(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: LinearGradient? = null
        private var anim: ValueAnimator? = null
        private var t = 0f

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (w <= 0) return
            shader = LinearGradient(
                0f, 0f, w * 0.38f, 0f,
                intArrayOf(0x0059E1B3, 0xFF59E1B3.toInt(), 0xFFFF8066.toInt(), 0x00FF8066),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }

        fun start() {
            if (anim?.isRunning == true) return
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            anim?.cancel()
            anim = null
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val trackW = w * 0.38f
            trackPaint.color = 0x1AFFFFFF
            canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, trackPaint)
            val x = -trackW + (w + trackW) * t
            canvas.save()
            canvas.translate(x, 0f)
            paint.shader = shader
            canvas.drawRoundRect(0f, 0f, trackW, h, h / 2f, h / 2f, paint)
            canvas.restore()
            paint.shader = null
        }
    }

    companion object {
        /** 过渡页深空底色，LoginActivity 也用它同步状态栏。 */
        const val SPLASH_BG = 0xFF06101A.toInt()
    }
}
