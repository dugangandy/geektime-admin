package com.gk.reader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class GkApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        api = Api(this, prefs)
    }
}
