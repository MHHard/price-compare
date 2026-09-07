package com.mahao.teapricecompare

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object MeituanSelectors {

    private const val SEARCH_HOME_ID = ":id/search_layout_area"
    private const val SEARCH_INPUT_ID = ":id/search_edit_text"
    private const val SEARCH_BUTTON_ID = ":id/search_button"
    private const val SEARCH_RESULT_LIST_ID = ":id/f2c"
    private const val DELIVERY_ENTRY_ID = ":id/self_pick_waimai_channel_icon"
    private const val DELIVERY_SEARCH_HOME_ID = ":id/homepage_search_icon"
    private const val DELIVERY_SEARCH_BUTTON_ID = ":id/bes"
    private const val DELIVERY_SEARCH_INPUT_ID = ":id/il5"
    private const val DELIVERY_RESULT_PAGE_ID = ":id/result_view_pager"
    private const val DELIVERY_RESULT_LIST_ID = ":id/s9d"
    private val EMPTY_CART_MARKERS = setOf("购物车是空的", "购物车为空", "暂无商品", "还没有商品", "空空如也")
    private val CART_ENTRY_LABELS = setOf("购物车", "查看购物车", "已加购商品")

    fun isSearchHome(node: AccessibilityNodeInfo): Boolean =
        hasId(node, SEARCH_HOME_ID) ||
            node.contentDescription?.toString()?.contains("搜索框，点击可搜索") == true

    fun isDeliveryEntry(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        if (hasId(node, DELIVERY_ENTRY_ID)) return true
        return isMainDeliveryEntryCandidate(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = true,
            top = bounds.top,
            bottom = bounds.bottom,
        )
    }

    fun isMainDeliveryEntryCandidate(
        text: String?,
        contentDescription: String?,
        clickable: Boolean,
        top: Int,
        bottom: Int,
    ): Boolean =
        clickable &&
            isDeliveryEntryLabel(text, contentDescription) &&
            top >= 250 &&
            bottom <= 1000 &&
            bottom > top

    fun isDeliveryEntryLabel(text: String?, contentDescription: String?): Boolean =
        text == "外卖" || contentDescription == "外卖"

    fun isDeliverySearchHome(node: AccessibilityNodeInfo): Boolean =
        isDeliverySearchHomeMarker(
            resourceId = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
        )

    fun isDeliverySearchHomeMarker(resourceId: String?, contentDescription: String?): Boolean =
        resourceId?.endsWith(DELIVERY_SEARCH_HOME_ID) == true

    fun isDeliverySearchInput(node: AccessibilityNodeInfo): Boolean =
        (hasId(node, DELIVERY_SEARCH_INPUT_ID) || isSearchInput(node)) &&
            node.className?.toString() == "android.widget.EditText"

    fun isSearchInput(node: AccessibilityNodeInfo): Boolean =
        hasId(node, SEARCH_INPUT_ID) && node.className?.toString() == "android.widget.EditText"

    fun isSearchButton(node: AccessibilityNodeInfo): Boolean =
        hasId(node, SEARCH_BUTTON_ID) && node.text?.toString() == "搜索"

    fun isDeliverySearchButton(node: AccessibilityNodeInfo): Boolean =
        (hasId(node, DELIVERY_SEARCH_BUTTON_ID) || isSearchButton(node)) &&
            node.text?.toString() == "搜索"

    fun isSearchResultList(packageName: String?, resourceId: String?): Boolean =
        packageName == Platform.MEITUAN.packageName && resourceId?.endsWith(SEARCH_RESULT_LIST_ID) == true

    fun isDeliverySearchResult(packageName: String?, resourceId: String?): Boolean =
        packageName == Platform.MEITUAN.packageName &&
            (resourceId?.endsWith(DELIVERY_RESULT_PAGE_ID) == true ||
                resourceId?.endsWith(DELIVERY_RESULT_LIST_ID) == true)

    fun isStorePage(texts: Iterable<String>, storeKeyword: String): Boolean =
        texts.any { it == "定商品" } && texts.any { it.contains(storeKeyword) }

    fun isDeliveryStorePage(texts: Iterable<String>, storeKeyword: String): Boolean =
        texts.any { it == "点菜" } && texts.any { it.contains(storeKeyword) }

    fun isDeliveryStoreCandidate(
        text: String?,
        storeKeyword: String,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean =
        storeKeyword.isNotBlank() &&
            text?.trim()?.contains(storeKeyword.trim()) == true &&
            top in 650..2300 &&
            width >= 160 &&
            height >= 30

    fun isSpecPopupMarker(text: String?, contentDescription: String?): Boolean =
        text == "规格" || text?.startsWith("已选规格：") == true || contentDescription == "关闭选规格"

    fun isSpecAddToCart(text: String?, contentDescription: String?): Boolean =
        text == "加入购物车" || contentDescription?.contains("加入购物车") == true

    fun isMinimumOrderText(text: String?): Boolean =
        text?.contains("起送") == true

    fun isCartDrawerMarker(text: String?, contentDescription: String?): Boolean =
        text == "已加购商品" || contentDescription == "已加购商品"

    fun isCartEntry(text: String?, contentDescription: String?, resourceId: String?): Boolean =
        text in CART_ENTRY_LABELS ||
            contentDescription in CART_ENTRY_LABELS ||
            resourceId?.let {
                it.endsWith(":id/cart") ||
                    it.endsWith(":id/shopping_cart") ||
                    it.endsWith(":id/cart_icon")
            } == true

    fun isEmptyCartMarker(text: String?, contentDescription: String?): Boolean =
        listOfNotNull(text, contentDescription).any { value ->
            EMPTY_CART_MARKERS.any(value::contains)
        }

    fun isCartClearAction(text: String?, contentDescription: String?): Boolean =
        text == "清空购物车" || contentDescription == "清空购物车"

    fun isCartMinusControl(text: String?, contentDescription: String?, resourceId: String?): Boolean =
        text == "减" ||
            contentDescription?.contains("减少") == true ||
            contentDescription?.contains("减商品") == true ||
            resourceId?.let {
                it.endsWith(":id/minus") ||
                    it.endsWith(":id/sub") ||
                    it.endsWith(":id/decrease") ||
                    it.endsWith(":id/quantity_minus")
            } == true

    fun isCartProductRow(
        productName: String?,
        quantityText: String?,
        hasMinusControl: Boolean,
        storeName: String?,
        expectedStoreKeyword: String? = null,
    ): Boolean {
        val normalizedStore = storeName?.trim().orEmpty()
        val expectedStore = expectedStoreKeyword?.trim().orEmpty()
        return productName?.trim()?.isNotBlank() == true &&
            parseCartQuantity(quantityText) > 0 &&
            hasMinusControl &&
            normalizedStore.isNotBlank() &&
            (expectedStore.isBlank() || normalizedStore.contains(expectedStore))
    }

    fun parseCartQuantity(text: String?): Int {
        val normalized = text?.trim()?.removePrefix("x")?.removePrefix("×") ?: return 0
        return normalized.toIntOrNull()?.takeIf { it >= 0 } ?: 0
    }

    fun isCartQuantityText(text: String?): Boolean = parseCartQuantity(text) > 0

    fun isCartProductNameCandidate(text: String?, storeName: String? = null): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isBlank() || value == storeName?.trim()) return false
        if (isCartQuantityText(value)) return false
        if (isEmptyCartMarker(value, null) || isCartClearAction(value, null)) return false
        if (value in CART_ENTRY_LABELS || value == "去结算" || value == "明细") return false
        if (value == "减" || value.contains("减少") || value.contains("删除")) return false
        return true
    }

    fun isCartStoreNameCandidate(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        return value.isNotBlank() && (value.contains("店") || value.contains("门店"))
    }

    fun isCartSummaryMarker(text: String?, contentDescription: String?): Boolean =
        text == "明细" ||
            text?.contains("起送") == true ||
            text?.startsWith("到手约") == true ||
            contentDescription?.contains("去结算") == true

    fun isDeliveryDrawerTab(text: String?, left: Int, top: Int): Boolean =
        text == "外送" && left < 540 && top in 1150..1350

    fun isPickupDrawerTab(text: String?, left: Int, top: Int): Boolean =
        text == "自取" && left >= 540 && top in 1150..1350

    fun extractMerchantDistance(texts: Iterable<String>): String? {
        val pattern = Regex("距您\\s*([0-9.]+\\s*(?:m|km|公里))", RegexOption.IGNORE_CASE)
        return texts.asSequence()
            .mapNotNull { pattern.find(it)?.groupValues?.getOrNull(1) }
            .map { "距您 ${it.replace(" ", "")}" }
            .firstOrNull()
    }

    private fun hasId(node: AccessibilityNodeInfo, suffix: String): Boolean =
        node.viewIdResourceName?.endsWith(suffix) == true
}
