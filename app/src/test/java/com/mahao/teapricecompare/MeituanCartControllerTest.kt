package com.mahao.teapricecompare

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeituanCartControllerTest {

    @BeforeTest
    fun resetSessionConsent() {
        MeituanCartController.resetSessionForTests()
    }

    @Test
    fun clearCartWithoutConsentIsRejectedBeforeOpeningCart() = runBlocking {
        val accessibility = RecordingCartAccessibility()
        val result = MeituanCartController(accessibility).clearCart()

        assertEquals(CartClearStatus.NOT_AUTHORIZED, result.status)
        assertEquals(0, accessibility.openCartCalls)
        assertEquals(CartConsentState.NOT_ASKED, MeituanCartController().consentState)
    }

    @Test
    fun consentErrorNamesTheStorePendingPaymentCart() = runBlocking {
        val result = MeituanCartController(RecordingCartAccessibility()).clearCart()

        assertTrue(result.reason?.contains("店内待付款购物车") == true)
    }

    @Test
    fun rejectingConsentKeepsAutomaticCartActionsDisabled() = runBlocking {
        val controller = MeituanCartController(RecordingCartAccessibility())
        controller.rejectConsent()

        assertEquals(CartConsentState.DECLINED, controller.consentState)
        assertFalse(controller.canCompare)
        assertEquals(CartClearStatus.NOT_AUTHORIZED, controller.clearCart().status)
    }

    @Test
    fun acceptingConsentEnablesOnlyThisProcessSession() {
        val controller = MeituanCartController(RecordingCartAccessibility())
        controller.acceptConsent()

        assertEquals(CartConsentState.ACCEPTED, controller.consentState)
        assertTrue(controller.canCompare)
        assertEquals(CartConsentState.ACCEPTED, MeituanCartController().consentState)
    }

    @Test
    fun authorizedClearStopsOnAnUnrecognizedRowWithoutClicking() = runBlocking {
        val accessibility = RecordingCartAccessibility(
            observations = listOf(
                CartObservation(hasEmptyMarker = false, rows = emptyList(), unknownRowCount = 1),
            ),
        )
        val controller = MeituanCartController(accessibility)
        controller.acceptConsent()

        val result = controller.clearCart()

        assertEquals(CartClearStatus.CLEAR_FAILED, result.status)
        assertEquals(1, accessibility.readCartCalls)
        assertEquals(0, accessibility.minusClicks)
    }

    @Test
    fun unknownStoreRowIsNotDeleted() = runBlocking {
        val accessibility = RecordingCartAccessibility(
            observations = listOf(
                CartObservation(
                    hasEmptyMarker = false,
                    rows = listOf(CartItemRow("", "未知商品", 1, "unknown")),
                ),
            ),
        )
        val controller = MeituanCartController(accessibility)
        controller.acceptConsent()

        assertEquals(CartClearStatus.CLEAR_FAILED, controller.clearCart().status)
        assertEquals(0, accessibility.minusClicks)
    }

    @Test
    fun authorizedClearRequiresBothEmptyMarkerAndNoRows() = runBlocking {
        val accessibility = RecordingCartAccessibility(
            observations = listOf(CartObservation(hasEmptyMarker = true, rows = emptyList())),
        )
        val controller = MeituanCartController(accessibility)
        controller.acceptConsent()

        assertEquals(CartClearStatus.CLEARED, controller.clearCart().status)
    }

    @Test
    fun failedMinusActionStopsWithoutReadingOrClickingAgain() = runBlocking {
        val accessibility = RecordingCartAccessibility(
            observations = listOf(
                CartObservation(
                    hasEmptyMarker = false,
                    rows = listOf(CartItemRow("喜茶人民广场店", "芝芝莓莓", 1, "row-1")),
                ),
            ),
            minusResults = listOf(false),
        )
        val controller = MeituanCartController(accessibility)
        controller.acceptConsent()

        assertEquals(CartClearStatus.CLEAR_FAILED, controller.clearCart().status)
        assertEquals(1, accessibility.readCartCalls)
        assertEquals(1, accessibility.minusClicks)
    }

    @Test
    fun successfulMinusIsFollowedByAFreshCartRead() = runBlocking {
        val accessibility = RecordingCartAccessibility(
            observations = listOf(
                CartObservation(
                    hasEmptyMarker = false,
                    rows = listOf(CartItemRow("喜茶人民广场店", "芝芝莓莓", 2, "row-1")),
                ),
                CartObservation(
                    hasEmptyMarker = true,
                    rows = emptyList(),
                ),
            ),
            minusResults = listOf(true),
        )
        val controller = MeituanCartController(accessibility)
        controller.acceptConsent()

        assertEquals(CartClearStatus.CLEARED, controller.clearCart().status)
        assertEquals(2, accessibility.readCartCalls)
        assertEquals(1, accessibility.minusClicks)
    }

    private class RecordingCartAccessibility(
        private val observations: List<CartObservation> = emptyList(),
        private val minusResults: List<Boolean> = emptyList(),
    ) : MeituanCartAccessibility {
        var openCartCalls = 0
        var readCartCalls = 0
        var minusClicks = 0

        override fun openCart(): Boolean {
            openCartCalls += 1
            return true
        }

        override fun readCart(): CartObservation? {
            readCartCalls += 1
            return observations.getOrNull(readCartCalls - 1)
        }

        override fun clickMinus(row: CartItemRow): Boolean {
            minusClicks += 1
            return minusResults.getOrNull(minusClicks - 1) ?: false
        }
    }
}
