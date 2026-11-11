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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Covers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_BYTES = 8L * 1024 * 1024

    fun dir(ctx: Context): File = File(ctx.cacheDir, "covers").apply { if (!exists()) mkdirs() }

    fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun file(ctx: Context, url: String): File? {
        if (url.isEmpty()) return null
        val target = File(dir(ctx), key(url))
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val tmp = File(dir(ctx), target.name + ".part")
        try {
            val api = (ctx.applicationContext as GkApp).api
            val request = Request.Builder().url(api.absolute(url)).build()
            api.client.newCall(request).execute().use { res ->
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

    fun load(ctx: Context, url: String, name: String, view: CoverView) {
        view.bindPlaceholder(name)
        if (url.isEmpty()) return
        scope.launch {
            val file = file(ctx, url) ?: return@launch
            val bitmap = decode(file, view.targetPx())
            if (bitmap != null) {
                view.post { view.show(bitmap, url) }
            }
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

    init {
        clipToOutline = true
        background = Ui.roundBg(context, 0x14F66121, cornerDp)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        initial.gravity = Gravity.CENTER
        initial.setTextColor(Ui.ORANGE)
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
        Covers.load(context, url, name, this)
    }

    fun show(bitmap: Bitmap, url: String) {
        if (url != currentUrl) return
        image.setImageBitmap(bitmap)
        image.visibility = VISIBLE
        initial.visibility = GONE
    }
}
