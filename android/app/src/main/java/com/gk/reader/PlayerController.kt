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
import java.util.Locale
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

    /** 按分钟定时：到点时间戳，0 表示没有。 */
    private var sleepUntil = 0L

    /** 按集数定时：还剩几集（含正在播的这集），0 表示没有。 */
    private var sleepEpisodes = 0

    /** 已到点但勾了「播完整集声音再停止」，等这集播完再暂停。 */
    private var sleepHeld = false

    /** 当前按分钟定时选的是几分钟（弹窗高亮用，不随倒计时变）。 */
    private var sleepMinutesChosen = 0
    private var playJob: kotlinx.coroutines.Job? = null

    /** 独立闹钟：睡眠定时不能依赖 ticker，否则所有页面退到后台后就停摆了。 */
    private val sleepAlarm = Runnable {
        sleepUntil = 0
        sleepMinutesChosen = 0
        // 勾了「播完整集声音再停止」就别切断正在讲的这集，等换集时再停
        if (prefsOrNull()?.sleepFinishEpisode == true && controller?.isPlaying == true) {
            sleepHeld = true
        } else {
            controller?.pause()
        }
        notifyState()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickerOn = false
            notifyState()
        }
    }

    fun ensure(ctx: Context) {
        if (controller != null || connecting != null) return
        val app = ctx.applicationContext
        appContext = app
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = onDisconnected()
            })
            .buildAsync()
        connecting = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                connecting = null
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = notifyState()
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) =
                        onItemChanged(reason)
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

    /** 服务被杀后必须丢掉死连接，否则 ensure() 永远短路、播放指令全部静默失效。 */
    private fun onDisconnected() {
        controller = null
        connecting = null
        pending = null
        notifyState()
    }

    // ------------------------------------------------------------ 播放
    fun play(ctx: Context, queue: List<QueueItem>, startIndex: Int) {
        if (queue.isEmpty()) return
        val app = ctx.applicationContext
        ensure(app)
        val start = startIndex.coerceIn(0, queue.size - 1)
        val api = (app as GkApp).api
        // 换队列后「等本集播完」的挂起状态失效，避免刚点新的一讲就被暂停
        sleepHeld = false
        playJob?.cancel()
        playJob = scope.launch {
            // 同一门课所有讲次共用封面，按 URL 缓存，避免 N 份全尺寸字节进 MediaItem
            val artCache = HashMap<String, ByteArray?>()
            val items = queue.toList().map { item ->
                val mediaId = item.courseId + "|" + item.idx
                coverByMedia[mediaId] = item.coverUrl
                val art = artCache.getOrPut(item.coverUrl) { artwork(app, item.coverUrl) }
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
            val first = queue[start]
            val startMs = runCatching {
                api.progressCourse(first.courseId).lessons[first.idx]?.audioPos ?: 0L
            }.getOrDefault(0L)
            handler.post { startPlayback(items, start, startMs) }
        }
    }

    private suspend fun artwork(ctx: Context, coverUrl: String): ByteArray? {
        if (coverUrl.isEmpty()) return null
        val file = Covers.file(ctx, coverUrl) ?: return null
        if (file.length() > 2 * 1024 * 1024) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun startPlayback(items: List<MediaItem>, start: Int, startMs: Long) {
        val run = start@{
            val c = controller ?: return@start
            c.setMediaItems(items, start, if (startMs > 0) startMs else C.TIME_UNSET)
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

    fun setSpeed(ctx: Context, speed: Float) {
        if (appContext == null) appContext = ctx.applicationContext
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
    /** 按分钟定时；minutes <= 0 等同取消。两种定时互斥，设了时长就清掉集数。 */
    fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepAlarm)
        sleepHeld = false
        sleepEpisodes = 0
        sleepMinutesChosen = if (minutes > 0) minutes else 0
        sleepUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        if (sleepUntil > 0) {
            prefsOrNull()?.sleepMinutes = minutes
            handler.postDelayed(sleepAlarm, sleepUntil - System.currentTimeMillis())
        }
        notifyState()
    }

    /** 播完 count 集后停（含正在播的这集）；count <= 0 等同取消。 */
    fun setSleepEpisodes(count: Int) {
        handler.removeCallbacks(sleepAlarm)
        sleepUntil = 0
        sleepMinutesChosen = 0
        sleepHeld = false
        sleepEpisodes = count.coerceAtLeast(0)
        notifyState()
    }

    fun clearSleep() {
        handler.removeCallbacks(sleepAlarm)
        sleepUntil = 0
        sleepMinutesChosen = 0
        sleepEpisodes = 0
        sleepHeld = false
        notifyState()
    }

    fun sleepRemainingMs(): Long =
        if (sleepUntil > 0) (sleepUntil - System.currentTimeMillis()).coerceAtLeast(0) else 0

    /** 当前按分钟定时选中的分钟数，0 表示不是时长定时。 */
    fun sleepMinutes(): Int = sleepMinutesChosen

    /** 按集数定时还剩几集，0 表示不是集数定时。 */
    fun sleepEpisodesLeft(): Int = sleepEpisodes

    /** 上次用的分钟数，弹窗「上次定时」行展示。 */
    fun lastSleepMinutes(): Int = prefsOrNull()?.sleepMinutes ?: 15

    fun sleepFinishEpisode(): Boolean = prefsOrNull()?.sleepFinishEpisode == true

    fun setSleepFinishEpisode(on: Boolean) {
        prefsOrNull()?.sleepFinishEpisode = on
        notifyState()
    }

    /** 定时状态胶囊文案，null 表示没开定时。 */
    fun sleepLabel(): String? = when {
        sleepHeld || sleepEpisodes == 1 -> "本集后停"
        sleepEpisodes > 1 -> "剩 $sleepEpisodes 集"
        sleepUntil > 0 -> {
            val left = sleepRemainingMs()
            String.format(Locale.US, "定时 %d:%02d", left / 60_000, (left % 60_000) / 1000)
        }
        else -> null
    }

    /**
     * 换集回调：先处理「等本集播完再停」，再按集数扣减。
     * 只认自动切换，手动点下一讲不算播完一集。
     */
    private fun onItemChanged(reason: Int) {
        if (sleepHeld) {
            sleepHeld = false
            controller?.pause()
            notifyState()
            return
        }
        if (sleepEpisodes <= 0) return
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
        sleepEpisodes -= 1
        if (sleepEpisodes == 0) {
            // 下一讲已经起播，拉回开头再暂停，醒来还能接着听
            controller?.pause()
            controller?.seekTo(0)
        }
        notifyState()
    }

    // ------------------------------------------------------------ 订阅
    fun observe(listener: (PlayState) -> Unit): (PlayState) -> Unit {
        listeners.add(listener)
        notifyState()
        return listener
    }

    fun unobserve(listener: (PlayState) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stopTicker()
    }

    private fun startTicker() {
        if (tickerOn) return
        tickerOn = true
        handler.postDelayed(ticker, 500)
    }

    private fun stopTicker() {
        tickerOn = false
        handler.removeCallbacks(ticker)
    }

    private fun notifyState() {
        if (listeners.isEmpty()) {
            stopTicker()
            return
        }
        val s = state()
        for (listener in listeners) listener(s)
        // 只有播放/缓冲时进度才会变，暂停后别再 2Hz 空转唤醒主线程
        if (s.playing || s.buffering) startTicker() else stopTicker()
    }

    private fun prefs(): Prefs = (appContext as GkApp).prefs

    /** 定时可能在任何页面被触发，拿不到上下文时按「未开启」处理而不是崩。 */
    private fun prefsOrNull(): Prefs? = (appContext as? GkApp)?.prefs
}
