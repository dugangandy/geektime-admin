package com.gk.reader

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class PlayState(
    val hasItem: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val title: String = "",
    val course: String = "",
    val coverUrl: String = "",
    val courseId: String = "",
    val lessonIdx: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null
)

/** 进程级播放控制：各页面只跟这里打交道，MediaSession 由 PlaybackService 持有。 */
object PlayerController {
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private var appContext: Context? = null
    private var pending: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<(PlayState) -> Unit>()
    private val coverByMedia = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tickerOn = false
    private var sleepUntil = 0L
    private var sleepNotified = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerOn) return
            if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) {
                sleepUntil = 0
                sleepNotified = false
                controller?.pause()
            }
            notifyState()
            handler.postDelayed(this, 500)
        }
    }

    fun ensure(ctx: Context) {
        if (controller != null || connecting != null) return
        val app = ctx.applicationContext
        appContext = app
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        connecting = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = notifyState()
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        notifyState()
                    }
                })
                c.setPlaybackSpeed((app as GkApp).prefs.speed)
                pending?.invoke()
                pending = null
                notifyState()
            } catch (_: Exception) {
                connecting = null
            }
        }, ContextCompat.getMainExecutor(app))
    }

    // ------------------------------------------------------------ 播放
    fun play(ctx: Context, queue: List<QueueItem>, startIndex: Int) {
        if (queue.isEmpty()) return
        val app = ctx.applicationContext
        ensure(app)
        val start = startIndex.coerceIn(0, queue.size - 1)
        val api = (app as GkApp).api
        scope.launch {
            val items = queue.toList().map { item ->
                val mediaId = item.courseId + "|" + item.idx
                coverByMedia[mediaId] = item.coverUrl
                val art = artwork(app, item.coverUrl)
                val meta = MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.courseName)
                    .setAlbumTitle(item.courseName)
                    .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    .build()
                MediaItem.Builder()
                    .setUri(api.absolute(item.audioUrl))
                    .setMediaId(mediaId)
                    .setMediaMetadata(meta)
                    .build()
            }
            handler.post { startPlayback(items, start) }
        }
    }

    private fun artwork(ctx: Context, coverUrl: String): ByteArray? {
        if (coverUrl.isEmpty()) return null
        val file = Covers.file(ctx, coverUrl) ?: return null
        if (file.length() > 2 * 1024 * 1024) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun startPlayback(items: List<MediaItem>, start: Int) {
        val run = start@{
            val c = controller ?: return@start
            c.setMediaItems(items, start, C.TIME_UNSET)
            c.setPlaybackSpeed(prefs().speed)
            c.prepare()
            c.play()
            notifyState()
        }
        if (controller == null) {
            pending = run
            appContext?.let { ensure(it) }
        } else {
            run()
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause()
        else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        val max = if (c.duration > 0) c.duration else Long.MAX_VALUE
        c.seekTo(ms.coerceIn(0, max))
        notifyState()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val max = if (c.duration > 0) c.duration else Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, max))
        notifyState()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun prev() {
        controller?.seekToPreviousMediaItem()
    }

    fun setSpeed(speed: Float) {
        prefs().speed = speed
        controller?.setPlaybackSpeed(speed)
        notifyState()
    }

    fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        notifyState()
    }

    // ------------------------------------------------------------ 状态
    fun state(): PlayState {
        val c = controller
        if (c == null) {
            val fallback = (appContext as? GkApp)?.prefs?.speed ?: 1f
            return PlayState(speed = fallback)
        }
        val item = c.currentMediaItem
        val mediaId = item?.mediaId.orEmpty()
        return PlayState(
            hasItem = item != null,
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            course = item?.mediaMetadata?.artist?.toString().orEmpty(),
            coverUrl = coverByMedia[mediaId].orEmpty(),
            courseId = mediaId.substringBefore('|'),
            lessonIdx = mediaId.substringAfter('|', "").toIntOrNull() ?: -1,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = if (c.duration > 0) c.duration else 0,
            speed = c.playbackParameters.speed,
            error = c.playerError?.message
        )
    }

    fun queueSize(): Int = controller?.mediaItemCount ?: 0

    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: 0

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
        }
    }

    fun queueTitles(): List<String> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map {
            c.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty()
        }
    }

    // ------------------------------------------------------------ 定时关闭
    fun setSleep(minutes: Int) {
        sleepUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        sleepNotified = false
        notifyState()
    }

    fun clearSleep() {
        sleepUntil = 0
        sleepNotified = true
    }

    fun sleepRemainingMs(): Long =
        if (sleepUntil > 0) (sleepUntil - System.currentTimeMillis()).coerceAtLeast(0) else 0

    // ------------------------------------------------------------ 订阅
    fun observe(listener: (PlayState) -> Unit): (PlayState) -> Unit {
        listeners.add(listener)
        startTicker()
        listener(state())
        return listener
    }

    fun unobserve(listener: (PlayState) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stopTicker()
    }

    private fun startTicker() {
        if (tickerOn) return
        tickerOn = true
        handler.post(ticker)
    }

    private fun stopTicker() {
        tickerOn = false
        handler.removeCallbacks(ticker)
    }

    private fun notifyState() {
        if (listeners.isEmpty()) return
        val s = state()
        for (listener in listeners) listener(s)
    }

    private fun prefs(): Prefs = (appContext as GkApp).prefs
}
