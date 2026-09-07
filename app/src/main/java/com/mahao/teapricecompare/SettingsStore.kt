package com.mahao.teapricecompare

import android.content.Context

/** Local-only storage for the user's own DeepSeek API key — never bundled into the app or synced anywhere. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deepSeekApiKey: String?
        get() = prefs.getString(KEY_DEEPSEEK_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_DEEPSEEK_API_KEY, value).apply()

    companion object {
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }
}
