package com.gk.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import java.util.Locale

fun Context.c(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.sp(v: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
)

fun Context.isDark(): Boolean {
    val night = resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

object Ui {
    /** 橙色在 values-night 里是另一个值，硬编码会让深色模式下的强调色发暗。 */
    fun orange(ctx: Context): Int = ctx.c(R.color.orange)

    /** 橙色淡底，alpha 取 0..255。 */
    fun orangeSoft(ctx: Context, alpha: Int): Int =
        (orange(ctx) and 0x00FFFFFF) or (alpha shl 24)

    fun text(
        ctx: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE
    ): TextView = TextView(ctx).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        ellipsize = TextUtils.TruncateAt.END
        this.maxLines = maxLines
        includeFontPadding = false
    }

    fun icon(
        ctx: Context,
        @DrawableRes res: Int,
        color: Int,
        sizeDp: Int = 24,
        desc: String? = null,
        onClick: (() -> Unit)? = null
    ): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = ctx.dp(if (onClick != null) 10 else 0)
        setPadding(pad, pad, pad, pad)
        if (onClick != null) {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp + 20), ctx.dp(sizeDp + 20))
            background = ripple(ctx, Color.TRANSPARENT, ctx.dp(22).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        } else {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        }
        contentDescription = desc
        // 纯装饰图标（旁边已有文字）对 TalkBack 隐藏，可点图标必须带 desc
        if (desc == null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun ripple(ctx: Context, color: Int, radiusDp: Float = 12f, pressed: Int = 0x14000000): RippleDrawable {
        val radius = ctx.dp(radiusDp).toFloat()
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val content = if (color == Color.TRANSPARENT) null else GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(pressed), content, mask)
    }

    fun roundBg(ctx: Context, color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(ctx.dp(strokeDp), stroke)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(ctx, ctx.c(R.color.surface), 12f, ctx.c(R.color.line), 1f)
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ctx.c(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }

    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        text(ctx, label, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ripple(ctx, orange(ctx), 22f, 0x33FFFFFF)
            isClickable = true
            setOnClickListener { onClick() }
            minimumHeight = ctx.dp(44)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    fun secondaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        text(ctx, label, 15f, ctx.c(R.color.orange), true).apply {
            gravity = Gravity.CENTER
            background = roundBg(ctx, Color.TRANSPARENT, 22f, ctx.c(R.color.orange), 1f)
            isClickable = true
            setOnClickListener { onClick() }
            minimumHeight = ctx.dp(44)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    fun chip(ctx: Context, label: String, selected: Boolean): TextView =
        text(ctx, label, 13f, if (selected) Color.WHITE else ctx.c(R.color.text), selected).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = if (selected) roundBg(ctx, orange(ctx), 16f)
            else roundBg(ctx, ctx.c(R.color.surface_alt), 16f)
            minimumHeight = ctx.dp(32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = ctx.dp(8) }
            isClickable = true
        }

    /** 胶囊按钮（倍速 / 定时 / 排序）：浅底、圆角、可带前置小图标，文字走 tag 改写。 */
    fun pill(
        ctx: Context,
        label: String,
        @DrawableRes icon: Int = 0,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
            background = roundBg(ctx, ctx.c(R.color.surface_alt), 16f)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
                foreground = ripple(ctx, Color.TRANSPARENT, 16f)
            }
        }
        if (icon != 0) {
            val mark = icon(ctx, icon, ctx.c(R.color.text_dim), 12)
            (mark.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = ctx.dp(5)
            box.addView(mark)
        }
        val labelView = text(ctx, label, 12f, ctx.c(R.color.text), false, 1)
        box.addView(labelView)
        box.tag = labelView
        return box
    }

    /** 描边圆形图标按钮（目录行右侧的播放键）。 */
    fun circleIcon(
        ctx: Context,
        @DrawableRes res: Int,
        sizeDp: Int,
        tint: Int,
        desc: String? = null,
        onClick: (() -> Unit)? = null
    ): FrameLayout {
        val box = FrameLayout(ctx).apply {
            background = roundBg(ctx, Color.TRANSPARENT, sizeDp / 2f, ctx.c(R.color.line), 1f)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
                foreground = ripple(ctx, Color.TRANSPARENT, sizeDp / 2f)
            }
        }
        val mark = icon(ctx, res, tint, (sizeDp * 0.62f).toInt(), desc, null)
        mark.setPadding(0, 0, 0, 0)
        box.addView(mark, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        box.contentDescription = desc
        return box
    }

    /** 竖排「图标 + 文字」入口（播放页底部的目录 / 收藏 / 讲义）。 */
    fun iconAction(
        ctx: Context,
        @DrawableRes res: Int,
        label: String,
        tint: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            isClickable = true
            background = ripple(ctx, Color.TRANSPARENT, 12f)
            setOnClickListener { onClick() }
        }
        box.addView(icon(ctx, res, tint, 22), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        val labelView = text(ctx, label, 11f, ctx.c(R.color.text_dim), false, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(5), 0, 0)
        }
        box.addView(labelView)
        box.tag = labelView
        return box
    }

    fun tag(ctx: Context, label: String): TextView =
        text(ctx, label, 10f, ctx.c(R.color.orange), true).apply {
            setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            background = roundBg(ctx, Color.TRANSPARENT, 4f, ctx.c(R.color.orange), 1f)
        }

    /** 视频课标签：蓝色描边，与官网角标（橙色）区分。 */
    fun videoTag(ctx: Context): TextView =
        text(ctx, "视频课", 10f, ctx.c(R.color.tag_video), true).apply {
            setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            background = roundBg(ctx, Color.TRANSPARENT, 4f, ctx.c(R.color.tag_video), 1f)
        }

    fun spacer(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, 1).apply { weight = 1f }
    }

    fun topBar(ctx: Context, title: String, onBack: (() -> Unit)? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), 0, ctx.dp(10), 0)
            // 标题是 sp，系统字号放大后固定 52dp 会裁字，所以只给最小高度
            minimumHeight = ctx.dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (onBack != null) {
                addView(icon(ctx, R.drawable.ic_back, ctx.c(R.color.text), 24, "返回", onBack))
            } else {
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(10), 1)
                })
            }
            val titleView = text(ctx, title, 22f, ctx.c(R.color.text), true, 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = ctx.dp(6) }
            }
            addView(titleView)
            // 调用方改标题走 tag，不要 getChildAt(1) 硬编码位置
            tag = titleView
        }

    /** 内容加载完成后的入场：淡入 + 轻微上移，避免骨架/列表硬切。 */
    fun fadeInUp(view: View, delayMs: Long = 0L, offsetDp: Int = 10) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = view.context.dp(offsetDp).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun fadeIn(view: View, from: Float = 0f, durationMs: Long = 200) {
        view.animate().cancel()
        view.alpha = from
        view.animate().alpha(1f).setDuration(durationMs).start()
    }

    fun toast(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun formatSeconds(sec: Int): String {
        val m = sec / 60
        val h = m / 60
        return if (h > 0) String.format(Locale.US, "%d小时%d分", h, m % 60)
        else String.format(Locale.US, "%d 分钟", m)
    }

    fun formatMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun relativeTime(epochSec: Long): String {
        if (epochSec <= 0) return ""
        val diff = System.currentTimeMillis() / 1000 - epochSec
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60} 分钟前"
            diff < 86400 -> "${diff / 3600} 小时前"
            diff < 86400 * 2 -> "昨天"
            diff < 86400 * 30 -> "${diff / 86400} 天前"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = epochSec * 1000
                String.format(
                    Locale.US, "%d-%02d-%02d",
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                )
            }
        }
    }

    fun emptyState(
        ctx: Context,
        title: String,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(24), ctx.dp(48), ctx.dp(24), ctx.dp(48))
        addView(icon(ctx, R.drawable.ic_book, ctx.c(R.color.text_dim), 40))
        addView(text(ctx, title, 14f, ctx.c(R.color.text_dim), false, 3).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(12), 0, 0)
        })
        if (action != null && onAction != null) {
            addView(primaryButton(ctx, action, onAction).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(180), ctx.dp(40))
                    .apply { topMargin = ctx.dp(16) }
            })
        }
    }
}

