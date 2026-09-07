package com.mahao.teapricecompare

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class UsageLedgerRecord(
    val queryId: String,
    val requestId: String? = null,
    val apiRequestId: String? = null,
    val phase: String,
    val model: String,
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    val usage: DeepSeekUsage = DeepSeekUsage(),
    val priceVersion: String? = null,
    val billingPeriod: String? = null,
    val costUsd: Double = 0.0,
    val usdToCnyRate: Double = 0.0,
    val costCny: Double = 0.0,
    val success: Boolean = false,
    val error: String? = null,
    val usageEstimated: Boolean = false,
) {
    internal fun toJson() = JSONObject().apply {
        put("query_id", queryId)
        putNullable("request_id", requestId)
        putNullable("api_request_id", apiRequestId)
        put("phase", phase)
        put("model", model)
        put("started_at", startedAt)
        put("duration_ms", durationMs.coerceAtLeast(0L))
        put("usage", JSONObject().apply {
            put("prompt_tokens", usage.promptTokens)
            put("completion_tokens", usage.completionTokens)
            put("reasoning_tokens", usage.reasoningTokens)
            put("prompt_cache_hit_tokens", usage.cacheHitTokens)
            put("prompt_cache_miss_tokens", usage.cacheMissTokens)
            put("total_tokens", usage.totalTokens)
        })
        putNullable("price_version", priceVersion)
        putNullable("billing_period", billingPeriod)
        put("cost_usd", safeNonNegativeFinite(costUsd))
        put("usd_to_cny_rate", safeNonNegativeFinite(usdToCnyRate))
        put("cost_cny", safeNonNegativeFinite(costCny))
        put("success", success)
        put("usage_estimated", usageEstimated)
        putNullable("error", sanitizeLedgerError(error))
    }

    companion object {
        internal fun fromJson(json: JSONObject): UsageLedgerRecord {
            return UsageLedgerRecord(
                queryId = json.optString("query_id"),
                requestId = json.optNullableString("request_id"),
                apiRequestId = json.optNullableString("api_request_id"),
                phase = json.optString("phase"),
                model = json.optString("model"),
                startedAt = json.optLong("started_at", 0L),
                durationMs = json.optLong("duration_ms", 0L),
                usage = DeepSeekUsage.fromJson(json.optJSONObject("usage") ?: JSONObject()),
                priceVersion = json.optNullableString("price_version"),
                billingPeriod = json.optNullableString("billing_period"),
                costUsd = json.optDouble("cost_usd", 0.0),
                usdToCnyRate = json.optDouble("usd_to_cny_rate", 0.0),
                costCny = json.optDouble("cost_cny", 0.0),
                success = json.optBoolean("success", false),
                error = sanitizeLedgerError(json.optNullableString("error")),
                usageEstimated = json.optBoolean("usage_estimated", false),
            )
        }
    }
}

/** Bounded local storage for usage metadata; it never stores prompts or model output. */
class UsageLedgerStore(
    private val preferences: SharedPreferences,
    maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    private val maxRecords = maxRecords.coerceIn(1, MAX_ALLOWED_RECORDS)

    constructor(context: Context, maxRecords: Int = DEFAULT_MAX_RECORDS) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        maxRecords,
    )

    fun append(record: UsageLedgerRecord): Boolean = synchronized(GLOBAL_LOCK) {
        val stored = readStoredRecordsLocked()
        if (!stored.isValid) return@synchronized false
        val json = JSONArray()
        stored.records
            .toMutableList()
            .apply { add(record) }
            .takeLast(maxRecords)
            .forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY_RECORDS, json.toString()).apply()
        true
    }

    fun readAll(): List<UsageLedgerRecord> = synchronized(GLOBAL_LOCK) {
        readStoredRecordsLocked().records
    }

    private fun readStoredRecordsLocked(): StoredRecords {
        val raw = preferences.getString(KEY_RECORDS, null)
            ?: return StoredRecords(emptyList(), isValid = true)
        return runCatching {
            val json = JSONArray(raw)
            val parsed = (0 until json.length()).map { index ->
                runCatching { UsageLedgerRecord.fromJson(json.getJSONObject(index)) }
            }
            StoredRecords(
                records = parsed.mapNotNull { it.getOrNull() },
                isValid = parsed.all { it.isSuccess },
            )
        }.getOrElse { StoredRecords(emptyList(), isValid = false) }
    }

    private data class StoredRecords(
        val records: List<UsageLedgerRecord>,
        val isValid: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_RECORDS = 100
        private const val MAX_ALLOWED_RECORDS = 1_000
        private const val PREFERENCES_NAME = "deepseek_usage_ledger"
        private const val KEY_RECORDS = "deepseek_usage_ledger"
        private val GLOBAL_LOCK = Any()
    }
}

private fun JSONObject.putNullable(key: String, value: String?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun sanitizeLedgerError(error: String?): String? {
    val normalized = error?.trim() ?: return null
    val httpCode = Regex("^DeepSeek API error ([1-5]\\d{2})$")
        .matchEntire(normalized)
        ?.groupValues
        ?.getOrNull(1)
    return when {
        httpCode != null -> "deepseek_http_$httpCode"
        Regex("^deepseek_http_[1-5]\\d{2}$").matches(normalized) -> normalized
        normalized == "Query budget exceeded before request" -> "budget_exceeded"
        normalized == "budget_exceeded" -> normalized
        normalized == "Invalid DeepSeek response" -> "invalid_response"
        normalized == "invalid_response" -> normalized
        normalized == "DeepSeek request failed" -> "request_failed"
        normalized == "request_failed" -> normalized
        else -> "request_failed"
    }
}

private fun safeNonNegativeFinite(value: Double): Double =
    value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
