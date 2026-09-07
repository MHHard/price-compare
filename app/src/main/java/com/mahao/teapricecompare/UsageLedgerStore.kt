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
            val queryId = requiredText(json, "query_id")
            val phase = requiredText(json, "phase")
            val model = requiredText(json, "model")
            val usageJson = json.optJSONObject("usage")
            require(usageJson != null) { "usage is required" }
            val usage = DeepSeekUsage.fromJson(usageJson)
            require(usage.isComplete) { "usage is invalid" }
            return UsageLedgerRecord(
                queryId = queryId,
                requestId = json.readOptionalText("request_id"),
                apiRequestId = json.readOptionalText("api_request_id"),
                phase = phase,
                model = model,
                startedAt = json.readNonNegativeLong("started_at"),
                durationMs = json.readNonNegativeLong("duration_ms"),
                usage = usage,
                priceVersion = json.readOptionalText("price_version"),
                billingPeriod = json.readOptionalText("billing_period"),
                costUsd = json.readOptionalFiniteNonNegativeDouble("cost_usd"),
                usdToCnyRate = json.readOptionalFiniteNonNegativeDouble("usd_to_cny_rate"),
                costCny = json.readOptionalFiniteNonNegativeDouble("cost_cny"),
                success = json.readOptionalBoolean("success", false),
                error = sanitizeLedgerError(json.readOptionalText("error")),
                usageEstimated = json.readOptionalBoolean("usage_estimated", false),
            )
        }

        private fun requiredText(json: JSONObject, key: String): String {
            val value = json.opt(key)
            require(value is String && value.isNotBlank()) { "$key is required" }
            return value
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

private fun JSONObject.readOptionalText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    require(value is String) { "$key must be text" }
    return value.takeIf { it.isNotBlank() }
}

private fun JSONObject.readNonNegativeLong(key: String): Long {
    if (!has(key) || isNull(key)) return 0L
    val value = opt(key)
    val number = value as? Number ?: throw IllegalArgumentException("$key must be numeric")
    if (number is Long || number is Int || number is Short || number is Byte) {
        require(number.toLong() >= 0L) { "$key must be non-negative" }
        return number.toLong()
    }
    val doubleValue = number.toDouble()
    require(
        doubleValue.isFinite() &&
            doubleValue >= 0.0 &&
            doubleValue <= Long.MAX_VALUE.toDouble() &&
            doubleValue == number.toLong().toDouble(),
    ) { "$key must be a finite non-negative integer" }
    return number.toLong()
}

private fun JSONObject.readOptionalFiniteNonNegativeDouble(key: String): Double {
    if (!has(key) || isNull(key)) return 0.0
    val value = opt(key)
    val number = value as? Number ?: throw IllegalArgumentException("$key must be numeric")
    return number.toDouble().takeIf { it.isFinite() && it >= 0.0 }
        ?: throw IllegalArgumentException("$key must be finite and non-negative")
}

private fun JSONObject.readOptionalBoolean(key: String, default: Boolean): Boolean {
    if (!has(key) || isNull(key)) return default
    val value = opt(key)
    require(value is Boolean) { "$key must be boolean" }
    return value
}

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
