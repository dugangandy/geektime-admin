package com.gk.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var progress: ProgressBar
    private lateinit var rememberBox: CheckBox
    private var busy = false
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = (application as GkApp).api
        prefs = (application as GkApp).prefs
        setContentView(buildUi())
        if (api.hasSession()) silentSessionCheck()
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
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        content.addView(Ui.text(this, "极客时间", 24f, ctx.c(R.color.text), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(14), 0, ctx.dp(6))
        })
        content.addView(
            Ui.text(this, "本地课程库 · 随身学每一讲", 13f, ctx.c(R.color.text_dim)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ctx.dp(28))
            }
        )

        content.addView(fieldLabel("服务器地址"))
        address = editText(prefs.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(48)
            )
        }
        password = EditText(this).apply {
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
        pwRow.addView(Ui.text(this, "显示", 12f, ctx.c(R.color.orange), true).apply {
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            isClickable = true
            setOnClickListener {
                passwordVisible = !passwordVisible
                password.inputType = if (passwordVisible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                text = if (passwordVisible) "隐藏" else "显示"
                password.setSelection(password.text.length)
            }
        })
        content.addView(pwRow)

        rememberBox = CheckBox(this).apply {
            text = "记住登录（7 天免登录）"
            isChecked = prefs.remember
            setTextColor(ctx.c(R.color.text_dim))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            buttonTintList = android.content.res.ColorStateList.valueOf(ctx.c(R.color.orange))
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
        }
        content.addView(rememberBox)

        loginButton = Ui.primaryButton(this, "进 入 课 程 库") { doLogin() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)
            ).apply { topMargin = ctx.dp(10) }
        }
        content.addView(loginButton)

        progress = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24))
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = ctx.dp(10)
                }
        }
        content.addView(progress)

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
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        )
        isSingleLine = true
    }

    private fun addressHint(label: String, value: String): TextView =
        Ui.text(this, "· $label  $value", 12f, c(R.color.text_dim)).apply {
            setPadding(0, dp(5), 0, dp(5))
            isClickable = true
            setOnClickListener { address.setText(value) }
        }

    private fun setBusy(value: Boolean) {
        busy = value
        loginButton.alpha = if (value) 0.55f else 1f
        loginButton.isClickable = !value
        progress.visibility = if (value) ProgressBar.VISIBLE else ProgressBar.GONE
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
        if (busy) return
        val server = Api.normalize(address.text.toString())
        val pwd = password.text.toString()
        if (pwd.isEmpty()) {
            Ui.toast(this, "请输入访问密码")
            password.requestFocus()
            return
        }
        prefs.remember = rememberBox.isChecked
        setBusy(true)
        lifecycleScope.launch {
            try {
                api.login(server, pwd, rememberBox.isChecked)
                setBusy(false)
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: ApiException) {
                setBusy(false)
                when (e.code) {
                    "bad_password" -> pingLabel.text = "密码不正确，请重试"
                    "locked" -> pingLabel.text = "尝试次数过多，请 ${e.retryAfter} 秒后再试"
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

    private fun silentSessionCheck() {
        lifecycleScope.launch {
            try {
                api.session()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (_: AuthExpired) {
                // 会话过期，留在登录页
            } catch (_: Exception) {
                // 网络不通时仍允许手动登录重试
            }
        }
    }
}
