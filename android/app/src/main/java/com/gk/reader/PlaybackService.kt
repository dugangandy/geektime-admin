package com.gk.reader

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 后台音频：Media3 会话服务，通知/锁屏/耳机键由系统代理，播完自动下一讲。 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTickMs = 0L

    private val reporter = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as GkApp
        /* 播放器与 API 共用一个 OkHttp：CookieJar 会在每次请求时补上 gk_sess，
           Range 请求、换会话后都能直接生效（固定请求头做不到这一点）。 */
        val dataSource = OkHttpDataSource.Factory(app.api.client)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(open)
            .build()
        handler.postDelayed(reporter, 15_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(reporter)
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    private fun report() {
        val p = player ?: return
        if (!p.isPlaying) {
            lastTickMs = 0
            return
        }
        val id = p.currentMediaItem?.mediaId ?: return
        val parts = id.split('|')
        if (parts.size != 2) return
        val courseId = parts[0]
        val idx = parts[1].toIntOrNull() ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        val pct = pos * 100.0 / dur
        val now = System.currentTimeMillis()
        val listen = if (lastTickMs > 0) ((now - lastTickMs) / 1000).toInt() else 0
        lastTickMs = now
        val api = (application as GkApp).api
        scope.launch {
            runCatching {
                api.postProgress(courseId, idx, pct, pos.toDouble(), dur.toDouble(), false, listen)
            }
        }
    }
}
