package com.gk.reader

import android.content.Context
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gk", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = sp.edit().putString("server", value).apply()

    var remember: Boolean
        get() = sp.getBoolean("remember", true)
        set(value) = sp.edit().putBoolean("remember", value).apply()

    /** 0 跟随系统，1 浅色，2 深色 */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(value) = sp.edit().putInt("theme", value).apply()

    var fontSp: Int
        get() = sp.getInt("font", 17)
        set(value) = sp.edit().putInt("font", value).apply()

    /** 0 紧凑，1 标准，2 宽松 */
    var lineSpacing: Int
        get() = sp.getInt("line", 1)
        set(value) = sp.edit().putInt("line", value).apply()

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(value) = sp.edit().putFloat("speed", value).apply()

    val deviceId: String
        get() {
            val existing = sp.getString("device", null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("device", created).apply()
            return created
        }

    companion object {
        const val DEFAULT_SERVER = "http://139.198.29.121:8888"
        const val LAN_HINT = "http://192.168.1.8:8888"
    }
}
