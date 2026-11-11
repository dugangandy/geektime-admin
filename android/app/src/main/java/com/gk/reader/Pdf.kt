package com.gk.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

object Pdf {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(ctx: android.content.Context): File =
        File(ctx.cacheDir, "pdf").apply { if (!exists()) mkdirs() }

    fun size(ctx: android.content.Context): Long =
        dir(ctx).listFiles()?.sumOf { if (it.name.endsWith(".part")) 0L else it.length() } ?: 0L

    fun clear(ctx: android.content.Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun cached(ctx: android.content.Context, url: String): File? {
        val f = File(dir(ctx), Covers.key(url) + ".pdf")
        return if (f.length() > 0) f else null
    }

    /** 下载（带 Cookie）→ FileProvider → 系统 PDF 查看器。 */
    fun open(activity: Activity, url: String) {
        if (url.isEmpty()) return
        cached(activity, url)?.let {
            view(activity, it)
            return
        }
        val ctx = activity
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("正在下载 PDF 原稿")
            .setView(ProgressBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(6)
                )
                setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
                isIndeterminate = true
            })
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .create()
        dialog.show()

        scope.launch {
            val file = download(ctx, url)
            withContext(Dispatchers.Main) {
                if (dialog.isShowing) dialog.dismiss()
                if (file == null) {
                    Ui.toast(ctx, "PDF 下载失败，请检查网络或登录状态")
                    return@withContext
                }
                view(activity, file)
            }
        }
    }

    private fun download(ctx: android.content.Context, url: String): File? {
        val tmp = File(dir(ctx), Covers.key(url) + ".part")
        val target = File(dir(ctx), Covers.key(url) + ".pdf")
        return try {
            val api = (ctx.applicationContext as GkApp).api
            val request = Request.Builder().url(api.absolute(url)).build()
            api.client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body ?: return null
                body.byteStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
            if (tmp.length() <= 0) return null
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            target
        } catch (_: Exception) {
            null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun view(activity: Activity, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Ui.toast(activity, "没有可打开 PDF 的应用")
        }
    }
}
