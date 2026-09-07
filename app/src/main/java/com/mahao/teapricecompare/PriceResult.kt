package com.mahao.teapricecompare

data class PriceResult(
    val platform: Platform,
    val price: Double? = null,
    val error: String? = null,
    val merchantDistance: String? = null,
    val meituanPrices: MeituanPriceSnapshot? = null,
    val cartStatus: CartStatus = CartStatus.UNKNOWN,
    val candidates: List<ProductCandidate> = emptyList(),
    val orderConstraints: OrderConstraints? = null,
) {
    val isSuccess: Boolean get() = price != null
}
