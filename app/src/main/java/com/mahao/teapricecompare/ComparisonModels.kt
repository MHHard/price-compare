package com.mahao.teapricecompare

enum class ComparisonStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    NO_VERIFIED_PRICE,
    BUDGET_EXCEEDED,
    FAILED,
}

enum class CartStatus {
    UNKNOWN,
    EMPTY,
    HAS_ITEMS,
    CLEARING,
    CLEAR_FAILED,
}

data class ProductCandidate(
    val name: String,
    val price: Double? = null,
    val specSummary: String? = null,
    val isTarget: Boolean = false,
    val isAddable: Boolean = false,
)

data class OrderConstraints(
    val subtotal: Double = 0.0,
    val minimumOrder: Double = 0.0,
    val gap: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val packingFee: Double = 0.0,
    val isOrderable: Boolean = false,
)

internal fun isValidComparisonPrice(
    price: Double?,
    orderConstraints: OrderConstraints?,
): Boolean = price?.let { it >= 0.0 && it.isFinite() } == true &&
    orderConstraints?.isOrderable != false

data class UsageSummary(
    val agentCalls: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Double = 0.0,
    val costCny: Double = 0.0,
    val recoverySteps: Int = 0,
)

data class ComparisonResultState(
    val status: ComparisonStatus,
    val cheapest: Pair<MeituanStoreComparison, MeituanModePrice>? = null,
) {

    companion object {
        fun from(
            stores: List<MeituanStoreComparison>,
            budgetExceeded: Boolean,
        ): ComparisonResultState {
            val cheapest = stores.asSequence()
                .flatMap { store ->
                    store.availableModes.asSequence().map { mode -> store to mode }
                }
                .minByOrNull { (_, mode) -> mode.price!! }

            val verifiedStoreCount = stores.count { it.availableModes.isNotEmpty() }
            val status = when {
                budgetExceeded -> ComparisonStatus.BUDGET_EXCEEDED
                cheapest == null -> ComparisonStatus.NO_VERIFIED_PRICE
                stores.size > 1 && verifiedStoreCount in 1 until stores.size -> ComparisonStatus.PARTIAL
                else -> ComparisonStatus.SUCCESS
            }
            return ComparisonResultState(status = status, cheapest = cheapest)
        }
    }
}

data class ComparisonSnapshot(
    val queryId: String,
    val target: PlatformTarget,
    val stores: List<MeituanStoreComparison>,
    val status: ComparisonStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val usageSummary: UsageSummary = UsageSummary(),
    val cartNotice: String? = null,
    val failureReason: String? = null,
)
