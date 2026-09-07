package com.mahao.teapricecompare

enum class MeituanRoute {
    VOUCHER,
    DELIVERY,
    PICKUP,
}

data class MeituanModePrice(
    val mode: MeituanRoute,
    val price: Double? = null,
    val error: String? = null,
    val candidates: List<ProductCandidate> = emptyList(),
    val orderConstraints: OrderConstraints? = null,
) {
    val isRecommended: Boolean
        get() = isValidComparisonPrice(price, orderConstraints)
}

data class MeituanPriceSnapshot(
    val delivery: MeituanModePrice,
    val pickup: MeituanModePrice,
    val merchantDistance: String? = null,
)

data class MeituanStoreComparison(
    val storeName: String,
    val merchantDistance: String? = null,
    val voucher: MeituanModePrice = MeituanModePrice(MeituanRoute.VOUCHER),
    val delivery: MeituanModePrice = MeituanModePrice(MeituanRoute.DELIVERY),
    val pickup: MeituanModePrice = MeituanModePrice(MeituanRoute.PICKUP),
) {
    val availableModes: List<MeituanModePrice>
        get() = allModes.filter { it.isRecommended }

    val cheapest: MeituanModePrice?
        get() = availableModes.minByOrNull { it.price!! }

    private val allModes: List<MeituanModePrice>
        get() = listOf(voucher, delivery, pickup)
}

data class MeituanComparisonResult(
    val stores: List<MeituanStoreComparison> = emptyList(),
    val error: String? = null,
) {
    val cheapest: Pair<MeituanStoreComparison, MeituanModePrice>?
        get() = stores.mapNotNull { store ->
            store.cheapest?.let { store to it }
        }.minByOrNull { it.second.price!! }
}
