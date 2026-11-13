package com.gk.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var prefs: Prefs
    private lateinit var address: EditText
    private lateinit var password: EditText
    private lateinit var pingLabel: TextView
    private lateinit var loginButton: TextView
    private lateinit var loading: DropLoadingView
    private lateinit var rememberBox: CheckBox
    private lateinit var visibleToggle: TextView
    private var busy = false
    private var passwordVisible = false
    private var lockUntil = 0L
    /** splash 阶段表单还没建，onSaveInstanceState 里的控件引用都还不能碰。 */
    private var formBuilt = false
    private var splashAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lockTick = object : Runnable {
        override fun run() {
            val left = lockLeftSec()
            if (left <= 0) {
                pingLabel.setTextColor(c(R.color.text_dim))
                pingLabel.text = "可以重试了"
                updateEnabled()
                return
            }
            pingLabel.text = "尝试次数过多，请 $left 秒后再试"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = (application as GkApp).api
        prefs = (application as GkApp).prefs
        // 已登录时先显示品牌启动页再静默校验：直接铺登录表单会「闪一下服务器连接页」
        if (savedInstanceState == null && api.hasSession()) {
            splashAt = android.os.SystemClock.elapsedRealtime()
            setContentView(buildSplash())
            silentSessionCheck()
        } else {
            showForm(savedInstanceState)
        }
    }

    private fun showForm(state: Bundle?) {
        formBuilt = true
        val view = buildUi()
        setContentView(view)
        if (state != null) {
            passwordVisible = state.getBoolean(STATE_VISIBLE, false)
            pingLabel.text = state.getString(STATE_HINT).orEmpty()
            lockUntil = state.getLong(STATE_LOCK, 0L)
            applyPasswordVisibility()
            if (lockLeftSec() > 0) {
                pingLabel.setTextColor(c(R.color.red))
                updateEnabled()
                handler.post(lockTick)
            }
        }
        Ui.fadeIn(view, 0.85f, 180)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!formBuilt) return
        outState.putBoolean(STATE_VISIBLE, passwordVisible)
        outState.putString(STATE_HINT, pingLabel.text.toString())
        outState.putLong(STATE_LOCK, lockUntil)
    }

    override fun onDestroy() {
        handler.removeCallbacks(lockTick)
        super.onDestroy()
    }

    private fun lockLeftSec(): Int =
        ((lockUntil - android.os.SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

    /** 冷启动占位：品牌页 + 转圈，会话校验期间显示，避免先铺登录表单。 */
    private fun buildSplash(): ViewGroup {
        val box = FrameLayout(this).apply { setBackgroundColor(c(R.color.bg)) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        })
        col.addView(Ui.text(this, "极客时间", 20f, c(R.color.text), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        col.addView(Ui.text(this, "正在连接课程库…", 12f, c(R.color.text_dim)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(DropLoadingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(22)
            }
        })
        box.addView(col)
        Ui.fadeIn(box, 0.7f, 180)
        return box
    }

    private fun buildUi(): ViewGroup {
        val ctx = this
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ctx.c(R.color.bg))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(26), ctx.dp(36), ctx.dp(26), ctx.dp(32))
        }
        val wrap = MaxWidthLayout(this, 480).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val center = FrameLayout(this).apply {
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        wrap.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        center.addView(wrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        content.addView(Ui.text(this, "极客听书", 24f, ctx.c(R.color.text), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(14), 0, ctx.dp(6))
        })
        val slogan = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(6), 0, ctx.dp(2))
        }
        slogan.addView(Ui.text(this, "听一课，", 14f, ctx.c(R.color.text)))
        slogan.addView(Ui.text(this, "即刻成长", 14f, ctx.c(R.color.brand_mint), true))
        content.addView(slogan)
        content.addView(
            Ui.text(this, "本地课程库 · 随身学每一讲", 13f, ctx.c(R.color.text_dim)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ctx.dp(28))
            }
        )

        content.addView(fieldLabel("服务器地址"))
        address = editText(prefs.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        address.id = R.id.login_address
        content.addView(address)

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(10))
        }
        pingLabel = Ui.text(this, "", 12f, ctx.c(R.color.text_dim)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        testRow.addView(pingLabel)
        testRow.addView(Ui.text(this, "测试连接", 12f, ctx.c(R.color.orange), true).apply {
            setPadding(ctx.dp(8), ctx.dp(6), 0, ctx.dp(6))
            isClickable = true
            setOnClickListener { testConnection() }
        })
        content.addView(testRow)

        content.addView(fieldLabel("访问密码"))
        val pwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(ctx, ctx.c(R.color.surface_alt), 10f)
            setPadding(ctx.dp(12), 0, ctx.dp(6), 0)
            minimumHeight = ctx.dp(48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        password = EditText(this).apply {
            id = R.id.login_password
            hint = "输入口令"
            setTextColor(ctx.c(R.color.text))
            setHintTextColor(ctx.c(R.color.text_dim))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doLogin()
                    true
                } else false
            }
        }
        pwRow.addView(password)
        visibleToggle = Ui.text(this, "显示", 12f, ctx.c(R.color.orange), true).apply {
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            isClickable = true
            setOnClickListener {
                passwordVisible = !passwordVisible
                applyPasswordVisibility()
            }
        }
        pwRow.addView(visibleToggle)
        content.addView(pwRow)

        rememberBox = CheckBox(this).apply {
            id = R.id.login_remember
            text = "记住登录（7 天免登录）"
            isChecked = prefs.remember
            setTextColor(ctx.c(R.color.text_dim))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            buttonTintList = android.content.res.ColorStateList.valueOf(ctx.c(R.color.orange))
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
        }
        content.addView(rememberBox)

        loginButton = Ui.primaryButton(this, "进 入 课 程 库") { doLogin() }.apply {
            minimumHeight = ctx.dp(46)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(10) }
        }
        content.addView(loginButton)

        loading = DropLoadingView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56))
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = ctx.dp(10)
                }
        }
        content.addView(loading)

        content.addView(Ui.text(this, "常用地址", 12f, ctx.c(R.color.text_dim)).apply {
            setPadding(0, ctx.dp(26), 0, ctx.dp(6))
        })
        content.addView(addressHint("局域网", Prefs.LAN_HINT))
        content.addView(addressHint("公网隧道", Prefs.DEFAULT_SERVER))

        content.addView(Ui.text(
            this,
            "只连接你填写的这台服务器；课程正文与音频不会上传到任何第三方。",
            11f, ctx.c(R.color.text_dim)
        ).apply { setPadding(0, ctx.dp(22), 0, 0) })

        address.setText(prefs.serverUrl)
        if (!api.hasSession()) address.requestFocus()
        return scroll
    }

    private fun fieldLabel(label: String): TextView =
        Ui.text(this, label, 12f, c(R.color.text_dim), true).apply {
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun editText(value: String, type: Int): EditText = EditText(this).apply {
        setText(value)
        inputType = type
        setTextColor(c(R.color.text))
        setHintTextColor(c(R.color.text_dim))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        background = Ui.roundBg(this@LoginActivity, c(R.color.surface_alt), 10f)
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isSingleLine = true
    }

    private fun addressHint(label: String, value: String): TextView =
        Ui.text(this, "· $label  $value", 12f, c(R.color.text_dim)).apply {
            setPadding(0, dp(5), 0, dp(5))
            isClickable = true
            setOnClickListener { address.setText(value) }
        }

    private fun applyPasswordVisibility() {
        password.inputType = if (passwordVisible)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        visibleToggle.text = if (passwordVisible) "隐藏" else "显示"
        password.setSelection(password.text.length)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateEnabled()
    }

    private fun updateEnabled() {
        val blocked = busy || lockLeftSec() > 0
        loginButton.alpha = if (blocked) 0.55f else 1f
        loginButton.isClickable = !blocked
        loading.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun testConnection() {
        if (busy) return
        val server = Api.normalize(address.text.toString())
        pingLabel.setTextColor(c(R.color.text_dim))
        pingLabel.text = "正在连接…"
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            try {
                val ping = api.ping(server)
                val ms = System.currentTimeMillis() - started
                pingLabel.setTextColor(c(R.color.green))
                pingLabel.text = "✓ 可连接 · ${ping.courses} 门课程 · ${ms}ms"
            } catch (e: Exception) {
                pingLabel.setTextColor(c(R.color.red))
                pingLabel.text = "✕ 地址不可达，检查地址、防火墙或 SSH 隧道"
            }
        }
    }

    private fun doLogin() {
        if (busy || lockLeftSec() > 0) return
        val server = Api.normalize(address.text.toString())
        val pwd = password.text.toString()
        if (pwd.isEmpty()) {
            Ui.toast(this, "请输入访问密码")
            password.requestFocus()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                api.login(server, pwd, rememberBox.isChecked)
                // 只有登录成功才落盘，否则失败的尝试会把上次的选择改掉
                prefs.remember = rememberBox.isChecked
                setBusy(false)
                goMain()
            } catch (e: ApiException) {
                setBusy(false)
                when (e.code) {
                    "bad_password" -> pingLabel.text = "密码不正确，请重试"
                    "locked" -> startLockout(e.retryAfter)
                    "forbidden" -> pingLabel.text = "服务器拒绝了这次登录，请升级 PC 端 serve.py"
                    else -> pingLabel.text = e.message
                }
                pingLabel.setTextColor(c(R.color.red))
            } catch (e: Exception) {
                setBusy(false)
                pingLabel.setTextColor(c(R.color.red))
                pingLabel.text = "无法连接：${e.message ?: "网络错误"}"
            }
        }
    }

    /** 锁定时给出倒计时并禁用按钮，否则用户只能对着「请 60 秒后再试」反复点。 */
    private fun startLockout(retryAfterSec: Int) {
        lockUntil = android.os.SystemClock.elapsedRealtime() +
            retryAfterSec.coerceAtLeast(1) * 1000L
        handler.removeCallbacks(lockTick)
        handler.post(lockTick)
    }

    private fun silentSessionCheck() {
        lifecycleScope.launch {
            val ok = try {
                api.session()
                true
            } catch (_: Exception) {
                // 会话过期 / 网络不通：都退回登录页，允许手动重试
                false
            }
            if (!ok) {
                showForm(null)
                return@launch
            }
            // 至少露一下品牌页，别让极快的返回把启动页闪成白屏
            val shown = android.os.SystemClock.elapsedRealtime() - splashAt
            handler.postDelayed({ goMain() }, (220L - shown).coerceAtLeast(0L))
        }
    }

    private fun goMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        private const val STATE_VISIBLE = "state_visible"
        private const val STATE_HINT = "state_hint"
        private const val STATE_LOCK = "state_lock"
    }
}
