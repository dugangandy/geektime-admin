package com.gk.reader

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 原生视频播放页。
 *
 * 只做「一讲一个视频」：ExoPlayer + TextureView（宽高比由 AspectLayout 控制，
 * 参考 XinghaiFm 的 VideoPlayerActivity），底部一条控制栏（播放/暂停、进度、
 * 时间、倍速、全屏），点画面显隐控制栏，播放中 4 秒自动隐藏。
 *
 * 进度写进音频字段（audio_pos / audio_dur / listen_sec），与播放器共用一份，
 * 不动阅读器的滚动位置 pos；下次进来从这个位置续播。
 */
class VideoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_COURSE = "courseId"
        private const val EXTRA_IDX = "idx"
        private const val EXTRA_URL = "videoUrl"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUB = "sub"
        private const val EXTRA_START = "startMs"
        private const val EXTRA_VIDEO_W = "videoWidth"
        private const val EXTRA_VIDEO_H = "videoHeight"

        fun open(
            ctx: Context,
            courseId: String,
            idx: Int,
            videoUrl: String,
            title: String,
            sub: String,
            startMs: Long,
            videoWidth: Int = 0,
            videoHeight: Int = 0
        ) {
            ctx.startActivity(
                Intent(ctx, VideoActivity::class.java)
                    .putExtra(EXTRA_COURSE, courseId)
                    .putExtra(EXTRA_IDX, idx)
                    .putExtra(EXTRA_URL, videoUrl)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SUB, sub)
                    .putExtra(EXTRA_START, startMs)
                    .putExtra(EXTRA_VIDEO_W, videoWidth)
                    .putExtra(EXTRA_VIDEO_H, videoHeight)
            )
        }
    }

    private val api: Api get() = (application as GkApp).api
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var videoFrame: AspectLayout
    private lateinit var tex: TextureView
    private lateinit var controls: View
    private lateinit var loading: DropLoadingView
    private lateinit var playBtn: ImageView
    private lateinit var seek: SeekBar
    private lateinit var curText: TextView
    private lateinit var durText: TextView
    private lateinit var speedBtn: TextView
    private lateinit var sizeBtn: TextView

    private var player: ExoPlayer? = null
    private var courseId = ""
    private var idx = 0
    private var startMs = 0L
    private var dragging = false
    private var controlsOn = true
    /** 上次上报时的播放时刻：listen_sec 由它和当前时刻算出来，暂停不累积。 */
    private var lastTick = 0L
    private var nextReportAt = 0L

    private val hideRunnable = Runnable { if (player?.isPlaying == true) setControls(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = intent.getStringExtra(EXTRA_COURSE).orEmpty()
        idx = intent.getIntExtra(EXTRA_IDX, 0)
        startMs = intent.getLongExtra(EXTRA_START, 0L)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val sub = intent.getStringExtra(EXTRA_SUB).orEmpty()
        val videoWidth = intent.getIntExtra(EXTRA_VIDEO_W, 0)
        val videoHeight = intent.getIntExtra(EXTRA_VIDEO_H, 0)
        if (url.isEmpty()) {
            finish()
            return
        }
        buildUi(title, sub, videoWidth, videoHeight)
        startPlayer(api.absolute(url))
    }

    override fun onStop() {
        player?.pause()
        report(force = true)
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        report(force = true)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 布局
    private fun buildUi(title: String, sub: String, videoWidth: Int, videoHeight: Int) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { setControls(!controlsOn) }
        }

        videoFrame = AspectLayout(this)
        // 服务端已给真实宽高：进页面就按真实比例占位，不等 onVideoSizeChanged
        // 才从默认 16:9 跳过去；拿不到时才沿用默认比例。
        if (videoWidth > 0 && videoHeight > 0) {
            videoFrame.ratio = videoWidth.toFloat() / videoHeight.toFloat()
        }
        root.addView(videoFrame, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val view = TextureView(this)
        tex = view.apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    // 旋转后 surface 会被重建，必须重新挂一次，否则只有声音没有画面
                    player?.setVideoTextureView(view)
                }

                override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
            }
        }
        videoFrame.addView(tex, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        loading = DropLoadingView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER }
        }
        root.addView(loading)

        controls = buildControls(title, sub)
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        setControls(true)
    }

    private fun buildControls(title: String, sub: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { /* 挡住点击，避免点到画面把控制栏一起收掉 */ }
            isClickable = true
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(4), dp(8), dp(12), dp(8))
            setOnClickListener { }
            isClickable = true
        }
        head.addView(Ui.icon(this, R.drawable.ic_back, Color.WHITE, 24, "返回") { finish() })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(Ui.text(this, title.ifEmpty { "视频" }, 14f, Color.WHITE, true, 1))
        if (sub.isNotBlank()) {
            col.addView(Ui.text(this, sub, 11f, 0xCCCCCC, false, 1).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
        head.addView(col)
        box.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(View(this).apply {
            // 中间留白也接点击：控制栏可见时点画面同样能收起
            setOnClickListener { setControls(false) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setOnClickListener { }
            isClickable = true
        }
        playBtn = Ui.icon(this, R.drawable.ic_play, Color.WHITE, 26, "播放") { toggle() }
        bottom.addView(playBtn)
        curText = Ui.text(this, "00:00", 11f, 0xE6FFFFFF.toInt(), false, 1).apply {
            setPadding(dp(4), 0, dp(8), 0)
        }
        bottom.addView(curText)
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val p = player ?: return
                    val dur = p.duration
                    if (dur > 0) curText.text = Ui.formatMs(dur * value / 1000)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    dragging = false
                    val p = player ?: return
                    val dur = p.duration
                    if (dur > 0) p.seekTo(dur * (bar?.progress ?: 0) / 1000)
                }
            })
        }
        bottom.addView(seek)
        durText = Ui.text(this, "--:--", 11f, 0xE6FFFFFF.toInt(), false, 1).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }
        bottom.addView(durText)
        speedBtn = Ui.text(this, "1.0x", 12f, Color.WHITE, true, 1).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            setOnClickListener { cycleSpeed() }
        }
        bottom.addView(speedBtn)
        sizeBtn = Ui.text(this, "全屏", 12f, Color.WHITE, true, 1).apply {
            setPadding(dp(8), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener { toggleOrientation() }
        }
        bottom.addView(sizeBtn)
        box.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return box
    }

    // ------------------------------------------------------------ 播放
    private fun startPlayer(url: String) {
        /* 数据源共用 Api 的 OkHttp：CookieJar 每次请求都会补 gk_sess，
           Range 请求和换会话都直接生效 */
        val ds = OkHttpDataSource.Factory(api.client)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 2500, 5000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(load)
            .build()
        player = p
        p.setVideoTextureView(tex)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                if (playing) {
                    ui.removeCallbacks(hideRunnable)
                    ui.postDelayed(hideRunnable, 4000)
                } else {
                    ui.removeCallbacks(hideRunnable)
                    setControls(true)
                }
                if (player?.isPlaying != true) report(force = true)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                var w = size.width * size.pixelWidthHeightRatio
                var h = size.height.toFloat()
                if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
                    val t = w; w = h; h = t
                }
                if (w > 0f && h > 0f) videoFrame.ratio = w / h
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) refreshDuration()
                if (state == Player.STATE_ENDED) report(force = true, ended = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.GONE
                setControls(true)
                val net = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                Ui.toast(this@VideoActivity, if (net) "网络连接失败，请检查服务器" else "播放失败，请稍后重试")
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        if (startMs > 0) p.seekTo(startMs)
        refreshDuration()
        ui.post(progressRunnable)
    }

    /** 每 500ms 刷一次进度条；播放中每 10 秒上报一次（与音频后台一致）。 */
    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val dur = p.duration
                if (dur > 0) {
                    if (!dragging) {
                        seek.progress = (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000)
                        curText.text = Ui.formatMs(p.currentPosition)
                    }
                    durText.text = Ui.formatMs(dur)
                }
                val now = SystemClock.elapsedRealtime()
                if (p.isPlaying && now >= nextReportAt) {
                    nextReportAt = now + 10_000
                    report()
                }
                if (lastTick == 0L && p.isPlaying) lastTick = now
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun refreshDuration() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0) durText.text = Ui.formatMs(dur)
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 0

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        val v = speeds[speedIndex]
        player?.setPlaybackSpeed(v)
        speedBtn.text = (if (v == v.toInt().toFloat()) "%.1f" else "%.2f").format(v) + "x"
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            sizeBtn.text = "全屏"
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            sizeBtn.text = "还原"
        }
        setControls(true)
    }

    private fun setControls(on: Boolean) {
        controlsOn = on
        controls.visibility = if (on) View.VISIBLE else View.GONE
        if (on && player?.isPlaying == true) {
            ui.removeCallbacks(hideRunnable)
            ui.postDelayed(hideRunnable, 4000)
        }
    }

    // ------------------------------------------------------------ 进度上报
    private fun report(force: Boolean = false, ended: Boolean = false) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0 || courseId.isEmpty()) return
        if (!force && !p.isPlaying) return
        val pos = p.currentPosition.coerceIn(0L, dur)
        val pct = if (ended) 100.0 else pos * 100.0 / dur
        /* listen_sec 只算真正播放的时间：暂停/缓冲期间 lastTick 归零，不累积 */
        val now = SystemClock.elapsedRealtime()
        val listen = if (lastTick > 0 && p.isPlaying)
            ((now - lastTick) / 1000).toInt().coerceIn(0, 15) else 0
        lastTick = if (p.isPlaying) now else 0
        reportScope.launch {
            runCatching {
                api.postAudioProgress(courseId, idx, pct, pos, dur, listen,
                    pct >= Api.READ_THRESHOLD)
            }
        }
    }
}

/**
 * 按视频宽高比摆放唯一子 View（纯黑边填充剩余空间）。
 * 参考 XinghaiFm 的 AspectLayout：优先用服务端给的真实宽高占位，拿不到才
 * 退回 16:9，避免第一帧出来前被拉满全屏、也避免加载后比例跳变。
 */
class AspectLayout(ctx: Context) : ViewGroup(ctx) {

    var ratio = 0f
        set(v) { if (field != v) { field = v; requestLayout() } }

    private var childW = 0
    private var childH = 0

    init { setClipChildren(true) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pw = MeasureSpec.getSize(widthMeasureSpec)
        val ph = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(pw, ph)

        val r = if (ratio > 0f) ratio else 16f / 9f
        if (pw <= 0 || ph <= 0) {
            childW = pw; childH = ph
        } else if (pw / ph.toFloat() > r) {
            childH = ph; childW = (ph * r + 0.5f).toInt()
        } else {
            childW = pw; childH = (pw / r + 0.5f).toInt()
        }

        if (childCount > 0) {
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return
        val cx = (width - childW) / 2
        val cy = (height - childH) / 2
        getChildAt(0).layout(cx, cy, cx + childW, cy + childH)
    }
}
