package com.gk.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfter: Int = 0
) : Exception(message)

class AuthExpired : Exception("登录已过期，请重新登录")

/** 按 host|port 分桶存 Cookie；只有登录成功后才淘汰其它服务器，避免串会话。 */
class CookieStore(context: Context) : CookieJar {
    private val sp = context.getSharedPreferences("gk_cookies", Context.MODE_PRIVATE)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val editor = sp.edit()
        for (cookie in cookies) {
            val key = keyOf(url, cookie.name)
            if (cookie.expiresAt <= System.currentTimeMillis()) editor.remove(key)
            else editor.putString(key, cookie.toString())
        }
        editor.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val prefix = url.host + "|" + url.port + "|"
        val now = System.currentTimeMillis()
        val out = ArrayList<Cookie>()
        val expired = ArrayList<String>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(prefix) || v !is String) continue
            // Cookie.parse 声明 @Throws，一条脏记录不能让整个进程的所有请求都挂掉
            val cookie = runCatching { Cookie.parse(url, v) }.getOrNull() ?: continue
            if (cookie.expiresAt > now) out.add(cookie) else expired.add(k)
        }
        if (expired.isNotEmpty()) {
            val editor = sp.edit()
            for (k in expired) editor.remove(k)
            editor.apply()
        }
        return out
    }

    fun hasFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    /** 登录成功后只保留当前这台服务器的 Cookie；失败时不动，避免连带退掉可用的旧登录。 */
    fun retainOnly(url: HttpUrl?) {
        if (url == null) return
        val prefix = url.host + "|" + url.port + "|"
        val editor = sp.edit()
        for (k in sp.all.keys) if (!k.startsWith(prefix)) editor.remove(k)
        editor.apply()
    }

    fun clear() = sp.edit().clear().apply()

    private fun keyOf(url: HttpUrl, name: String) = url.host + "|" + url.port + "|" + name
}

