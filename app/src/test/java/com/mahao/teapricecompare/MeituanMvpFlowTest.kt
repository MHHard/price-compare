package com.mahao.teapricecompare

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeituanMvpFlowTest {

    @Test
    fun eachCandidateUsesVerifiedDeliveryThenVoucherFlow() {
        val plan = buildMeituanMvpRunPlan(listOf("蜜雪冰城浦江店", "蜜雪冰城万达店"))

        assertEquals(
            listOf(
                MeituanMvpRun("蜜雪冰城浦江店", MeituanRoute.DELIVERY),
                MeituanMvpRun("蜜雪冰城浦江店", MeituanRoute.VOUCHER),
                MeituanMvpRun("蜜雪冰城万达店", MeituanRoute.DELIVERY),
                MeituanMvpRun("蜜雪冰城万达店", MeituanRoute.VOUCHER),
            ),
            plan,
        )
    }

    @Test
    fun deliveryResultKeepsBothDrawerPricesAndDoesNotTurnItIntoVoucher() {
        val result = PriceResult(
            platform = Platform.MEITUAN_DELIVERY,
            price = 12.0,
            merchantDistance = "距您 904m",
            meituanPrices = MeituanPriceSnapshot(
                delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 12.0),
                pickup = MeituanModePrice(MeituanRoute.PICKUP, price = 8.0),
                merchantDistance = "距您 904m",
            ),
        )

        val comparison = priceResultToMeituanStoreComparison("蜜雪冰城浦江店", result)

        assertEquals(12.0, comparison.delivery.price)
        assertEquals(8.0, comparison.pickup.price)
        assertEquals("距您 904m", comparison.merchantDistance)
        assertEquals(null, comparison.voucher.price)
    }

    @Test
    fun priceResultConversionKeepsOrderConstraintsAndCandidatesOnEachMode() {
        val candidates = listOf(ProductCandidate("加料", price = 2.0, isAddable = true))
        val constraints = OrderConstraints(subtotal = 12.0, isOrderable = false)
        val result = PriceResult(
            platform = Platform.MEITUAN_DELIVERY,
            price = 12.0,
            candidates = candidates,
            orderConstraints = constraints,
            meituanPrices = MeituanPriceSnapshot(
                delivery = MeituanModePrice(MeituanRoute.DELIVERY, price = 12.0),
                pickup = MeituanModePrice(MeituanRoute.PICKUP, price = 8.0),
            ),
        )

        val comparison = priceResultToMeituanStoreComparison("约束店", result)

        assertEquals(constraints, comparison.voucher.orderConstraints)
        assertEquals(constraints, comparison.delivery.orderConstraints)
        assertEquals(constraints, comparison.pickup.orderConstraints)
        assertEquals(candidates, comparison.voucher.candidates)
        assertEquals(candidates, comparison.delivery.candidates)
        assertEquals(candidates, comparison.pickup.candidates)
        assertTrue(comparison.availableModes.isEmpty())
    }

    @Test
    fun meituanLaunchIntentBringsExistingTaskToFront() {
        val flags = meituanLaunchFlags()

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }
}
