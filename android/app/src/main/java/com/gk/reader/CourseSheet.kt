package com.gk.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全屏课程目录弹层（底部升起），详情页与播放页共用。
 * 展示完整分组目录、当前讲高亮，点击任讲回调 onPick 由调用方决定跳转/切播。
 */
class CourseSheet private constructor(
    private val host: Context,
    private val courseId: String,
    private val courseName: String,
    private val currentIdx: Int,
    private val onPick: (LessonItem) -> Unit
) {

    companion object {
        fun show(
            host: Context,
            courseId: String,
            courseName: String,
            currentIdx: Int,
            onPick: (LessonItem) -> Unit
        ) {
            if (courseId.isEmpty()) {
                Ui.toast(host, "还没有可用的课程目录")
                return
            }
            CourseSheet(host, courseId, courseName, currentIdx, onPick).open()
        }
    }

    private val api get() = (host.applicationContext as GkApp).api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var dialog: BottomSheetDialog
    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sortLabel: TextView
    private var data: CourseDetail? = null
    private var descending = false
    private val collapsed = HashSet<Chapter>()
    private var anchor: View? = null
    private var loaded = false

    fun open() {
        val sheetH = (host.resources.displayMetrics.heightPixels * 0.8f).toInt()
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = topRoundBg(host.c(R.color.surface), 20f)
        }
        box.addView(View(host).apply {
            setBackgroundColor(host.c(R.color.line))
            layoutParams = LinearLayout.LayoutParams(host.dp(36), host.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = host.dp(10)
            }
        })

        val titleRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(18), host.dp(14), host.dp(14), host.dp(10))
        }
        titleRow.addView(Ui.text(host, "课程目录", 18f, host.c(R.color.text), true, 1))
        titleRow.addView(Ui.spacer(host))
        val sortPill = Ui.pill(host, "正序", R.drawable.ic_sort) { toggleSort() }
        sortLabel = sortPill.tag as TextView
        titleRow.addView(sortPill)
        box.addView(titleRow)

        box.addView(View(host).apply {
            setBackgroundColor(host.c(R.color.line))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1))
        })

        listBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        // 高度写死：BottomSheetDialog 会重写外层 LayoutParams，weight 会算出 0 高
        val listH = (sheetH - host.dp(112)).coerceAtLeast(host.dp(220))
        scroll = ScrollView(host).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH
            )
            addView(listBox)
        }
        box.addView(scroll)

        dialog = BottomSheetDialog(host).apply {
            setContentView(box)
            setOnDismissListener { scope.cancel() }
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setDimAmount(0.45f)
            window.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        // from() 在 LayoutParams 不是 CoordinatorLayout 那套时会抛异常，取不到就当默认展开
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        val behavior = sheet?.let { runCatching { BottomSheetBehavior.from(it) }.getOrNull() }
        if (behavior != null) {
            behavior.skipCollapsed = true
            behavior.peekHeight = sheetH
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        load()
    }

    private fun topRoundBg(color: Int, radiusDp: Float): GradientDrawable {
        val r = host.dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(color)
        }
    }

    private fun toggleSort() {
        descending = !descending
        sortLabel.text = if (descending) "倒序" else "正序"
        renderList()
    }

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(Ui.emptyState(host, if (courseName.isEmpty()) "正在加载目录…" else "正在加载《$courseName》目录…"))
        scope.launch {
            try {
                val detail = api.course(courseId)
                data = detail
                loaded = true
                renderList()
            } catch (e: Exception) {
                if (!loaded) {
                    listBox.removeAllViews()
                    listBox.addView(Ui.emptyState(host, "目录加载失败：${e.message}", "重试") { load() })
                }
            }
        }
    }

    private fun renderList() {
        val detail = data ?: return
        listBox.removeAllViews()
        anchor = null
        val chapters = if (descending) detail.chapters.reversed() else detail.chapters
        for (chapter in chapters) {
            val open = !collapsed.contains(chapter)
            listBox.addView(
                Syllabus.chapterBar(host, chapter.name, chapter.items.size, open) {
                    if (open) collapsed.add(chapter) else collapsed.remove(chapter)
                    renderList()
                }
            )
            if (!open) continue
            for (item in chapter.items) {
                val row = LessonRowView(host, item, onClick = { pick(item) }).apply {
                    active = item.idx == currentIdx
                }
                if (item.idx == currentIdx) anchor = row
                listBox.addView(row)
            }
        }
        val target = anchor
        if (target != null) {
            scroll.post {
                val y = (target.top - scroll.height / 3).coerceAtLeast(0)
                scroll.scrollTo(0, y)
            }
        }
    }

    private fun pick(item: LessonItem) {
        dialog.dismiss()
        onPick(item)
    }
}
