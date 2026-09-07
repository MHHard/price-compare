package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonStatusTest {

    @Test
    fun noPriceIsNotCompleted() {
        val result = ComparisonResultState.from(
            stores = listOf(storeWithoutPrices()),
            budgetExceeded = false,
        )

        assertEquals(ComparisonStatus.NO_VERIFIED_PRICE, result.status)
        assertEquals(null, result.cheapest)
    }

    @Test
    fun oneVerifiedModeProducesSuccess() {
        val result = ComparisonResultState.from(
            stores = listOf(storeWithPrice(8.0)),
            budgetExceeded = false,
        )

        assertEquals(ComparisonStatus.SUCCESS, result.status)
        assertEquals(8.0, result.cheapest?.second?.price)
    }

    private fun storeWithoutPrices() = MeituanStoreComparison(
        storeName = "无价格店",
        voucher = MeituanModePrice(MeituanRoute.VOUCHER, error = "未读取到价格"),
        delivery = MeituanModePrice(MeituanRoute.DELIVERY, error = "未读取到价格"),
        pickup = MeituanModePrice(MeituanRoute.PICKUP, error = "未读取到价格"),
    )

    private fun storeWithPrice(price: Double) = MeituanStoreComparison(
        storeName = "有效价格店",
        delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = price),
    )
}