class Api(context: Context, val prefs: Prefs) {
    val cookieStore = CookieStore(context)
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieStore)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = normalize(prefs.serverUrl)
    val deviceId: String get() = prefs.deviceId

    fun absolute(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl + if (path.startsWith("/")) path else "/$path"
    }

    fun hasSession(): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        return cookieStore.hasFor(url)
    }

    /** 给 WebView 补登录态：同源图片/静态资源也要带 gk_sess。 */
    fun cookieHeader(url: String = baseUrl): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return cookieStore.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
    }

    // ------------------------------------------------------------ 基础请求
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        base: String? = null,
        authAsError: Boolean = false
    ): JSONObject {
        val origin = base ?: baseUrl
        if (origin.isEmpty()) throw ApiException(0, "no_server", "请填写服务器地址")
        val url = origin + if (path.startsWith("/")) path else "/$path"
        val builder = Request.Builder().url(url)
            .header("X-GK-Client", "android")
            .header("X-GK-Device", deviceId)
        val media = "application/json; charset=utf-8".toMediaType()
        // 无 body 时必须发零字节：服务端 logout 不读请求体，残留字节会污染复用的 keep-alive 连接
        val payload = if (body == null) ByteArray(0).toRequestBody(null)
        else body.toString().toRequestBody(media)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(payload)
            else -> builder.method(method, payload)
        }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401 && !authAsError) {
                cookieStore.clear()
                clearWebCookies()
                throw AuthExpired()
            }
            if (!res.isSuccessful) {
                var code = "http_error"
                var msg = "请求失败（${res.code}）"
                var retry = 0
                try {
                    val error = JSONObject(text).optJSONObject("error")
                    if (error != null) {
                        code = error.optString("code", code)
                        msg = error.optString("message", msg)
                        retry = error.optInt("retry_after", 0)
                    }
                } catch (_: Exception) {
                }
                if (res.code == 401) code = "bad_password"
                throw ApiException(res.code, code, msg, retry)
            }
            if (text.isBlank()) return JSONObject()
            return JSONObject(text)
        }
    }

    // ------------------------------------------------------------ 鉴权
    suspend fun ping(server: String? = null): Ping = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/ping", base = normalize(server ?: prefs.serverUrl))
        Ping(
            ok = json.optBoolean("ok", false),
            name = json.optString("name"),
            version = json.optInt("version"),
            authenticated = json.optBoolean("authenticated", false),
            courses = json.optInt("courses")
        )
    }

    suspend fun login(server: String, password: String, keep: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            val base = normalize(server)
            val body = JSONObject().put("password", password).put("keep", keep)
            val json = request("POST", "/api/auth/login", body, base = base, authAsError = true)
            // 成功之后才淘汰别的服务器：失败时保留旧登录，否则填错新地址会把可用的会话一起退掉
            cookieStore.retainOnly(base.toHttpUrlOrNull())
            prefs.serverUrl = base
            json
        }

    suspend fun session(): JSONObject = withContext(Dispatchers.IO) {
        request("GET", "/api/auth/session")
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            request("POST", "/api/auth/logout")
        } catch (_: Exception) {
        } finally {
            cookieStore.clear()
            clearWebCookies()
        }
    }

    /** gk_sess 是无状态签名令牌，服务端无法吊销；WebView 里那份必须一起清，否则登出后仍有效。 */
    private fun clearWebCookies() {
        runCatching {
            val manager = android.webkit.CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.removeSessionCookies(null)
            manager.flush()
        }
    }

    // ------------------------------------------------------------ 课程
    suspend fun courses(): CoursesPayload = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/courses")
        val categories = ArrayList<Category>()
        json.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                categories.add(Category(o.optString("key"), o.optString("label"), o.optInt("count")))
            }
        }
        val courses = ArrayList<CourseSummary>()
        json.optJSONArray("courses")?.let { arr ->
            for (i in 0 until arr.length()) courses.add(parseCourse(arr.optJSONObject(i) ?: continue))
        }
        CoursesPayload(categories, courses)
    }

    suspend fun course(id: String): CourseDetail = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/courses/$id")
        val chapters = ArrayList<Chapter>()
        json.optJSONArray("chapters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val items = ArrayList<LessonItem>()
                c.optJSONArray("items")?.let { rows ->
                    for (j in 0 until rows.length()) {
                        val o = rows.optJSONObject(j) ?: continue
                        items.add(
                            LessonItem(
                                idx = o.optInt("idx"),
                                label = o.optString("label"),
                                date = o.optString("date"),
                                dur = o.optString("dur"),
                                hasAudio = o.optBoolean("has_audio"),
                                hasPdf = o.optBoolean("has_pdf"),
                                hasVideo = o.optBoolean("has_video"),
                                audioUrl = o.optString("audio_url"),
                                pdfUrl = o.optString("pdf_url"),
                                videoUrl = o.optString("video_url"),
                                read = o.optBoolean("read"),
                                pct = o.optDouble("pct", 0.0),
                                pos = o.optDouble("pos", 0.0)
                            )
                        )
                    }
                }
                chapters.add(Chapter(c.optString("name"), items))
            }
        }
        CourseDetail(
            id = json.optString("id"),
            name = json.optString("name"),
            author = json.optString("author"),
            identity = json.optString("identity"),
            badge = json.optString("badge"),
            learners = json.optString("learners"),
            coverUrl = json.optString("cover_url"),
            total = json.optInt("total"),
            read = json.optInt("read"),
            pct = json.optInt("pct"),
            chapters = chapters,
            last = parseLast(json.optJSONObject("last")),
            videoCourse = json.optBoolean("video_course")
        )
    }

    suspend fun lesson(courseId: String, idx: Int): LessonDetail = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/courses/$courseId/lessons/$idx")
        LessonDetail(
            idx = json.optInt("idx"),
            pageUrl = json.optString("page_url"),
            total = json.optInt("total"),
            kind = json.optString("kind"),
            title = json.optString("title"),
            course = json.optString("course"),
            author = json.optString("author"),
            date = json.optString("date"),
            duration = json.optString("duration"),
            coverUrl = json.optString("cover_url"),
            audioUrl = json.optString("audio_url"),
            pdfUrl = json.optString("pdf_url"),
            videoUrl = json.optString("video_url"),
            bodyHtml = json.optString("body_html"),
            prev = parseNav(json.optJSONObject("prev")),
            next = parseNav(json.optJSONObject("next"))
        )
    }

    // ------------------------------------------------------------ 进度 / 统计
    suspend fun progressCourse(id: String): CourseProgress = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/progress/$id")
        val lessons = HashMap<Int, LessonProgress>()
        json.optJSONObject("lessons")?.let { obj ->
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                val idx = key.toIntOrNull() ?: continue
                lessons[idx] = LessonProgress(
                    pct = o.optDouble("pct", 0.0),
                    pos = o.optDouble("pos", 0.0),
                    dur = o.optDouble("dur", 0.0),
                    read = o.optBoolean("read"),
                    touched = o.optLong("touched"),
                    audioPos = o.optLong("audio_pos"),
                    audioDur = o.optLong("audio_dur")
                )
            }
        }
        CourseProgress(lessons, parseLast(json.optJSONObject("last")))
    }

    /** 阅读进度：pos/dur 是滚动像素，只有阅读器能写。 */
    suspend fun postProgress(
        courseId: String,
        lesson: Int,
        pct: Double,
        pos: Double,
        dur: Double,
        read: Boolean,
        listenSec: Int
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("course", courseId)
            .put("lesson", lesson)
            .put("pct", pct)
            .put("pos", pos)
            .put("dur", dur)
            .put("read", read)
            .put("listen_sec", listenSec)
        request("POST", "/api/progress", body)
    }

    /** 音频进度：毫秒走独立字段，避免覆盖阅读器的滚动位置。 */
    suspend fun postAudioProgress(
        courseId: String,
        lesson: Int,
        pct: Double,
        posMs: Long,
        durMs: Long,
        listenSec: Int,
        read: Boolean
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("course", courseId)
            .put("lesson", lesson)
            .put("pct", pct)
            .put("audio_pos", posMs)
            .put("audio_dur", durMs)
            .put("listen_sec", listenSec)
            .put("read", read)
        request("POST", "/api/progress", body)
    }

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/stats")
        val cont = ArrayList<ContinueRow>()
        json.optJSONArray("continue")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                cont.add(
                    ContinueRow(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        coverUrl = o.optString("cover_url"),
                        lesson = o.optInt("lesson"),
                        title = o.optString("title"),
                        pct = o.optDouble("pct", 0.0),
                        at = o.optLong("at")
                    )
                )
            }
        }
        val recent = ArrayList<RecentRow>()
        json.optJSONArray("recent")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                recent.add(
                    RecentRow(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        coverUrl = o.optString("cover_url"),
                        lesson = o.optInt("lesson"),
                        title = o.optString("title"),
                        pct = o.optDouble("pct", 0.0),
                        read = o.optBoolean("read"),
                        at = o.optLong("at")
                    )
                )
            }
        }
        Stats(
            lessonsRead = json.optInt("lessons_read"),
            lessonsTotal = json.optInt("lessons_total"),
            minutes = json.optInt("minutes"),
            coursesStarted = json.optInt("courses_started"),
            coursesTotal = json.optInt("courses_total"),
            daysRead = json.optInt("days_read"),
            streak = json.optInt("streak"),
            continueRows = cont,
            recent = recent
        )
    }

    suspend fun search(query: String): SearchPayload = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = request("GET", "/api/search?q=$q")
        val courses = ArrayList<CourseSummary>()
        json.optJSONArray("courses")?.let { arr ->
            for (i in 0 until arr.length()) courses.add(parseCourse(arr.optJSONObject(i) ?: continue))
        }
        val lessons = ArrayList<LessonHit>()
        json.optJSONArray("lessons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                lessons.add(
                    LessonHit(
                        courseId = o.optString("course"),
                        courseName = o.optString("course_name"),
                        idx = o.optInt("idx"),
                        label = o.optString("label"),
                        chapter = o.optString("chapter"),
                        date = o.optString("date"),
                        dur = o.optString("dur")
                    )
                )
            }
        }
        SearchPayload(courses, lessons)
    }

    // ------------------------------------------------------------ 解析
    private fun parseCourse(o: JSONObject) = CourseSummary(
        id = o.optString("id"),
        name = o.optString("name"),
        author = o.optString("author"),
        identity = o.optString("identity"),
        category = o.optString("category"),
        categoryLabel = o.optString("category_label"),
        badge = o.optString("badge"),
        learners = o.optString("learners"),
        total = o.optInt("total"),
        coverUrl = o.optString("cover_url"),
        read = o.optInt("read"),
        pct = o.optInt("pct"),
        started = o.optBoolean("started"),
        last = parseLast(o.optJSONObject("last")),
        videoCourse = o.optBoolean("video_course")
    )

    private fun parseLast(o: JSONObject?): LastLesson? {
        if (o == null || o.isNull("lesson")) return null
        return LastLesson(o.optInt("lesson"), o.optString("title"), o.optLong("at"))
    }

    private fun parseNav(o: JSONObject?): NavRef? {
        if (o == null) return null
        return NavRef(o.optInt("idx"), o.optString("title"))
    }

    companion object {
        /** 与服务端 mobile_api.py READ_THRESHOLD 保持一致。 */
        const val READ_THRESHOLD = 88.0

        fun normalize(raw: String): String {
            var value = raw.trim()
            // 空地址不能回落到默认服务器：否则用户没填地址就把密码发去了另一台主机
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            return value.trimEnd('/')
        }
    }
}
