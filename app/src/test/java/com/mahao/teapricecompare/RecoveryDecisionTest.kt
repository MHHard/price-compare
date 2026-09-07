package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class RecoveryDecisionTest {

    @Test
    fun parsesSearchVariantWithRequiredState() {
        val decision = RecoveryDecision.fromJson(
            JSONObject(
                """
                {
                  "action":"SEARCH_VARIANT",
                  "keywords":["空山栀子茶","空山栀子饮品"],
                  "reason":"页面有相近商品名",
                  "confidence":0.86,
                  "expected_state":"PRODUCT_LIST"
                }
                """.trimIndent(),
            ),
        )

        assertEquals(RecoveryAction.SEARCH_VARIANT, decision?.action)
        assertEquals(listOf("空山栀子茶", "空山栀子饮品"), decision?.keywords)
        assertEquals("PRODUCT_LIST", decision?.expectedState)
    }

    @Test
    fun rejectsUnknownActionCoordinatesAndMissingKeywords() {
        assertNull(
            RecoveryDecision.fromJson(
                JSONObject("{\"action\":\"CLICK_COORDINATE\",\"expected_state\":\"PRODUCT_LIST\"}"),
            ),
        )
        assertNull(
            RecoveryDecision.fromJson(
                JSONObject("{\"action\":\"RETRY_CURRENT\",\"x\":12,\"expected_state\":\"PRODUCT_LIST\"}"),
            ),
        )
        assertNull(
            RecoveryDecision.fromJson(
                JSONObject("{\"action\":\"SEARCH_VARIANT\",\"expected_state\":\"PRODUCT_LIST\"}"),
            ),
        )
    }

    @Test
    fun executorOnlyDispatchesKnownLocalActions() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = RecoveryExecutor(
            object : RecoveryActions {
                override suspend fun retryCurrent(): Boolean {
                    calls += "retry"
                    return true
                }

                override suspend fun scrollAndScan(): Boolean {
                    calls += "scroll"
                    return true
                }

                override suspend fun switchCategory(category: String): Boolean {
                    calls += "category:$category"
                    return true
                }

                override suspend fun searchVariant(keyword: String): Boolean {
                    calls += "keyword:$keyword"
                    return true
                }

                override suspend fun openCandidate(index: Int): Boolean {
                    calls += "candidate:$index"
                    return true
                }

                override suspend fun skipStore(): Boolean {
                    calls += "skip"
                    return true
                }
            },
        )

        val result = executor.execute(
            RecoveryDecision(
                action = RecoveryAction.SEARCH_VARIANT,
                keywords = listOf("空山栀子饮品"),
                expectedState = "PRODUCT_LIST",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("keyword:空山栀子饮品"), calls)
    }

    @Test
    fun executorRejectsInvalidTypedDecisionBeforeCallingLocalAction() = runBlocking {
        var called = false
        val executor = RecoveryExecutor(
            object : RecoveryActions {
                override suspend fun retryCurrent(): Boolean {
                    called = true
                    return true
                }

                override suspend fun scrollAndScan() = false
                override suspend fun switchCategory(category: String) = false
                override suspend fun searchVariant(keyword: String) = false
                override suspend fun openCandidate(index: Int) = false
                override suspend fun skipStore() = false
            },
        )

        val result = executor.execute(
            RecoveryDecision(
                action = RecoveryAction.SEARCH_VARIANT,
                keywords = emptyList(),
                expectedState = "PRODUCT_LIST",
            ),
        )

        assertFalse(result.isSuccess)
        assertFalse(called)
    }
}
