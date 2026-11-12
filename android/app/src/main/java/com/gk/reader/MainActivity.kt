package com.gk.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java))
        }

        private const val TAB_COURSES = 0
        private const val TAB_STUDY = 1
        private const val TAB_ME = 2
    }

    private lateinit var prefs: Prefs
    private val api: Api get() = (application as GkApp).api

    private lateinit var content: FrameLayout
    private lateinit var mini: MiniPlayer
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>
    private var tab = TAB_COURSES

    private lateinit var coursesPage: LinearLayout
    private lateinit var libraryBox: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var sortLabel: TextView
    private lateinit var continueBox: LinearLayout
    private lateinit var listFrame: FrameLayout
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var listEmpty: LinearLayout
    private lateinit var searchScroll: ScrollView
    private lateinit var searchBox: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var studyBox: LinearLayout
    private lateinit var studyScroll: ScrollView
    private lateinit var mePage: LinearLayout
    private lateinit var cacheValue: TextView
    private lateinit var themeValue: TextView
    private lateinit var fontValue: TextView
    private lateinit var speedValue: TextView

    private val adapter by lazy {
        CourseAdapter(this, { openDetail(it) }, { openDetail(it, true) })
    }
    private var payload: CoursesPayload? = null
    private var filter = FilterState()
    private var loadingCourses = false
    private var loadingStudy = false
    private var firstResume = true
    private var searchJob: kotlinx.coroutines.Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as GkApp).prefs
        if (!api.hasSession()) {
            goLogin()
            return
        }
        buildUi()
        loadCourses(false)
        maybeAskNotifications()
    }

    override fun onResume() {
        super.onResume()
        // onCreate 无 session 时会 finish() 早退，mini 尚未初始化
        if (!::mini.isInitialized) return
        mini.refresh()
        if (firstResume) {
            firstResume = false
            return
        }
        when (tab) {
            TAB_COURSES -> if (!api.hasSession()) goLogin() else loadCourses(true)
            TAB_STUDY -> refreshStudy(true)
            TAB_ME -> refreshCacheSizes()
        }
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        searchJob?.cancel()
        super.onDestroy()
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    // ------------------------------------------------------------ 骨架
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
        }
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        coursesPage = buildCoursesPage()
        val study = buildStudyPage()
        mePage = buildMePage()
        content.addView(wrap(coursesPage))
        content.addView(wrap(study))
        content.addView(wrap(mePage))
        root.addView(content)

        mini = MiniPlayer(this).apply { attach() }
        root.addView(mini, MiniPlayer.layoutParams(this))

        root.addView(buildNav())
        setContentView(root)
        selectTab(TAB_COURSES, false)
    }

    private fun wrap(page: View): MaxWidthLayout {
        val wrap = MaxWidthLayout(this)
        wrap.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        wrap.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        return wrap
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bar.addView(View(this).apply {
            setBackgroundColor(c(R.color.line))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            )
        }
        bar.addView(row)
        val icons = arrayOf(R.drawable.ic_book, R.drawable.ic_stats, R.drawable.ic_person)
        val labels = arrayOf("课程", "学习", "我的")
        val iconViews = ArrayList<ImageView>()
        val labelViews = ArrayList<TextView>()
        for (i in 0 until 3) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                background = Ui.ripple(this@MainActivity, android.graphics.Color.TRANSPARENT, 0f)
                setOnClickListener { selectTab(i, true) }
            }
            // 图标和文字都占满整格、各自在格内居中，不依赖父布局 gravity，两者就不会错位
            val icon = Ui.icon(this, icons[i], c(R.color.text_dim), 23, labels[i]).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(23)
                )
            }
            val label = Ui.text(this, labels[i], 10f, c(R.color.text_dim), false, 1).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            item.addView(icon)
            item.addView(label)
            iconViews.add(icon)
            labelViews.add(label)
            row.addView(item)
        }
        navIcons = iconViews
        navLabels = labelViews
        return bar
    }

    private fun selectTab(index: Int, refresh: Boolean) {
        tab = index
        for (i in 0 until 3) {
            val on = i == index
            navIcons[i].setColorFilter(c(if (on) R.color.orange else R.color.text_dim))
            navLabels[i].setTextColor(c(if (on) R.color.orange else R.color.text_dim))
            navLabels[i].setTypeface(navLabels[i].typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        coursesPage.visibility = if (index == TAB_COURSES) View.VISIBLE else View.GONE
        studyScroll.visibility = if (index == TAB_STUDY) View.VISIBLE else View.GONE
        mePage.visibility = if (index == TAB_ME) View.VISIBLE else View.GONE
        if (!refresh) return
        when (index) {
            TAB_STUDY -> refreshStudy(false)
            TAB_ME -> refreshCacheSizes()
        }
    }

    // ------------------------------------------------------------ 课程库
    private fun buildCoursesPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(6), 0)
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        head.addView(Ui.text(this, "课程库", 22f, c(R.color.text), true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(Ui.icon(this, R.drawable.ic_search, c(R.color.text), 22, "搜索") {
            searchInput.requestFocus()
            showKeyboard(searchInput)
        })
        page.addView(head)

        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@MainActivity, context.c(R.color.surface_alt), 20f)
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(40)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(10)
            }
        }
        searchWrap.addView(Ui.icon(this, R.drawable.ic_search, c(R.color.text_dim), 17))
        searchInput = EditText(this).apply {
            hint = "搜索课程、讲次…"
            setHintTextColor(c(R.color.text_dim))
            setTextColor(c(R.color.text))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        searchWrap.addView(searchInput)
        val clear = Ui.icon(this, R.drawable.ic_close, c(R.color.text_dim), 16, "清空搜索") {
            searchInput.setText("")
        }
        clear.visibility = View.GONE
        searchWrap.addView(clear)
        page.addView(searchWrap)

        libraryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(libraryBox)

        val chipsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        chipsScroll.addView(chipsRow)
        libraryBox.addView(chipsScroll)

        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), dp(4))
        }
        listTitle = Ui.text(this, "全部课程", 14f, c(R.color.text), true, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sortLabel = Ui.text(this, "最近学习 ▾", 13f, c(R.color.text_dim), false, 1).apply {
            setPadding(dp(10), dp(8), dp(4), dp(8))
            isClickable = true
            setOnClickListener { showFilter() }
        }
        sortRow.addView(listTitle)
        sortRow.addView(sortLabel)
        libraryBox.addView(sortRow)

        continueBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        libraryBox.addView(continueBox)

        listFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        refresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Ui.orange(this@MainActivity))
            setOnRefreshListener { loadCourses(true) }
        }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(12))
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        refresh.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        listFrame.addView(refresh, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        listEmpty = Ui.emptyState(this, "正在加载课程…").apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        listFrame.addView(listEmpty)
        libraryBox.addView(listFrame)

        searchScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        searchScroll.addView(searchBox)
        page.addView(searchScroll)

        searchInput.doAfterTextChanged { text ->
            clear.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            searchHandler.removeCallbacksAndMessages(null)
            val q = text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                searchScroll.visibility = View.GONE
                libraryBox.visibility = View.VISIBLE
                searchBox.removeAllViews()
                return@doAfterTextChanged
            }
            searchHandler.postDelayed({ runSearch(q) }, 320)
        }
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                val q = searchInput.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) runSearch(q)
                true
            } else false
        }
        return page
    }

    private fun loadCourses(silent: Boolean) {
        if (loadingCourses) {
            refresh.isRefreshing = false
            return
        }
        loadingCourses = true
        if (!silent) showEmpty("正在加载课程…", null)
        lifecycleScope.launch {
            try {
                val data = api.courses()
                withContext(Dispatchers.Main) {
                    payload = data
                    buildChips()
                    renderCourses()
                    refresh.isRefreshing = false
                }
            } catch (e: AuthExpired) {
                goLogin()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    refresh.isRefreshing = false
                    if (payload == null) {
                        showEmpty(networkHint(e), "重试") { loadCourses(true) }
                    } else {
                        Ui.toast(this@MainActivity, "刷新失败，显示的是本地缓存")
                    }
                }
            } finally {
                loadingCourses = false
            }
        }
    }

    private fun buildChips() {
        val data = payload ?: return
        chipsRow.removeAllViews()
        chipsRow.addView(chip("全部", filter.category.isEmpty() && !filter.videoOnly) {
            filter = filter.copy(category = "", videoOnly = false)
            buildChips()
            renderCourses()
        })
        for (category in data.categories) {
            chipsRow.addView(chip(category.label, filter.category == category.key) {
                filter = filter.copy(category = category.key)
                buildChips()
                renderCourses()
            })
        }
        val videoCount = data.courses.count { it.videoCourse }
        if (videoCount > 0) {
            chipsRow.addView(chip("视频课（$videoCount）", filter.videoOnly) {
                filter = filter.copy(videoOnly = !filter.videoOnly)
                buildChips()
                renderCourses()
            })
        }
        sortLabel.text = when (filter.sort) {
            1 -> "课程名称 ▾"
            2 -> "讲数 ▾"
            else -> "最近学习 ▾"
        }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        Ui.chip(this, label, selected).apply { setOnClickListener { onClick() } }

    private fun renderCourses() {
        val data = payload ?: return
        val list = Filters.apply(data.courses, filter)
        adapter.submit(list)
        val scope = when {
            filter.videoOnly -> "视频课"
            filter.category.isEmpty() -> "全部课程"
            else -> data.categories.firstOrNull { it.key == filter.category }?.label ?: "课程"
        }
        listTitle.text = "$scope · ${list.size} 门"
        renderContinue(data.courses)
        if (list.isEmpty()) {
            showEmpty("没有符合条件的课程", "查看全部") {
                filter = FilterState()
                buildChips()
                renderCourses()
            }
        } else {
            listEmpty.visibility = View.GONE
            refresh.visibility = View.VISIBLE
        }
    }

    private fun renderContinue(courses: List<CourseSummary>) {
        continueBox.removeAllViews()
        val course = courses.filter { it.last != null }.maxByOrNull { it.last?.at ?: 0L } ?: return
        val last = course.last ?: return
        if (course.pct >= 100) return
        val card = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            background = Ui.roundBg(this@MainActivity, Ui.orangeSoft(this@MainActivity, 0x0C), 12f)
        }
        card.addView(Ui.text(this, "继续学习", 12f, c(R.color.orange), true, 1))
        card.addView(Ui.text(this, course.name, 15f, c(R.color.text), true, 1).apply {
            setPadding(0, dp(4), 0, dp(2))
        })
        card.addView(Ui.text(this, "${last.lesson} | ${last.title}", 12f, c(R.color.text_dim), false, 1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(ProgressLine(this, 3f).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f)
            pct = course.pct.toFloat()
        })
        row.addView(Ui.text(this, "${course.pct}%", 12f, c(R.color.orange), true, 1).apply {
            setPadding(dp(8), 0, 0, 0)
        })
        val go = Ui.text(this, "继续", 13f, Color.WHITE, true, 1).apply {
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@MainActivity, Ui.orange(this@MainActivity), 14f)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(28)).apply { marginStart = dp(12) }
            isClickable = true
            setOnClickListener { openReader(course.id, last.lesson, course.name, last.title) }
        }
        row.addView(go)
        card.addView(row)
        card.setOnClickListener { openReader(course.id, last.lesson, course.name, last.title) }
        continueBox.addView(card)
    }

    private fun showEmpty(message: String, action: String?, onAction: (() -> Unit)? = null) {
        listEmpty.removeAllViews()
        listEmpty.addView(Ui.emptyState(this, message, action, onAction).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        listEmpty.visibility = View.VISIBLE
        refresh.visibility = View.GONE
    }

    private fun showFilter() {
        val data = payload ?: return
        FilterSheet.show(
            this, data.categories, filter, data.courses.size,
            countOf = { Filters.apply(data.courses, it).size }
        ) { state ->
            filter = state
            buildChips()
            renderCourses()
        }
    }

    // ------------------------------------------------------------ 搜索
    private fun runSearch(query: String) {
        searchJob?.cancel()
        libraryBox.visibility = View.GONE
        searchScroll.visibility = View.VISIBLE
        searchBox.removeAllViews()
        searchBox.addView(Ui.text(this, "正在搜索…", 13f, c(R.color.text_dim), false, 1).apply {
            setPadding(0, dp(16), 0, 0)
        })
        searchJob = lifecycleScope.launch {
            try {
                val result = api.search(query)
                withContext(Dispatchers.Main) {
                    if (isFinishing) return@withContext
                    renderSearch(query, result)
                }
            } catch (e: AuthExpired) {
                goLogin()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    searchBox.removeAllViews()
                    searchBox.addView(
                        Ui.emptyState(this@MainActivity, networkHint(e), "重试") { runSearch(query) }
                    )
                }
            }
        }
    }

    private fun renderSearch(query: String, result: SearchPayload) {
        searchBox.removeAllViews()
        if (result.courses.isEmpty() && result.lessons.isEmpty()) {
            searchBox.addView(Ui.emptyState(this, "没有找到“$query”相关课程或讲次"))
            return
        }
        if (result.courses.isNotEmpty()) {
            searchBox.addView(sectionTitle("课程 · ${result.courses.size}"))
            for (course in result.courses) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener { openDetail(course) }
                }
                row.addView(CoverView(this, 44, 58, 6f).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(58))
                    load(course.coverUrl, course.name)
                })
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(Ui.text(this, course.name, 15f, c(R.color.text), true, 2))
                if (course.videoCourse) {
                    col.addView(Ui.videoTag(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                    })
                }
                col.addView(Ui.text(this, course.author, 12f, c(R.color.text_dim), false, 1).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(col)
                searchBox.addView(row)
                searchBox.addView(Ui.divider(this))
            }
        }
        if (result.lessons.isNotEmpty()) {
            searchBox.addView(sectionTitle("讲次 · ${result.lessons.size}"))
            for (hit in result.lessons) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    isClickable = true
                    setOnClickListener { openReader(hit.courseId, hit.idx, hit.courseName, hit.label) }
                }
                row.addView(Ui.text(this, "${hit.idx} | ${hit.label}", 14f, c(R.color.text), false, 2))
                row.addView(Ui.text(this, hit.courseName, 12f, c(R.color.text_dim), false, 1).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                searchBox.addView(row)
                searchBox.addView(Ui.divider(this))
            }
        }
    }

    // ------------------------------------------------------------ 学习
    private fun buildStudyPage(): ScrollView {
        studyScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        studyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        studyScroll.addView(studyBox)
        return studyScroll
    }

    private fun refreshStudy(force: Boolean) {
        if (loadingStudy && !force) return
        loadingStudy = true
        if (studyBox.childCount == 0) {
            studyBox.addView(Ui.text(this, "学习", 22f, c(R.color.text), true, 1).apply {
                setPadding(0, dp(14), 0, dp(16))
            })
            studyBox.addView(Ui.text(this, "正在加载学习记录…", 13f, c(R.color.text_dim), false, 1))
        }
        lifecycleScope.launch {
            try {
                val stats = api.stats()
                val courses = payload?.courses ?: api.courses().also { payload = it }.courses
                withContext(Dispatchers.Main) {
                    renderStudy(stats, courses)
                }
            } catch (e: AuthExpired) {
                goLogin()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    studyBox.removeAllViews()
                    studyBox.addView(Ui.text(this@MainActivity, "学习", 22f, c(R.color.text), true, 1).apply {
                        setPadding(0, dp(14), 0, 0)
                    })
                    studyBox.addView(
                        Ui.emptyState(this@MainActivity, networkHint(e), "重试") { refreshStudy(true) }
                    )
                }
            } finally {
                loadingStudy = false
            }
        }
    }

    private fun renderStudy(stats: Stats, courses: List<CourseSummary>) {
        studyBox.removeAllViews()
        studyBox.addView(Ui.text(this, "学习", 22f, c(R.color.text), true, 1).apply {
            setPadding(0, dp(14), 0, dp(16))
        })

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(statCard("已学完", "${stats.lessonsRead}", "讲", 1f, 8))
        val (timeValue, timeUnit) = studyTime(stats.minutes)
        cards.addView(statCard("累计学习", timeValue, timeUnit, 1f, 8))
        cards.addView(statCard("连续学习", "${stats.streak}", "天", 1f, 0))
        studyBox.addView(cards)

        val cont = stats.continueRows.firstOrNull()
        if (cont != null) {
            studyBox.addView(sectionTitle("继续学习"))
            val card = Ui.card(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                isClickable = true
                setOnClickListener { openReader(cont.id, cont.lesson, cont.name, cont.title) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CoverView(this, 50, 66, 7f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(66))
                load(cont.coverUrl, cont.name)
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(Ui.text(this, cont.name, 15f, c(R.color.text), true, 1))
            col.addView(Ui.text(this, "${cont.lesson} | ${cont.title}", 12f, c(R.color.text_dim), false, 2).apply {
                setPadding(0, dp(4), 0, dp(6))
            })
            col.addView(ProgressLine(this, 3f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
                )
                pct = cont.pct.toFloat()
            })
            row.addView(col)
            card.addView(row)
            studyBox.addView(card)
        }

        if (stats.recent.isNotEmpty()) {
            studyBox.addView(sectionTitle("最近学习"))
            for (hit in stats.recent.take(10)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener { openReader(hit.id, hit.lesson, hit.name, hit.title) }
                }
                row.addView(CoverView(this, 34, 46, 5f).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(46))
                    load(hit.coverUrl, hit.name)
                })
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(Ui.text(this, hit.title, 14f, c(R.color.text), false, 1))
                col.addView(Ui.text(this, hit.name, 12f, c(R.color.text_dim), false, 1).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(col)
                row.addView(Ui.text(this, Ui.relativeTime(hit.at), 11f, c(R.color.text_dim), false, 1))
                studyBox.addView(row)
                studyBox.addView(Ui.divider(this))
            }
        }

        val started = courses.filter { it.started }.sortedByDescending { it.last?.at ?: 0L }
        if (started.isNotEmpty()) {
            studyBox.addView(sectionTitle("全部课程进度"))
            for (course in started) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(9), 0, dp(9))
                    isClickable = true
                    setOnClickListener { openDetail(course) }
                }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(Ui.text(this, course.name, 14f, c(R.color.text), false, 1).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                line.addView(Ui.text(this, "${course.pct}%", 12f, c(R.color.orange), true, 1))
                row.addView(line)
                row.addView(ProgressLine(this, 3f).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
                    ).apply { topMargin = dp(6) }
                    pct = course.pct.toFloat()
                })
                studyBox.addView(row)
            }
        }

        if (stats.lessonsRead == 0 && stats.recent.isEmpty()) {
            studyBox.addView(Ui.emptyState(this, "还没有学习记录", "去课程库挑一门") {
                selectTab(TAB_COURSES, false)
            })
        }
    }

    private fun statCard(label: String, value: String, unit: String, weight: Float, endMargin: Int): LinearLayout {
        val card = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = dp(endMargin)
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        line.addView(Ui.text(this, value, 22f, c(R.color.text), true, 1))
        if (unit.isNotEmpty()) {
            line.addView(Ui.text(this, unit, 11f, c(R.color.text_dim), false, 1).apply {
                setPadding(dp(3), 0, 0, dp(3))
            })
        }
        card.addView(line)
        card.addView(Ui.text(this, label, 11f, c(R.color.text_dim), false, 1).apply {
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    /** 超过一小时就换单位，否则卡片上光一个「1.5」看不出是分钟还是小时。 */
    private fun studyTime(minutes: Int): Pair<String, String> =
        if (minutes >= 60) String.format(Locale.US, "%.1f", minutes / 60f) to "小时"
        else "$minutes" to "分钟"

    // ------------------------------------------------------------ 我的
    private fun buildMePage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        page.addView(Ui.text(this, "我的", 22f, c(R.color.text), true, 1).apply {
            setPadding(0, dp(14), 0, dp(16))
        })

        val server = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            setOnClickListener { editServer() }
        }
        server.addView(Ui.text(this, "服务器", 12f, c(R.color.text_dim), false, 1))
        server.addView(Ui.text(this, prefs.serverUrl.replace("http://", "").replace("https://", ""),
            15f, c(R.color.text), true, 1).apply { setPadding(0, dp(4), 0, 0) })
        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        serverRow.addView(Ui.text(this, "已登录 · 本地课程库", 12f, c(R.color.text_dim), false, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        serverRow.addView(Ui.text(this, "退出登录", 12f, c(R.color.red), true, 1).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { confirmLogout() }
        })
        server.addView(serverRow)
        page.addView(server)

        val settings = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        themeValue = settingRow(settings, "外观", themeLabel(), null) { showThemeDialog() }
        fontValue = Ui.text(this, "${prefs.fontSp}sp", 13f, c(R.color.text_dim), false, 1)
        settings.addView(rowFrame("正文字号", fontValue))
        val seek = SeekBar(this).apply {
            max = 7
            progress = (prefs.fontSp - 15).coerceIn(0, 7)
            setPadding(dp(2), 0, dp(2), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    fontValue.text = "${progress + 15}sp"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    prefs.fontSp = (bar?.progress ?: 0) + 15
                }
            })
        }
        settings.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
        ))
        speedValue = settingRow(settings, "播放倍速", formatSpeed(prefs.speed), null) { showSpeedDialog() }
        settings.addView(Ui.divider(this))
        cacheValue = settingRow(settings, "封面 / PDF 缓存", "计算中…", "清理") { confirmClearCache() }
        settings.addView(Ui.divider(this))
        settingRow(settings, "关于", "v${appVersion()}", "›") { showAbout() }
        page.addView(settings)

        val tip = Ui.text(this, "仅访问你配置的服务器，不收集任何使用数据。", 11f, c(R.color.text_dim), false, 2).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        page.addView(tip)
        return page
    }

    private fun rowFrame(label: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(Ui.text(this@MainActivity, label, 14f, c(R.color.text), false, 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
        }

    private fun settingRow(
        parent: LinearLayout,
        label: String,
        value: String,
        action: String?,
        onClick: (() -> Unit)? = null
    ): TextView {
        val valueView = Ui.text(this, value, 13f, c(R.color.text_dim), false, 1).apply {
            setPadding(dp(8), 0, 0, 0)
        }
        val row = rowFrame(label, valueView)
        if (action != null) {
            row.addView(Ui.text(this, action, 12f, c(R.color.orange), false, 1).apply {
                setPadding(dp(10), dp(2), 0, dp(2))
            })
        }
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }
        parent.addView(row)
        return valueView
    }

    private fun themeLabel(): String = when (prefs.themeMode) {
        1 -> "浅色"
        2 -> "深色"
        else -> "跟随系统"
    }

    private fun showThemeDialog() {
        val labels = arrayOf("跟随系统", "浅色", "深色")
        AlertDialog.Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(labels, prefs.themeMode) { dialog, which ->
                prefs.themeMode = which
                themeValue.text = labels[which]
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) String.format(Locale.US, "%.1fx", speed)
        else String.format(Locale.US, "%sx", speed.toString().trimEnd('0').trimEnd('.'))

    private fun showSpeedDialog() {
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = values.map { formatSpeed(it) }.toTypedArray()
        val current = values.indexOfFirst { it == prefs.speed }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PlayerController.setSpeed(this@MainActivity, values[which])
                speedValue.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshCacheSizes() {
        lifecycleScope.launch {
            val covers = withContext(Dispatchers.IO) { Covers.size(this@MainActivity) }
            val pdf = withContext(Dispatchers.IO) { Pdf.size(this@MainActivity) }
            cacheValue.text = "${mb(covers)} / ${mb(pdf)}"
            speedValue.text = formatSpeed(prefs.speed)
            themeValue.text = themeLabel()
            fontValue.text = "${prefs.fontSp}sp"
        }
    }

    private fun mb(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB"
        else String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("清理缓存")
            .setMessage("将删除已下载的封面与 PDF 原稿，不影响学习进度。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    Covers.clear(this@MainActivity)
                    Pdf.clear(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        refreshCacheSizes()
                        Ui.toast(this@MainActivity, "缓存已清理")
                    }
                }
            }
            .show()
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setText(prefs.serverUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(wrap)
            .setMessage("修改后需要重新登录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val next = Api.normalize(input.text?.toString().orEmpty())
                prefs.serverUrl = next
                api.cookieStore.clear()
                goLogin()
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后需要重新输入访问密码。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                lifecycleScope.launch {
                    api.logout()
                    withContext(Dispatchers.Main) { goLogin() }
                }
            }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(
                "极客时间 · 本地课程库\n版本 v${appVersion()}\n\n" +
                    "课程内容来自你自己配置的服务器，正文由本地 App 排版展示。\n" +
                    "音频播放使用 AndroidX Media3，网络请求使用 OkHttp。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    // ------------------------------------------------------------ 跳转
    private fun openDetail(course: CourseSummary, continueLast: Boolean = false) {
        if (continueLast && course.last != null) {
            openReader(course.id, course.last.lesson, course.name, course.last.title)
            return
        }
        DetailActivity.open(this, course.id, course.name)
    }

    private fun openReader(courseId: String, idx: Int, courseName: String, title: String) {
        ReaderActivity.open(this, courseId, idx, courseName, title)
    }

    private fun goLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun networkHint(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "找不到服务器，请检查地址与网络"
        is java.net.SocketTimeoutException -> "连接超时，请确认 PC 上的 serve.py 正在运行"
        is java.net.ConnectException -> "无法连接服务器，请检查地址或 SSH 隧道"
        is ApiException -> e.message
        else -> "加载失败：${e.message ?: e.javaClass.simpleName}"
    }

    private fun showKeyboard(view: EditText) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun sectionTitle(label: String): TextView =
        Ui.text(this, label, 15f, c(R.color.text), true, 1).apply {
            setPadding(0, dp(16), 0, dp(6))
        }
}
