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
class ProgressLine(context: Context, private val heightDp: Float = 3f) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var pct: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    init {
        track.color = context.c(R.color.line)
        fill.color = context.c(R.color.orange)
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
