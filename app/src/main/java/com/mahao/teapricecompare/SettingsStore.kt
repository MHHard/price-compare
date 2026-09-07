package com.mahao.teapricecompare

import android.content.Context

/** Local-only storage for the user's own DeepSeek API key — never bundled into the app or synced anywhere. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deepSeekApiKey: String?
        get() = prefs.getString(KEY_DEEPSEEK_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_DEEPSEEK_API_KEY, value).apply()

    val queryMaxCalls: Int
        get() = prefs.getInt(KEY_QUERY_MAX_CALLS, DEFAULT_QUERY_MAX_CALLS)

    val queryMaxTotalTokens: Int
        get() = prefs.getInt(KEY_QUERY_MAX_TOTAL_TOKENS, DEFAULT_QUERY_MAX_TOTAL_TOKENS)

    val queryMaxCostUsd: Double
        get() = prefs.getFloat(KEY_QUERY_MAX_COST_USD, DEFAULT_QUERY_MAX_COST_USD.toFloat()).toDouble()

    val queryMaxRecoverySteps: Int
        get() = prefs.getInt(KEY_QUERY_MAX_RECOVERY_STEPS, DEFAULT_QUERY_MAX_RECOVERY_STEPS)

    val usdToCnyRate: Double
        get() = prefs.getFloat(KEY_USD_TO_CNY_RATE, DEFAULT_USD_TO_CNY_RATE.toFloat()).toDouble()

    companion object {
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val KEY_QUERY_MAX_CALLS = "query_max_calls"
        private const val KEY_QUERY_MAX_TOTAL_TOKENS = "query_max_total_tokens"
        private const val KEY_QUERY_MAX_COST_USD = "query_max_cost_usd"
        private const val KEY_QUERY_MAX_RECOVERY_STEPS = "query_max_recovery_steps"
        private const val KEY_USD_TO_CNY_RATE = "usd_to_cny_rate"

        const val DEFAULT_QUERY_MAX_CALLS = QueryBudget.MAX_CALLS
        const val DEFAULT_QUERY_MAX_TOTAL_TOKENS = QueryBudget.MAX_TOTAL_TOKENS
        const val DEFAULT_QUERY_MAX_COST_USD = QueryBudget.MAX_COST_USD
        const val DEFAULT_QUERY_MAX_RECOVERY_STEPS = QueryBudget.MAX_RECOVERY_STEPS
        const val DEFAULT_USD_TO_CNY_RATE = 7.2
    }
}
