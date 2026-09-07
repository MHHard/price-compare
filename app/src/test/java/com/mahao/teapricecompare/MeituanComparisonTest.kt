package com.mahao.teapricecompare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeituanComparisonTest {

    @Test
    fun cheapestIncludesVoucherDeliveryAndPickup() {
        val result = MeituanComparisonResult(
            stores = listOf(
                MeituanStoreComparison(
                    storeName = "蜜雪冰城A",
                    voucher = MeituanModePrice(MeituanRoute.VOUCHER, price = 6.0),
                    delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 8.6),
                    pickup = MeituanModePrice(MeituanRoute.PICKUP, price = 4.78),
                ),
            ),
        )

        assertEquals("蜜雪冰城A", result.cheapest?.first?.storeName)
        assertEquals(MeituanRoute.PICKUP, result.cheapest?.second?.mode)
        assertEquals(4.78, result.cheapest?.second?.price)
    }

    @Test
    fun unavailableModeDoesNotBlockAnotherMode() {
        val result = MeituanComparisonResult(
            stores = listOf(
                MeituanStoreComparison(
                    storeName = "自取不可用店",
                    delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 9.9),
                    pickup = MeituanModePrice(MeituanRoute.PICKUP, error = "商品不支持自取"),
                ),
            ),
        )

        assertEquals(MeituanRoute.DELIVERY, result.cheapest?.second?.mode)
        assertNull(result.stores.single().pickup.price)
    }
}
