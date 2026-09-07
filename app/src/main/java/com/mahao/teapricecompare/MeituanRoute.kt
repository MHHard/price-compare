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
)

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
        get() = listOf(voucher, delivery, pickup).filter { it.price != null }

    val cheapest: MeituanModePrice?
        get() = availableModes.minByOrNull { it.price!! }
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
