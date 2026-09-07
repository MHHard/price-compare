package com.mahao.teapricecompare

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageLedgerTest {

    @Test
    fun sharedPreferencesRoundTripRestoresUsageAndRequestMetadata() {
        val preferences = MemoryPreferences()
        val store = UsageLedgerStore(preferences)
        val usage = DeepSeekUsage(
            promptTokens = 1000,
            completionTokens = 200,
            reasoningTokens = 80,
            cacheHitTokens = 400,
            cacheMissTokens = 600,
            totalTokens = 1200,
        )
        val record = UsageLedgerRecord(
            queryId = "query-1",
            requestId = "req-123",
            apiRequestId = "api-123",
            phase = "parse_price",
            model = "deepseek-v4-flash",
            startedAt = 1_725_000_000_000,
            durationMs = 321,
            usage = usage,
            priceVersion = "deepseek-v4-flash-2026-09",
            billingPeriod = "off_peak",
            costUsd = 0.0012,
            usdToCnyRate = 7.2,
            costCny = 0.00864,
            success = true,
            error = null,
        )

        store.append(record)

        val restored = store.readAll().single()
        assertEquals(record, restored)
    }

    @Test
    fun storeKeepsOnlyTheMostRecentReasonableNumberOfRecords() {
        val store = UsageLedgerStore(MemoryPreferences(), maxRecords = 2)

        repeat(3) { index ->
            store.append(
                UsageLedgerRecord(
                    queryId = "query-$index",
                    requestId = "request-$index",
                    phase = "test",
                    model = "deepseek-v4-flash",
                    usage = DeepSeekUsage(totalTokens = index),
                ),
            )
        }

        assertEquals(listOf("query-1", "query-2"), store.readAll().map { it.queryId })
    }

    @Test
    fun rawErrorResponseIsReducedBeforeItReachesTheLedger() {
        val rawErrorBody = """
            {"error":{"message":"secret=sk-live-value; page text: user address and full model response"}}
        """.trimIndent()
        val result = DeepSeekClient("").parseChatCompletionResponse(rawErrorBody, responseCode = 429)
        val preferences = MemoryPreferences()
        UsageLedgerStore(preferences).append(
            UsageLedgerRecord(
                queryId = "query-safe",
                requestId = result.requestId,
                phase = "parse_price",
                model = result.model ?: "deepseek-v4-flash",
                usage = result.usage ?: DeepSeekUsage(),
                success = result.success,
                error = result.error,
            ),
        )

        assertEquals("DeepSeek API error 429", result.error)
        val serialized = preferences.getString("deepseek_usage_ledger", "")!!
        assertFalse(serialized.contains(rawErrorBody))
        assertFalse(serialized.contains("sk-live-value"))
        assertFalse(serialized.contains("user address"))
        assertFalse(serialized.contains("full model response"))
        assertTrue(serialized.contains("deepseek_http_429"))
    }

    @Test
    fun untrustedLedgerErrorIsStoredAsAControlledCode() {
        val preferences = MemoryPreferences()
        UsageLedgerStore(preferences).append(
            UsageLedgerRecord(
                queryId = "query-safe",
                requestId = "request-safe",
                phase = "parse_price",
                model = "deepseek-v4-flash",
                usage = DeepSeekUsage(totalTokens = 10),
                error = "unexpected error detail",
            ),
        )

        val serialized = preferences.getString("deepseek_usage_ledger", "")!!
        assertEquals("request_failed", UsageLedgerStore(preferences).readAll().single().error)
        assertFalse(serialized.contains("unexpected error detail"))
        assertTrue(serialized.contains("query-safe"))
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
