package com.gk.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 定时关闭弹层：按时长 / 按集数两种定时，外加「播完整集声音再停止」。
 * 只负责界面，状态全部在 PlayerController，操作后立即生效并回调 onChanged。
 */
class SleepSheet private constructor(
    private val host: Context,
    private val onChanged: () -> Unit
) {

    companion object {
        fun show(host: Context, onChanged: () -> Unit) {
            SleepSheet(host, onChanged).open()
        }
    }

    private lateinit var dialog: BottomSheetDialog
    private var switchRow: SwitchCompat? = null
    private var switchLabel: TextView? = null
    private var timeChips: List<Pair<Int, TextView>> = emptyList()
    private var episodeChips: List<Pair<Int, TextView>> = emptyList()
    private var finishDot: FrameLayout? = null
    private var finishMark: View? = null
    private var finishLabel: TextView? = null

    /** 刷新界面时回写控件会触发监听，用它挡住递归。 */
    private var syncing = false

    private fun open() {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = topRoundBg(host.c(R.color.surface_alt), 20f)
        }

        val titleRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(20), host.dp(12), host.dp(10), host.dp(6))
        }
        titleRow.addView(Ui.text(host, "定时", 20f, host.c(R.color.text), true, 1))
        titleRow.addView(Ui.spacer(host))
        titleRow.addView(
            Ui.icon(host, R.drawable.ic_close, host.c(R.color.text_dim), 20, "关闭") {
                dialog.dismiss()
            }
        )
        box.addView(titleRow)

        box.addView(timeCard(), cardParams(topMargin = 6))
        box.addView(startRow(), cardParams(topMargin = 12))
        box.addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(1, host.dp(20))
        })

        dialog = BottomSheetDialog(host).apply {
            setContentView(box)
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setDimAmount(0.45f)
            window.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        val behavior = sheet?.let { runCatching { BottomSheetBehavior.from(it) }.getOrNull() }
        if (behavior != null) {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        refresh()
    }

    // ------------------------------------------------------------ 卡片一：时长 / 集数
    private fun timeCard(): LinearLayout {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(host, host.c(R.color.surface), 14f)
        }

        val lastRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14))
        }
        switchLabel = Ui.text(host, "", 15f, host.c(R.color.text), false, 1)
        lastRow.addView(switchLabel)
        lastRow.addView(Ui.spacer(host))
        switchRow = SwitchCompat(host).apply {
            setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                if (checked) PlayerController.setSleep(PlayerController.lastSleepMinutes())
                else PlayerController.clearSleep()
                refresh()
                onChanged()
            }
        }
        lastRow.addView(switchRow)
        card.addView(lastRow)

        card.addView(Ui.divider(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)
            )
        })

        // 按时间 + 播完整集开关
        val timeHead = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(16), host.dp(12), host.dp(12), 0)
        }
        timeHead.addView(Ui.text(host, "按时间", 13f, host.c(R.color.text_dim), false, 1))
        timeHead.addView(Ui.spacer(host))
        timeHead.addView(finishToggle())
        card.addView(timeHead)

        val timeRow = FlowBox(host, host.dp(10), host.dp(10)).apply {
            setPadding(host.dp(16), host.dp(10), host.dp(16), 0)
        }
        timeChips = intArrayOf(15, 30, 60, 90).map { minutes ->
            val view = chip("${minutes}分") { pickMinutes(minutes) }
            timeRow.addView(view)
            minutes to view
        } + (0 to chip("自定义") { askCustom() }.also { timeRow.addView(it) })
        card.addView(timeRow)

        card.addView(Ui.text(host, "按集数", 13f, host.c(R.color.text_dim), false, 1).apply {
            setPadding(host.dp(16), host.dp(16), host.dp(16), 0)
        })

        val episodeRow = FlowBox(host, host.dp(10), host.dp(10)).apply {
            setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(16))
        }
        episodeChips = intArrayOf(1, 2, 3, 5).map { count ->
            val view = chip(if (count == 1) "播完本集" else "播完${count}集") {
                PlayerController.setSleepEpisodes(count)
                onChanged()
                dialog.dismiss()
            }
            episodeRow.addView(view)
            count to view
        }
        card.addView(episodeRow)
        return card
    }

    /** 「播完整集声音再停止」：到点后不切断正在讲的这集。 */
    private fun finishToggle(): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(host.dp(6), host.dp(4), host.dp(4), host.dp(4))
            foreground = Ui.ripple(host, Color.TRANSPARENT, 8f)
            setOnClickListener {
                PlayerController.setSleepFinishEpisode(!PlayerController.sleepFinishEpisode())
                refresh()
                onChanged()
            }
        }
        val dot = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(host.dp(16), host.dp(16))
        }
        val mark = Ui.icon(host, R.drawable.ic_check, Color.WHITE, 11).apply {
            setPadding(0, 0, 0, 0)
        }
        dot.addView(mark, FrameLayout.LayoutParams(host.dp(11), host.dp(11), Gravity.CENTER))
        finishDot = dot
        finishMark = mark
        row.addView(dot)
        val label = Ui.text(host, "播完整集声音再停止", 12f, host.c(R.color.text_dim), false, 1)
            .apply { setPadding(host.dp(6), 0, 0, 0) }
        finishLabel = label
        row.addView(label)
        row.contentDescription = "播完整集声音再停止"
        return row
    }

    // ------------------------------------------------------------ 卡片二：定时启播
    private fun startRow(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.roundBg(host, host.c(R.color.surface), 14f)
        setPadding(host.dp(16), host.dp(16), host.dp(14), host.dp(16))
        isClickable = true
        foreground = Ui.ripple(host, Color.TRANSPARENT, 14f)
        setOnClickListener { Ui.toast(host, "定时启播暂未支持") }
        addView(Ui.text(host, "设置定时启播", 15f, host.c(R.color.text), false, 1))
        addView(Ui.spacer(host))
        addView(
            Ui.text(host, "定时声音陪伴，告别无趣生活", 12f, host.c(R.color.text_dim), false, 1)
                .apply { setPadding(0, 0, host.dp(2), 0) }
        )
        addView(Ui.icon(host, R.drawable.ic_chevron_right, host.c(R.color.text_dim), 14))
    }

    // ------------------------------------------------------------ 交互
    private fun pickMinutes(minutes: Int) {
        PlayerController.setSleep(minutes)
        onChanged()
        dialog.dismiss()
    }

    private fun askCustom() {
        val input = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "分钟（1-600）"
            setText(PlayerController.lastSleepMinutes().toString())
            setSelection(text.length)
        }
        val wrap = FrameLayout(host).apply {
            setPadding(host.dp(20), 0, host.dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(host)
            .setTitle("自定义定时")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                PlayerController.setSleep(minutes.coerceIn(1, 600))
                onChanged()
                dialog.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------ 渲染
    private fun refresh() {
        syncing = true
        val minutes = PlayerController.sleepMinutes()
        val episodes = PlayerController.sleepEpisodesLeft()
        switchLabel?.text =
            if (minutes > 0) "定时 ${minutes}分钟"
            else "上次定时 ${PlayerController.lastSleepMinutes()}分钟"
        switchRow?.isChecked = minutes > 0
        // value == 0 是「自定义」：选了预设之外的分钟数时高亮它
        val presets = intArrayOf(15, 30, 60, 90)
        timeChips.forEach { (value, view) ->
            paintChip(
                view,
                if (value == 0) minutes > 0 && minutes !in presets else minutes == value
            )
        }
        episodeChips.forEach { (value, view) -> paintChip(view, episodes == value) }

        val finish = PlayerController.sleepFinishEpisode()
        finishDot?.background = if (finish) Ui.roundBg(host, Ui.orange(host), 8f)
        else Ui.roundBg(host, Color.TRANSPARENT, 8f, host.c(R.color.line), 1f)
        finishMark?.visibility = if (finish) View.VISIBLE else View.GONE
        finishLabel?.setTextColor(host.c(if (finish) R.color.text else R.color.text_dim))
        syncing = false
    }

    private fun paintChip(view: TextView, selected: Boolean) {
        view.background = Ui.roundBg(
            host,
            if (selected) Ui.orange(host) else host.c(R.color.surface_alt),
            18f
        )
        view.setTextColor(if (selected) Color.WHITE else host.c(R.color.text))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.isSelected = selected
    }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        Ui.text(host, label, 13f, host.c(R.color.text), false, 1).apply {
            gravity = Gravity.CENTER
            setPadding(host.dp(16), host.dp(9), host.dp(16), host.dp(9))
            background = Ui.roundBg(host, host.c(R.color.surface_alt), 18f)
            foreground = Ui.ripple(host, Color.TRANSPARENT, 18f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun cardParams(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = host.dp(16)
            rightMargin = host.dp(16)
            this.topMargin = host.dp(topMargin)
        }

    private fun topRoundBg(color: Int, radiusDp: Float): GradientDrawable {
        val r = host.dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(color)
        }
    }
}

/** 简易流式布局：窄屏上胶囊自动换行，不会被裁掉。 */
private class FlowBox(
    context: Context,
    private val hGap: Int,
    private val vGap: Int
) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(0)
        val childSpec = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
        var lineW = 0
        var lineH = 0
        var totalH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, childSpec, heightMeasureSpec)
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (lineW > 0 && lineW + hGap + w > avail) {
                totalH += lineH + vGap
                lineW = w
                lineH = h
            } else {
                lineW = if (lineW == 0) w else lineW + hGap + w
                lineH = maxOf(lineH, h)
            }
        }
        totalH += lineH
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            totalH + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > paddingLeft && x + w > paddingLeft + avail) {
                x = paddingLeft
                y += lineH + vGap
                lineH = 0
            }
            child.layout(x, y, x + w, y + h)
            x += w + hGap
            lineH = maxOf(lineH, h)
        }
    }
}
