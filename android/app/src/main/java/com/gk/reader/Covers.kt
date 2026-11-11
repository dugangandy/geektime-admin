package com.gk.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Covers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_BYTES = 8L * 1024 * 1024

    fun dir(ctx: Context): File = File(ctx.cacheDir, "covers").apply { if (!exists()) mkdirs() }

    /** 键必须含 origin：不同服务器下发的相对路径完全相同，否则会串文件。 */
    fun key(ctx: Context, url: String): String {
        val api = (ctx.applicationContext as GkApp).api
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(api.absolute(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun file(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext null
        val target = File(dir(ctx), key(ctx, url))
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val api = (ctx.applicationContext as GkApp).api
        val call = api.client.newCall(Request.Builder().url(api.absolute(url)).build())
        // 协程取消必须连带取消请求：execute() 是阻塞的，光取消 Job 会一直吊到 30s 超时
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            download(call, target)
        } finally {
            handle?.dispose()
        }
    }

    private fun download(call: Call, target: File): File? {
        // 同一封面可能被多个 View 并发拉取，临时名必须唯一，否则字节交错后落成损坏缓存
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                var size = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > MAX_BYTES) return null
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (tmp.length() <= 0) return null
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            return target
        } catch (_: Exception) {
            return null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun size(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { if (it.name.endsWith(".part")) 0L else it.length() } ?: 0L

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** 只回传 Bitmap，Job 交给调用方随 View 生命周期取消，避免进程级 scope 吊住整棵 View 树。 */
    fun load(ctx: Context, url: String, targetPx: Int, onReady: (Bitmap) -> Unit): Job? {
        if (url.isEmpty()) return null
        val app = ctx.applicationContext
        return scope.launch {
            val file = file(app, url) ?: return@launch
            val bitmap = decode(file, targetPx) ?: return@launch
            withContext(Dispatchers.Main) { onReady(bitmap) }
        }
    }

    private fun decode(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > target) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}

/** 圆角封面：加载前显示「书名首字 + 橙色淡底」，失败保持占位而不是白块。 */
class CoverView(
    context: Context,
    private val widthDp: Int,
    private val heightDp: Int,
    private val cornerDp: Float = 10f
) : FrameLayout(context) {
    private val image = ImageView(context)
    private val initial = TextView(context)
    private var currentUrl = ""
    private var loadJob: Job? = null

    init {
        clipToOutline = true
        background = Ui.roundBg(context, Ui.orangeSoft(context, 0x14), cornerDp)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        initial.gravity = Gravity.CENTER
        initial.setTextColor(Ui.orange(context))
        initial.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (widthDp >= 72) 26f else 16f)
        initial.setTypeface(initial.typeface, android.graphics.Typeface.BOLD)
        addView(initial, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            context.dp(widthDp), context.dp(heightDp)
        )
    }

    fun targetPx(): Int = context.dp(maxOf(widthDp, heightDp).toFloat())

    fun bindPlaceholder(name: String) {
        image.setImageDrawable(null)
        image.visibility = GONE
        initial.visibility = VISIBLE
        initial.text = name.trim().take(1).ifEmpty { "书" }
    }

    fun load(url: String, name: String) {
        currentUrl = url
        loadJob?.cancel()
        bindPlaceholder(name)
        loadJob = Covers.load(context, url, targetPx()) { show(it, url) }
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        loadJob = null
        super.onDetachedFromWindow()
    }

    fun show(bitmap: Bitmap, url: String) {
        if (url != currentUrl) return
        image.setImageBitmap(bitmap)
        image.visibility = VISIBLE
        initial.visibility = GONE
    }
}
