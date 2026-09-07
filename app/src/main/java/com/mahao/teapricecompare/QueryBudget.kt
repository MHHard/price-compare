package com.mahao.teapricecompare

import org.json.JSONObject

/** Hard limits for one query. A record is actual usage, not a reversible reservation. */
class QueryBudget {
    val maxCalls = MAX_CALLS
    val maxTotalTokens = MAX_TOTAL_TOKENS
    val maxCostUsd = MAX_COST_USD
    val maxRecoverySteps = MAX_RECOVERY_STEPS

    var callsUsed = 0
        private set
    var totalTokensUsed = 0
        private set
    var costUsdUsed = 0.0
        private set
    var recoveryStepsUsed = 0
        private set

    @Synchronized
    fun canStart(estimatedTokens: Int = 0, estimatedCostUsd: Double = 0.0): Boolean {
        val tokens = estimatedTokens.coerceAtLeast(0)
        val cost = estimatedCostUsd.coerceAtLeast(0.0)
        return callsUsed < maxCalls &&
            totalTokensUsed < maxTotalTokens &&
            totalTokensUsed + tokens <= maxTotalTokens &&
            costUsdUsed < maxCostUsd &&
            costUsdUsed + cost <= maxCostUsd &&
            recoveryStepsUsed < maxRecoverySteps
    }

    @Synchronized
    fun record(usage: DeepSeekUsage, costUsd: Double) {
        record(usage.totalTokens, costUsd)
    }

    @Synchronized
    fun record(tokens: Int, costUsd: Double) {
        callsUsed += 1
        totalTokensUsed += tokens.coerceAtLeast(0)
        costUsdUsed += costUsd.coerceAtLeast(0.0)
    }

    @Synchronized
    fun recordRecoveryStep(): Boolean {
        if (recoveryStepsUsed >= maxRecoverySteps) return false
        recoveryStepsUsed += 1
        return true
    }

    companion object {
        const val MAX_CALLS = 6
        const val MAX_TOTAL_TOKENS = 16_000
        const val MAX_COST_USD = 0.02
        const val MAX_RECOVERY_STEPS = 3
    }
}

data class DeepSeekUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject): DeepSeekUsage {
            val prompt = json.optInt("prompt_tokens", 0).coerceAtLeast(0)
            val completion = json.optInt("completion_tokens", 0).coerceAtLeast(0)
            val reasoning = json.optInt("reasoning_tokens", 0).coerceAtLeast(0).let { direct ->
                if (direct != 0) direct else {
                    json.optJSONObject("completion_tokens_details")
                        ?.optInt("reasoning_tokens", 0)
                        ?.coerceAtLeast(0)
                        ?: 0
                }
            }
            val hasHit = json.has("prompt_cache_hit_tokens")
            val hasMiss = json.has("prompt_cache_miss_tokens")
            val declaredHit = json.optInt("prompt_cache_hit_tokens", 0).coerceAtLeast(0)
            val declaredMiss = json.optInt("prompt_cache_miss_tokens", 0).coerceAtLeast(0)
            val hit = when {
                hasHit -> declaredHit.coerceAtMost(prompt)
                hasMiss -> (prompt - declaredMiss).coerceAtLeast(0)
                else -> 0
            }
            val miss = when {
                hasMiss -> declaredMiss.coerceAtMost((prompt - hit).coerceAtLeast(0))
                else -> (prompt - hit).coerceAtLeast(0)
            }
            val total = if (json.has("total_tokens")) {
                json.optInt("total_tokens", prompt + completion).coerceAtLeast(0)
            } else {
                prompt + completion
            }
            return DeepSeekUsage(prompt, completion, reasoning, hit, miss, total)
        }
    }
}

data class DeepSeekPriceCatalog(
    val model: String,
    val priceVersion: String,
    val billingPeriod: String,
    val cacheHitPriceUsdPerMillion: Double,
    val cacheMissPriceUsdPerMillion: Double,
    val outputPriceUsdPerMillion: Double,
) {
    fun cost(usage: DeepSeekUsage): Double = (
        usage.cacheHitTokens * cacheHitPriceUsdPerMillion +
            usage.cacheMissTokens * cacheMissPriceUsdPerMillion +
            usage.completionTokens * outputPriceUsdPerMillion
        ) / 1_000_000.0

    companion object {
        val flashOffPeak = DeepSeekPriceCatalog(
            model = "deepseek-v4-flash",
            priceVersion = "deepseek-v4-flash-2026-08-16",
            billingPeriod = "off_peak",
            cacheHitPriceUsdPerMillion = 0.007,
            cacheMissPriceUsdPerMillion = 0.22,
            outputPriceUsdPerMillion = 0.66,
        )

        val flashPeak = flashOffPeak.copy(
            billingPeriod = "peak",
            cacheHitPriceUsdPerMillion = 0.014,
            cacheMissPriceUsdPerMillion = 0.44,
            outputPriceUsdPerMillion = 1.32,
        )
    }
}
