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
        if (!canStart(estimatedTokens, estimatedCostUsd, requiresRecoveryStep)) {
            budgetRejected = true
            return null
        }
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
        if (recoveryStepsUsed + inFlightRecoverySteps >= maxRecoverySteps) {
            budgetRejected = true
            return false
        }
        recoveryStepsUsed += 1
        return true
    }

    @Synchronized
    fun markRejected() {
        budgetRejected = true
    }

    @Synchronized
    fun usageSummary(): UsageSummary = UsageSummary(
        agentCalls = callsUsed,
        totalTokens = totalTokensUsed,
        costUsd = costUsdUsed,
        costCny = 0.0,
        recoverySteps = recoveryStepsUsed,
    )

    @Synchronized
    fun isExhausted(): Boolean = callsUsed >= maxCalls ||
        totalTokensUsed >= maxTotalTokens ||
        costUsdUsed >= maxCostUsd ||
        recoveryStepsUsed >= maxRecoverySteps ||
        budgetRejected

    private var inFlightCalls = 0
    private var inFlightTokens = 0L
    private var inFlightCostUsd = 0.0
    private var inFlightRecoverySteps = 0
    private var budgetRejected = false

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
            val promptValue = readToken(json, "prompt_tokens")
            val completionValue = readToken(json, "completion_tokens")
            val totalValue = readToken(json, "total_tokens")
            val prompt = promptValue ?: 0
            val completion = completionValue ?: 0
            val total = totalValue ?: 0

            val directReasoning = readOptionalToken(json, "reasoning_tokens")
            val detailsPresent = json.has("completion_tokens_details")
            val details = json.optJSONObject("completion_tokens_details")
            val detailReasoning = details?.let { readOptionalToken(it, "reasoning_tokens") }
            val reasoning = when {
                directReasoning != null && directReasoning != 0 -> directReasoning
                detailReasoning != null -> detailReasoning
                else -> directReasoning ?: 0
            }
            val reasoningValid = (!json.has("reasoning_tokens") || directReasoning != null) &&
                (!detailsPresent || details != null) &&
                (details?.let { !it.has("reasoning_tokens") || detailReasoning != null } ?: true)

            val hitValue = readOptionalToken(json, "prompt_cache_hit_tokens")
            val missValue = readOptionalToken(json, "prompt_cache_miss_tokens")
            val cacheFieldsValid = (!json.has("prompt_cache_hit_tokens") || hitValue != null) &&
                (!json.has("prompt_cache_miss_tokens") || missValue != null)
            val hit = when {
                hitValue != null -> hitValue
                missValue != null -> (prompt - missValue).coerceAtLeast(0)
                else -> 0
            }
            val miss = when {
                missValue != null -> missValue
                hitValue != null -> (prompt - hitValue).coerceAtLeast(0)
                else -> prompt
            }
            val cacheConsistent = hit.toLong() + miss.toLong() == prompt.toLong()
            val isComplete = promptValue != null &&
                completionValue != null &&
                totalValue != null &&
                reasoningValid &&
                cacheFieldsValid &&
                cacheConsistent &&
                total.toLong() == prompt.toLong() + completion.toLong()
            return DeepSeekUsage(prompt, completion, reasoning, hit, miss, total, isComplete)
        }

        private fun readOptionalToken(json: JSONObject, key: String): Int? =
            if (json.has(key)) readToken(json, key) else null

        private fun readToken(json: JSONObject, key: String): Int? {
            val value = json.opt(key) as? Number ?: return null
            val doubleValue = value.toDouble()
            if (!doubleValue.isFinite() ||
                doubleValue < 0.0 ||
                doubleValue > Int.MAX_VALUE.toDouble() ||
                doubleValue != value.toLong().toDouble()
            ) {
                return null
            }
            return value.toLong().toInt()
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
