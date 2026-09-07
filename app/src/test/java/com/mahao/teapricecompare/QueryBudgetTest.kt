package com.mahao.teapricecompare

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryBudgetTest {

    @Test
    fun fixedLimitsRejectTheSeventhCall() {
        val budget = QueryBudget()

        repeat(QueryBudget.MAX_CALLS) {
            assertTrue(budget.canStart(estimatedTokens = 100, estimatedCostUsd = 0.001))
            budget.record(tokens = 100, costUsd = 0.001)
        }

        assertFalse(budget.canStart(estimatedTokens = 1, estimatedCostUsd = 0.000001))
        assertEquals(QueryBudget.MAX_CALLS, budget.callsUsed)
    }

    @Test
    fun tokenAndCostEstimatesAreCheckedBeforeStartingAnotherCall() {
        val tokenBudget = QueryBudget()
        tokenBudget.record(tokens = QueryBudget.MAX_TOTAL_TOKENS - 10, costUsd = 0.001)
        assertFalse(tokenBudget.canStart(estimatedTokens = 11, estimatedCostUsd = 0.0))

        val costBudget = QueryBudget()
        costBudget.record(tokens = 1, costUsd = QueryBudget.MAX_COST_USD - 0.000001)
        assertFalse(costBudget.canStart(estimatedTokens = 0, estimatedCostUsd = 0.0000011))
    }

    @Test
    fun reservationsAtomicallyHoldCallTokenAndCostCapacity() {
        val budget = QueryBudget()
        val reservations = (0 until QueryBudget.MAX_CALLS).map {
            budget.reserve(estimatedTokens = 100, estimatedCostUsd = 0.001)
        }

        assertTrue(reservations.all { it != null })
        assertEquals(null, budget.reserve(estimatedTokens = 100, estimatedCostUsd = 0.001))
        reservations.filterNotNull().forEach { reservation ->
            assertTrue(budget.settle(reservation, DeepSeekUsage(totalTokens = 100), 0.001))
        }
        assertFalse(budget.settle(reservations.first()!!, DeepSeekUsage(totalTokens = 100), 0.001))
        assertEquals(QueryBudget.MAX_CALLS, budget.callsUsed)
    }

    @Test
    fun exhaustedRecoveryBudgetDoesNotBlockOrdinaryCalls() {
        val budget = QueryBudget()

        repeat(QueryBudget.MAX_RECOVERY_STEPS) {
            assertTrue(budget.recordRecoveryStep())
        }

        assertFalse(budget.recordRecoveryStep())
        assertTrue(budget.canStart())
        assertFalse(budget.canStartRecovery())
    }

    @Test
    fun recoveryBudgetCanBeCheckedAlongsideCallBudgets() {
        val budget = QueryBudget()

        assertTrue(budget.canStartRecovery(estimatedTokens = 100, estimatedCostUsd = 0.001))
        budget.recordRecoveryStep()
        assertTrue(budget.canStartRecovery(estimatedTokens = 100, estimatedCostUsd = 0.001))
    }

    @Test
    fun usageJsonReadsSnakeCaseFieldsAndDoesNotDoubleCountTotalTokens() {
        val usage = DeepSeekUsage.fromJson(
            JSONObject(
                """
                {
                  "prompt_tokens": 1000,
                  "completion_tokens": 200,
                  "prompt_cache_hit_tokens": 400,
                  "prompt_cache_miss_tokens": 600,
                  "total_tokens": 1200,
                  "completion_tokens_details": {"reasoning_tokens": 80}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1000, usage.promptTokens)
        assertEquals(200, usage.completionTokens)
        assertEquals(80, usage.reasoningTokens)
        assertEquals(400, usage.cacheHitTokens)
        assertEquals(600, usage.cacheMissTokens)
        assertEquals(1200, usage.totalTokens)

        val budget = QueryBudget()
        budget.record(usage, costUsd = 0.001)
        assertEquals(1200, budget.totalTokensUsed)
    }

    @Test
    fun missingCacheFieldsFallBackToUncachedPromptTokens() {
        val usage = DeepSeekUsage.fromJson(
            JSONObject("""{"prompt_tokens": 25, "completion_tokens": 5, "total_tokens": 30}"""),
        )

        assertEquals(0, usage.cacheHitTokens)
        assertEquals(25, usage.cacheMissTokens)
        assertEquals(30, usage.totalTokens)
    }

    @Test
    fun cacheAwareCostUsesTheSelectedCatalogRates() {
        val usage = DeepSeekUsage(
            promptTokens = 1000,
            completionTokens = 200,
            reasoningTokens = 80,
            cacheHitTokens = 400,
            cacheMissTokens = 600,
            totalTokens = 1200,
        )
        val catalog = DeepSeekPriceCatalog.flashOffPeak
        val expected = (
            usage.cacheHitTokens * catalog.cacheHitPriceUsdPerMillion +
                usage.cacheMissTokens * catalog.cacheMissPriceUsdPerMillion +
                usage.completionTokens * catalog.outputPriceUsdPerMillion
            ) / 1_000_000.0

        assertEquals(expected, catalog.cost(usage), 0.000000001)
        assertEquals(1200, usage.totalTokens)
    }

    @Test
    fun successfulResponseParserReturnsContentUsageRequestIdAndModel() {
        val result = DeepSeekClient("").parseChatCompletionResponse(
            """
            {
              "id": "chatcmpl-123",
              "model": "deepseek-v4-flash",
              "choices": [{"message": {"content": "42"}}],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 2,
                "prompt_cache_hit_tokens": 4,
                "prompt_cache_miss_tokens": 6,
                "total_tokens": 12
              }
            }
            """.trimIndent(),
            responseCode = 200,
        )

        assertTrue(result.success)
        assertEquals("42", result.content)
        assertEquals("chatcmpl-123", result.requestId)
        assertEquals("deepseek-v4-flash", result.model)
        assertEquals(12, result.usage?.totalTokens)
    }

    @Test
    fun missingUsageUsesConservativeEstimateForAccounting() {
        val client = DeepSeekClient("")
        val result = client.parseChatCompletionResponse(
            """{"id":"chatcmpl-no-usage","model":"deepseek-v4-flash","choices":[{"message":{"content":"42"}}]}""",
            responseCode = 200,
        )
        val system = "系统提示：只返回数字"
        val user = "页面文本：到手约¥42"
        val maxTokens = 32
        val estimate = client.estimatedUsage(system, user, maxTokens)
        val utf8Bytes = (system + user).toByteArray(Charsets.UTF_8).size

        assertTrue(result.success)
        assertNull(result.usage)
        assertEquals(estimate, client.usageForAccounting(result, estimate))
        assertTrue(estimate.totalTokens >= utf8Bytes + maxTokens)

        val emptyEstimate = client.estimatedUsage("", "", maxTokens)
        assertEquals(64 + maxTokens, emptyEstimate.totalTokens)
    }

    @Test
    fun emptyOrPartialUsageIsNotTreatedAsActualUsage() {
        val client = DeepSeekClient("")
        val empty = DeepSeekUsage.fromJson(JSONObject("{}"))
        val partial = DeepSeekUsage.fromJson(JSONObject("""{"prompt_tokens": 4}"""))
        val estimate = client.estimatedUsage("system", "user", maxTokens = 8)
        val result = ChatCompletionResult(usage = empty, responseCode = 200, content = "ok")

        assertFalse(empty.isComplete)
        assertFalse(partial.isComplete)
        assertFalse(result.hasCompleteUsage)
        assertEquals(estimate, client.usageForAccounting(result, estimate))
    }

    @Test
    fun malformedCoreUsageIsIncompleteButLegalUsageRemainsComplete() {
        val malformedValues = listOf(-1, Double.NaN, Double.POSITIVE_INFINITY)
        malformedValues.forEach { malformed ->
            val usage = DeepSeekUsage.fromJson(
                JSONObject()
                    .put("prompt_tokens", malformed)
                    .put("completion_tokens", 1)
                    .put("total_tokens", 2),
            )
            assertFalse(usage.isComplete, "malformed prompt token: $malformed")
        }

        assertTrue(
            DeepSeekUsage.fromJson(
                JSONObject("""{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}"""),
            ).isComplete,
        )
    }

    @Test
    fun priceCatalogRejectsNegativeAndNonFiniteRates() {
        assertFailsWith<IllegalArgumentException> {
            DeepSeekPriceCatalog.flashOffPeak.copy(outputPriceUsdPerMillion = -0.01)
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekPriceCatalog.flashOffPeak.copy(cacheHitPriceUsdPerMillion = Double.NaN)
        }
        assertEquals(
            0.0,
            DeepSeekPriceCatalog.flashOffPeak.cost(DeepSeekUsage(cacheHitTokens = -1, completionTokens = -1)),
        )
    }

    @Test
    fun exchangeRateAndCnyCostRejectNonFiniteOrNegativeValues() {
        val client = DeepSeekClient("")

        assertEquals(0.0, client.safeExchangeRate(-1.0))
        assertEquals(0.0, client.safeExchangeRate(Double.NaN))
        assertEquals(0.0, client.safeExchangeRate(Double.POSITIVE_INFINITY))
        assertEquals(7.2, client.safeExchangeRate(7.2))
        assertEquals(0.0, client.safeCostCny(Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(0.0, client.safeCostCny(Double.NaN, 7.2))
        assertEquals(8.64, client.safeCostCny(1.2, 7.2), 0.000000001)
    }
}
