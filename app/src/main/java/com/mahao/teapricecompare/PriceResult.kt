package com.mahao.teapricecompare

data class PriceResult(
    val platform: Platform,
    val price: Double? = null,
    val error: String? = null,
    val merchantDistance: String? = null,
    val meituanPrices: MeituanPriceSnapshot? = null,
) {
    val isSuccess: Boolean get() = price != null
}
