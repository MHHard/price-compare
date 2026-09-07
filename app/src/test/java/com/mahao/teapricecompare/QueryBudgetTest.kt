package com.mahao.teapricecompare

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun recoveryStepsHaveTheirOwnBound() {
        val budget = QueryBudget()

        repeat(QueryBudget.MAX_RECOVERY_STEPS) {
            assertTrue(budget.recordRecoveryStep())
        }

        assertFalse(budget.recordRecoveryStep())
        assertFalse(budget.canStart())
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
}
