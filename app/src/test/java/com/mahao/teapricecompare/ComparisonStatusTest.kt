package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun oneVerifiedModeProducesPartial() {
        val result = ComparisonResultState.from(
            stores = listOf(storeWithPrice(8.0)),
            budgetExceeded = false,
        )

        assertEquals(ComparisonStatus.PARTIAL, result.status)
        assertEquals(8.0, result.cheapest?.second?.price)
    }

    @Test
    fun allModesVerifiedProducesSuccess() {
        val result = ComparisonResultState.from(
            stores = listOf(
                MeituanStoreComparison(
                    storeName = "完整价格店",
                    voucher = MeituanModePrice(MeituanRoute.VOUCHER, price = 8.0),
                    delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 9.0),
                    pickup = MeituanModePrice(MeituanRoute.PICKUP, price = 7.0),
                ),
            ),
            budgetExceeded = false,
        )

        assertEquals(ComparisonStatus.SUCCESS, result.status)
        assertEquals(MeituanRoute.PICKUP, result.cheapest?.second?.mode)
    }

    @Test
    fun invalidPricesAndExplicitlyUnorderableModesDoNotProduceCheapest() {
        val store = MeituanStoreComparison(
            storeName = "不可推荐店",
            voucher = MeituanModePrice(MeituanRoute.VOUCHER, price = -1.0),
            delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = Double.NaN),
            pickup = MeituanModePrice(
                MeituanRoute.PICKUP,
                price = 7.0,
                orderConstraints = OrderConstraints(isOrderable = false),
            ),
        )

        assertTrue(store.availableModes.isEmpty())
        assertEquals(null, store.cheapest)
        assertEquals(
            ComparisonStatus.NO_VERIFIED_PRICE,
            ComparisonResultState.from(listOf(store), budgetExceeded = false).status,
        )
    }

    @Test
    fun infinitePriceDoesNotProduceCheapest() {
        val store = MeituanStoreComparison(
            storeName = "无穷价格店",
            delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = Double.POSITIVE_INFINITY),
        )

        assertTrue(store.availableModes.isEmpty())
        assertEquals(null, MeituanComparisonResult(stores = listOf(store)).cheapest)
    }

    @Test
    fun nullConstraintsRemainCompatibleWithVerifiedPrice() {
        val result = PriceResult(Platform.MEITUAN_DELIVERY, price = 8.0)

        assertTrue(result.isSuccess)
        assertEquals(8.0, result.price)
    }

    @Test
    fun invalidPriceMakesPriceResultUnsuccessful() {
        assertFalse(PriceResult(Platform.MEITUAN, price = -0.01).isSuccess)
        assertFalse(PriceResult(Platform.MEITUAN, price = Double.NaN).isSuccess)
        assertFalse(PriceResult(Platform.MEITUAN, price = Double.NEGATIVE_INFINITY).isSuccess)
        assertFalse(
            PriceResult(
                Platform.MEITUAN,
                price = 8.0,
                orderConstraints = OrderConstraints(isOrderable = false),
            ).isSuccess,
        )
    }

    @Test
    fun budgetExceededKeepsVerifiedCheapest() {
        val result = ComparisonResultState.from(
            stores = listOf(
                MeituanStoreComparison(
                    storeName = "预算停止店",
                    delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 8.0),
                ),
            ),
            budgetExceeded = true,
        )

        assertEquals(ComparisonStatus.BUDGET_EXCEEDED, result.status)
        assertEquals(8.0, result.cheapest?.second?.price)
    }

    @Test
    fun emptyStoreListHasNoVerifiedPrice() {
        val result = ComparisonResultState.from(emptyList(), budgetExceeded = false)

        assertEquals(ComparisonStatus.NO_VERIFIED_PRICE, result.status)
        assertEquals(null, result.cheapest)
    }

    @Test
    fun snapshotFailureReasonDefaultsToNull() {
        val snapshot = ComparisonSnapshot(
            queryId = "query-1",
            target = PlatformTarget("蜜雪冰城", "柠檬水"),
            stores = emptyList(),
            status = ComparisonStatus.NO_VERIFIED_PRICE,
        )

        assertEquals(null, snapshot.failureReason)
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
