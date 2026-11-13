package com.gk.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
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
        val f = File(dir(ctx), Covers.key(ctx, url) + ".pdf")
        if (f.length() <= 0) return null
        // 旧版本可能把 HTML 错误页当 PDF 缓存过，命中脏缓存就丢掉重下
        if (!looksLikePdf(f)) {
            f.delete()
            return null
        }
        return f
    }

    /** 下载（带 Cookie）→ FileProvider → 系统 PDF 查看器。 */
    fun open(activity: AppCompatActivity, url: String) {
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
            .setNegativeButton("取消") { d, _ -> d.cancel() }
            .setCancelable(true)
            .create()
        dialog.show()

        val app = activity.applicationContext
        val job = scope.launch {
            val result = download(app, url)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (dialog.isShowing) dialog.dismiss()
                val file = result.file
                if (file == null) {
                    Ui.toast(activity, result.error ?: "PDF 下载失败，请检查网络或登录状态")
                    return@withContext
                }
                view(activity, file)
            }
        }
        // 进程级 scope + 阻塞下载：用户取消或 Activity 销毁都要真取消，否则吊住 Activity 到超时并 WindowLeaked
        dialog.setOnCancelListener { job.cancel() }
        activity.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_DESTROY) return
                job.cancel()
                if (dialog.isShowing) dialog.dismiss()
                source.lifecycle.removeObserver(this)
            }
        })
    }

    /** 下载结果：成功带文件，失败带一句能直接展示的原因。 */
    private class Download(val file: File?, val error: String? = null)

    private suspend fun download(ctx: android.content.Context, url: String): Download =
        withContext(Dispatchers.IO) {
            val target = File(dir(ctx), Covers.key(ctx, url) + ".pdf")
            val api = (ctx.applicationContext as GkApp).api
            val call = api.client.newCall(Request.Builder().url(api.absolute(url)).build())
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                save(call, target)
            } finally {
                handle?.dispose()
            }
        }

    private fun save(call: Call, target: File): Download {
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
        return try {
            var contentType = ""
            call.execute().use { res ->
                if (!res.isSuccessful) return Download(null, "PDF 下载失败：HTTP ${res.code}")
                val body = res.body ?: return Download(null, "PDF 下载失败：响应为空")
                contentType = body.contentType()?.toString().orEmpty()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
            if (tmp.length() <= 0) return Download(null, "PDF 下载失败：响应为空")
            // 登录过期 / 路径写错时服务端会回一个 HTML 页面，之前会当 PDF 存下来再交给浏览器，
            // 表现成「点讲义跳到了网页」。这里按文件头判定，别让非 PDF 进缓存。
            if (!looksLikePdf(tmp)) {
                val hint = if (contentType.contains("html", true)) "登录可能已失效，请重新登录" else "服务端返回的不是 PDF"
                return Download(null, hint)
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return Download(null, "PDF 保存失败")
            Download(target)
        } catch (_: Exception) {
            Download(null, "PDF 下载失败，请检查网络")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun looksLikePdf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(5)
            input.read(head) == 5 && String(head, Charsets.US_ASCII) == "%PDF-"
        }
    } catch (_: Exception) {
        false
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
