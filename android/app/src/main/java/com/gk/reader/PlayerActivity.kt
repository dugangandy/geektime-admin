package com.gk.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private lateinit var cover: CoverView
    private lateinit var titleText: TextView
    private lateinit var courseText: TextView
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var toggle: ImageView
    private lateinit var speedButton: TextView
    private lateinit var sleepButton: LinearLayout
    private lateinit var sleepLabel: TextView
    private lateinit var nextRow: TextView
    private lateinit var emptyBox: LinearLayout
    private lateinit var mainBox: LinearLayout

    private var dragging = false
    private var observer: ((PlayState) -> Unit)? = null
    private var lastCover = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerController.ensure(this)
        buildUi()
        observer = PlayerController.observe { render(it) }
        render(PlayerController.state())
    }

    override fun onDestroy() {
        observer?.let { PlayerController.unobserve(it) }
        observer = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            setPadding(dp(20), 0, dp(20), dp(16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        top.addView(Ui.icon(this, R.drawable.ic_chevron_down, c(R.color.text), 26, "收起") { finish() })
        top.addView(Ui.spacer(this))
        speedButton = Ui.text(this, "1.0x", 15f, c(R.color.text), true, 1).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showSpeed() }
        }
        top.addView(speedButton)
        root.addView(top)

        mainBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val side = minOf(dp(260).toFloat(), resources.displayMetrics.widthPixels - dp(64).toFloat())
        val sideDp = (side / resources.displayMetrics.density).toInt().coerceAtLeast(140)
        mainBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.5f)
        })
        cover = CoverView(this, sideDp, sideDp, 18f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sideDp), dp(sideDp))
        }
        mainBox.addView(cover)

        titleText = Ui.text(this, "正在播放", 18f, c(R.color.text), true, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(6))
        }
        mainBox.addView(titleText)
        courseText = Ui.text(this, "", 13f, c(R.color.text_dim), false, 1).apply {
            gravity = Gravity.CENTER
        }
        mainBox.addView(courseText)

        mainBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.35f)
        })

        val seekWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        seek = SeekBar(this).apply {
            max = 1000
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = PlayerController.state().durationMs
                        if (dur > 0) posText.text = Ui.formatMs(dur * progress / 1000)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    dragging = false
                    val dur = PlayerController.state().durationMs
                    if (dur > 0) PlayerController.seekTo(dur * (bar?.progress ?: 0) / 1000)
                }
            })
        }
        seekWrap.addView(seek)
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        posText = Ui.text(this, "00:00", 11f, c(R.color.text_dim), false, 1)
        durText = Ui.text(this, "00:00", 11f, c(R.color.text_dim), false, 1)
        times.addView(posText)
        times.addView(Ui.spacer(this))
        times.addView(durText)
        seekWrap.addView(times)
        mainBox.addView(seekWrap)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }
        controls.addView(Ui.icon(this, R.drawable.ic_replay15, c(R.color.text), 30, "后退 15 秒") {
            PlayerController.seekBy(-15_000)
        })
        val playBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                marginStart = dp(40)
                marginEnd = dp(40)
            }
        }
        toggle = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = Ui.roundBg(this@PlayerActivity, Ui.orange(this@PlayerActivity), 34f)
            isClickable = true
            setOnClickListener { PlayerController.toggle() }
            contentDescription = "播放/暂停"
        }
        playBox.addView(toggle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        controls.addView(playBox)
        controls.addView(Ui.icon(this, R.drawable.ic_forward15, c(R.color.text), 30, "前进 15 秒") {
            PlayerController.seekBy(15_000)
        })
        mainBox.addView(controls)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        sleepButton = actionButton("定时", R.drawable.ic_timer) { showSleep() }
        sleepLabel = sleepButton.tag as TextView
        bottom.addView(sleepButton)
        bottom.addView(actionButton("队列", R.drawable.ic_queue) { showQueue() })
        val prevNext = actionButton("下一讲", R.drawable.ic_next) {
            PlayerController.next()
        }
        bottom.addView(prevNext)
        mainBox.addView(bottom)

        nextRow = Ui.text(this, "", 12f, c(R.color.text_dim), false, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { PlayerController.next() }
        }
        mainBox.addView(nextRow)
        root.addView(mainBox)

        emptyBox = Ui.emptyState(this, "还没有播放内容", "去课程库") {
            MainActivity.start(this)
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(emptyBox)
        setContentView(root)
    }

    private fun actionButton(label: String, icon: Int, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(Ui.icon(this, icon, c(R.color.text_dim), 16))
        val text = Ui.text(this, label, 12f, c(R.color.text), false, 1).apply {
            setPadding(dp(5), 0, 0, 0)
        }
        box.addView(text)
        box.tag = text
        return box
    }

    private fun render(state: PlayState) {
        if (!state.hasItem) {
            mainBox.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
            return
        }
        mainBox.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        titleText.text = state.title.ifEmpty { "正在播放" }
        courseText.text = when {
            state.error != null -> "播放失败，检查网络或登录状态"
            state.buffering -> "缓冲中… · ${state.course}"
            else -> state.course
        }
        courseText.setTextColor(c(if (state.error != null) R.color.red else R.color.text_dim))
        toggle.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = if (state.playing) "暂停" else "播放"
        val speedText = formatSpeed(state.speed)
        speedButton.text = speedText
        if (state.coverUrl != lastCover) {
            lastCover = state.coverUrl
            cover.load(state.coverUrl, state.course.ifEmpty { state.title })
        }
        val dur = state.durationMs
        durText.text = if (dur > 0) Ui.formatMs(dur) else "00:00"
        if (!dragging) {
            posText.text = Ui.formatMs(state.positionMs)
            seek.progress = if (dur > 0) {
                (state.positionMs * 1000 / dur).toInt().coerceIn(0, 1000)
            } else 0
        }
        val sleep = PlayerController.sleepRemainingMs()
        sleepLabel.text =
            if (sleep > 0) String.format(Locale.US, "定时 %d:%02d", sleep / 60_000, (sleep % 60_000) / 1000)
            else "定时"
        val titles = PlayerController.queueTitles()
        val index = PlayerController.currentIndex()
        nextRow.text = if (index + 1 in titles.indices) "下一讲 · ${titles[index + 1]}" else ""
    }

    private fun formatSpeed(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

    private fun showSpeed() {
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = values.map { formatSpeed(it) }.toTypedArray()
        val actual = PlayerController.state().speed
        // 非预设倍速时返回 -1（不勾选），别用 coerceAtLeast(0) 假装选中了 0.75x
        val current = values.indexOfFirst { kotlin.math.abs(it - actual) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PlayerController.setSpeed(this@PlayerActivity, values[which])
                speedButton.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showSleep() {
        val labels = arrayOf("关闭", "15 分钟", "30 分钟", "45 分钟", "60 分钟")
        val values = intArrayOf(0, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle("定时关闭")
            .setItems(labels) { dialog, which ->
                PlayerController.setSleep(values[which])
                if (values[which] == 0) PlayerController.clearSleep()
                dialog.dismiss()
                render(PlayerController.state())
            }
            .show()
    }

    private fun showQueue() {
        val titles = PlayerController.queueTitles()
        if (titles.isEmpty()) {
            Ui.toast(this, "播放队列为空")
            return
        }
        val index = PlayerController.currentIndex()
        val labels = titles.mapIndexed { i, t ->
            (if (i == index) "▶ " else "${i + 1}. ") + t
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("播放队列（${titles.size}）")
            .setItems(labels) { dialog, which ->
                PlayerController.jumpTo(which)
                dialog.dismiss()
            }
            .show()
    }
}