/** 正文/列表在平板上不铺满，最多 720dp 居中。 */
class MaxWidthLayout(context: Context, private val maxDp: Int = 720) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = context.dp(maxDp.toFloat())
        val size = MeasureSpec.getSize(widthMeasureSpec)
        // UNSPECIFIED 时 size 可能是 0，此时只能按上限测量，否则子 View 全被压成 0 宽
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> max
            else -> if (size > 0) minOf(size, max) else max
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }
}

/** 3dp 细进度条（课程卡 / 详情头）。 */
class ProgressLine(
    context: Context,
    private val heightDp: Float = 3f,
    trackColor: Int? = null,
    fillColor: Int? = null
) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var pct: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    init {
        track.color = trackColor ?: context.c(R.color.line)
        fill.color = fillColor ?: context.c(R.color.orange)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            context.dp(heightDp)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (pct > 0f) {
            rect.set(0f, 0f, width * pct / 100f, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}

/**
 * 播放页进度条：细轨道 + 橙色已播段 + 末端圆点，拖动手感跟官方一致。
 * 只上报比例，时间换算交给调用方，避免和播放器状态互相覆盖。
 */
class SlidingSeek(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.c(R.color.line)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.c(R.color.orange)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.c(R.color.orange) }

    private val stroke = context.dp(3f)
    private val knobR = context.dp(5.5f)
    private var dragging = false

    var ratio: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!dragging) invalidate()
        }

    /** 拖动中/结束：true 表示手指按下，调用方别再刷新进度。 */
    var onSeekingChange: ((Boolean) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(28f))
    }

    private fun xOf(value: Float): Float {
        val span = (width - 2 * knobR).coerceAtLeast(0).toFloat()
        return knobR + span * value.coerceIn(0f, 1f)
    }

    private fun ratioAt(x: Float): Float {
        val span = (width - 2 * knobR).coerceAtLeast(1).toFloat()
        return ((x - knobR) / span).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val from = xOf(0f)
        val to = xOf(1f)
        val head = xOf(ratio)
        fill.strokeWidth = stroke.toFloat()
        track.strokeWidth = stroke.toFloat()
        canvas.drawLine(from, cy, to, cy, track)
        if (head > from) canvas.drawLine(from, cy, head, cy, fill)
        canvas.drawCircle(head, cy, knobR.toFloat(), knob)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                ratio = ratioAt(event.x)
                onSeekingChange?.invoke(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> if (dragging) ratio = ratioAt(event.x)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val target = ratioAt(event.x)
                    ratio = target
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onSeek?.invoke(target)
                    onSeekingChange?.invoke(false)
                }
            }
        }
        return true
    }
}
