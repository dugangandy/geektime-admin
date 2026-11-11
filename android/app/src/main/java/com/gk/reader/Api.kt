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

/** 只存当前这一台服务器的 Cookie；换服务器时清空，避免串会话。 */
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
        for ((k, v) in sp.all) {
            if (!k.startsWith(prefix) || v !is String) continue
            val cookie = Cookie.parse(url, v) ?: continue
            if (cookie.expiresAt > now) out.add(cookie) else sp.edit().remove(k).apply()
        }
        return out
    }

    fun hasAny(): Boolean = sp.all.isNotEmpty()

    fun clear() = sp.edit().clear().apply()

    private fun keyOf(url: HttpUrl, name: String) = url.host + "|" + url.port + "|" + name
}

class Api(val context: Context, val prefs: Prefs) {
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

    fun hasSession() = cookieStore.hasAny()

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
        val url = (base ?: baseUrl) + if (path.startsWith("/")) path else "/$path"
        val builder = Request.Builder().url(url)
            .header("X-GK-Client", "android")
            .header("X-GK-Device", deviceId)
        val media = "application/json; charset=utf-8".toMediaType()
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(media))
            else -> builder.method(method, (body ?: JSONObject()).toString().toRequestBody(media))
        }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401 && !authAsError) {
                cookieStore.clear()
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

    private fun requestArray(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = request(method, path, body)

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
            cookieStore.clear()
            val body = JSONObject().put("password", password).put("keep", keep)
            val json = request("POST", "/api/auth/login", body, base = base, authAsError = true)
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
                                audioUrl = o.optString("audio_url"),
                                pdfUrl = o.optString("pdf_url"),
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
            last = parseLast(json.optJSONObject("last"))
        )
    }

    suspend fun lesson(courseId: String, idx: Int): LessonDetail = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/courses/$courseId/lessons/$idx")
        LessonDetail(
            idx = json.optInt("idx"),
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
            bodyHtml = json.optString("body_html"),
            prev = parseNav(json.optJSONObject("prev")),
            next = parseNav(json.optJSONObject("next"))
        )
    }

    // ------------------------------------------------------------ 进度 / 统计
    suspend fun progressAll(): Map<String, CourseProgress> = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/progress")
        val out = HashMap<String, CourseProgress>()
        val root = json.optJSONObject("courses") ?: return@withContext out
        for (id in root.keys()) {
            val o = root.optJSONObject(id) ?: continue
            out[id] = CourseProgress(emptyMap(), null)
        }
        out
    }

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
                    touched = o.optLong("touched")
                )
            }
        }
        CourseProgress(lessons, parseLast(json.optJSONObject("last")))
    }

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
        last = parseLast(o.optJSONObject("last"))
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
        fun normalize(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return Prefs.DEFAULT_SERVER
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            return value.trimEnd('/')
        }
    }
}
