package com.gk.reader

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
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
        // 定时上报会漏掉每讲最后一段；播完/暂停时立刻结清，听完才能真正标记已学完
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) report(force = true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) report(force = true)
            }
        })

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
        // 交给 Media3：它用 isPlaybackOngoing() 判断，比 playWhenReady 准确
        // （缓冲/IDLE 时 playWhenReady 仍为 true，会留下带通知的空前台服务）
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(reporter)
        report(force = true)
        // 先释放 session 再释放 player；session?.run{player} 会解析到 session.getPlayer()
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    private fun report(force: Boolean = false) {
        val p = player ?: return
        val playing = p.isPlaying
        if (!playing && !force) {
            lastTickMs = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        val listen = if (lastTickMs > 0) ((now - lastTickMs) / 1000).toInt().coerceIn(0, 15) else 0
        lastTickMs = if (playing) now else 0
        reportItem(p.currentMediaItem?.mediaId, p.currentPosition, p.duration, listen)
    }

    private fun reportItem(mediaId: String?, pos: Long, dur: Long, listen: Int) {
        val parts = mediaId?.split('|') ?: return
        if (parts.size != 2) return
        val idx = parts[1].toIntOrNull() ?: return
        if (dur <= 0) return
        val pct = (pos * 100.0 / dur).coerceIn(0.0, 100.0)
        val api = (application as GkApp).api
        scope.launch {
            runCatching {
                api.postAudioProgress(parts[0], idx, pct, pos, dur, listen,
                    pct >= Api.READ_THRESHOLD)
            }
        }
    }
}
