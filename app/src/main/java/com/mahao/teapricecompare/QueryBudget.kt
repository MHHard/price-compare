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
    fun canStart(
        estimatedTokens: Int = 0,
        estimatedCostUsd: Double = 0.0,
        requiresRecoveryStep: Boolean = false,
    ): Boolean {
        val tokens = estimatedTokens.coerceAtLeast(0)
        val cost = estimatedCostUsd.takeIf { it.isFinite() && it >= 0.0 } ?: return false
        return callsUsed + inFlightCalls < maxCalls &&
            totalTokensUsed.toLong() + inFlightTokens < maxTotalTokens.toLong() &&
            totalTokensUsed.toLong() + inFlightTokens + tokens.toLong() <= maxTotalTokens.toLong() &&
            costUsdUsed + inFlightCostUsd < maxCostUsd &&
            costUsdUsed + inFlightCostUsd + cost <= maxCostUsd &&
            (!requiresRecoveryStep || recoveryStepsUsed + inFlightRecoverySteps < maxRecoverySteps)
    }

    @Synchronized
    fun canStartRecovery(estimatedTokens: Int = 0, estimatedCostUsd: Double = 0.0): Boolean =
        canStart(estimatedTokens, estimatedCostUsd, requiresRecoveryStep = true)

    @Synchronized
    fun reserve(
        estimatedTokens: Int = 0,
        estimatedCostUsd: Double = 0.0,
        requiresRecoveryStep: Boolean = false,
    ): QueryBudgetReservation? {
        if (!canStart(estimatedTokens, estimatedCostUsd, requiresRecoveryStep)) return null
        val tokens = estimatedTokens.coerceAtLeast(0)
        val cost = estimatedCostUsd.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        inFlightCalls += 1
        inFlightTokens += tokens.toLong()
        inFlightCostUsd += cost
        if (requiresRecoveryStep) inFlightRecoverySteps += 1
        return QueryBudgetReservation(tokens, cost, requiresRecoveryStep)
    }

    @Synchronized
    fun settle(reservation: QueryBudgetReservation, usage: DeepSeekUsage, costUsd: Double): Boolean {
        if (!reservation.active) return false
        reservation.active = false
        inFlightCalls -= 1
        inFlightTokens -= reservation.estimatedTokens.toLong()
        inFlightCostUsd -= reservation.estimatedCostUsd
        if (reservation.requiresRecoveryStep) {
            inFlightRecoverySteps -= 1
            recoveryStepsUsed += 1
        }
        recordUsage(usage.totalTokens, costUsd)
        return true
    }

    @Synchronized
    fun record(usage: DeepSeekUsage, costUsd: Double) {
        record(usage.totalTokens, costUsd)
    }

    @Synchronized
    fun record(tokens: Int, costUsd: Double) {
        recordUsage(tokens, costUsd)
    }

    private fun recordUsage(tokens: Int, costUsd: Double) {
        callsUsed += 1
        totalTokensUsed = (totalTokensUsed.toLong() + tokens.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        costUsdUsed += costUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    @Synchronized
    fun recordRecoveryStep(): Boolean {
        if (recoveryStepsUsed + inFlightRecoverySteps >= maxRecoverySteps) return false
        recoveryStepsUsed += 1
        return true
    }

    private var inFlightCalls = 0
    private var inFlightTokens = 0L
    private var inFlightCostUsd = 0.0
    private var inFlightRecoverySteps = 0

    companion object {
        const val MAX_CALLS = 6
        const val MAX_TOTAL_TOKENS = 16_000
        const val MAX_COST_USD = 0.02
        const val MAX_RECOVERY_STEPS = 3
    }
}

class QueryBudgetReservation internal constructor(
    internal val estimatedTokens: Int,
    internal val estimatedCostUsd: Double,
    internal val requiresRecoveryStep: Boolean,
) {
    internal var active: Boolean = true
}

data class DeepSeekUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val totalTokens: Int = 0,
    val isComplete: Boolean = true,
) {
    companion object {
        fun fromJson(json: JSONObject): DeepSeekUsage {
            val prompt = readToken(json, "prompt_tokens")
            val completion = readToken(json, "completion_tokens")
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
            val total = readToken(json, "total_tokens")
            val isComplete = hasToken(json, "prompt_tokens") &&
                hasToken(json, "completion_tokens") &&
                hasToken(json, "total_tokens")
            return DeepSeekUsage(prompt, completion, reasoning, hit, miss, total, isComplete)
        }

        private fun hasToken(json: JSONObject, key: String): Boolean =
            json.has(key) && !json.isNull(key) && json.opt(key) is Number

        private fun readToken(json: JSONObject, key: String): Int =
            (json.opt(key) as? Number)?.toLong()
                ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0
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
    init {
        require(cacheHitPriceUsdPerMillion.isFinite() && cacheHitPriceUsdPerMillion >= 0.0)
        require(cacheMissPriceUsdPerMillion.isFinite() && cacheMissPriceUsdPerMillion >= 0.0)
        require(outputPriceUsdPerMillion.isFinite() && outputPriceUsdPerMillion >= 0.0)
    }

    fun cost(usage: DeepSeekUsage): Double {
        val value = (
            usage.cacheHitTokens.coerceAtLeast(0) * cacheHitPriceUsdPerMillion +
                usage.cacheMissTokens.coerceAtLeast(0) * cacheMissPriceUsdPerMillion +
                usage.completionTokens.coerceAtLeast(0) * outputPriceUsdPerMillion
            ) / 1_000_000.0
        return value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

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
