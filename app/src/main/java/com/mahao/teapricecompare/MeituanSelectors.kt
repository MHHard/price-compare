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

    fun isProductAddAction(text: String?, contentDescription: String?): Boolean =
        text == "选规格" ||
            text == "抢购" ||
            text == "加入购物车" ||
            contentDescription?.contains("选规格") == true ||
            contentDescription?.contains("加入购物车") == true

    /** Parses a price exposed by a product card, not an order summary or discount label. */
    fun parseProductPrice(text: String?): Double? {
        val value = text?.trim().orEmpty()
        if (value.isBlank() || isMinimumOrderText(value) ||
            value.contains("配送费") || value.contains("打包费") ||
            value.contains("优惠") || (value.contains("满") && value.contains("减"))
        ) return null

        val withCurrency = Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
            .find(value)?.groupValues?.getOrNull(1)
        val plain = Regex("^\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元)?(?:起)?\\s*$")
            .matchEntire(value)?.groupValues?.getOrNull(1)
        return (withCurrency ?: plain)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun isProductNameCandidate(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isBlank() || value.length > 60 || parseProductPrice(value) != null) return false
        if (isProductAddAction(value, null) || isMinimumOrderText(value)) return false
        if (value.matches(Regex("[0-9.￥¥元]+"))) return false
        val blocked = listOf(
            "月售", "已售", "好评", "折", "优惠", "配送", "打包费", "商品小计",
            "商品金额", "去结算", "提交订单", "明细", "规格", "甜度", "冰量",
        )
        return blocked.none(value::contains)
    }

    /** Reads only amounts that are supported by the current cart text. */
    fun parseOrderConstraints(texts: Iterable<String>): OrderConstraints? {
        val values = texts.map(String::trim).filter(String::isNotBlank).toList()
        if (values.isEmpty()) return null
        val gap = values.asSequence()
            .mapNotNull { GAP_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
            .firstOrNull { it.isFinite() && it >= 0.0 }
        val minimumOrder = values.asSequence()
            .mapNotNull(::parseMinimumOrder)
            .firstOrNull { it.isFinite() && it >= 0.0 }
            ?: 0.0
        val subtotal = parseLabeledAmount(values, listOf("商品小计", "小计", "商品金额", "商品合计"))
        val deliveryFee = parseLabeledAmount(values, listOf("配送费")) ?: 0.0
        val packingFee = parseLabeledAmount(values, listOf("打包费", "包装费")) ?: 0.0
        val checkoutMarker = values.any { value ->
            value == "去结算" || value.contains("去结算") ||
                value == "提交订单" || value.contains("提交订单")
        }

        if (gap != null) {
            return OrderConstraints(
                subtotal = subtotal ?: 0.0,
                minimumOrder = minimumOrder,
                gap = gap,
                deliveryFee = deliveryFee,
                packingFee = packingFee,
                isOrderable = gap <= ORDERABLE_EPSILON,
            )
        }
        if (minimumOrder > 0.0 && subtotal != null) {
            val computedGap = (minimumOrder - subtotal).coerceAtLeast(0.0)
            return OrderConstraints(
                subtotal = subtotal,
                minimumOrder = minimumOrder,
                gap = computedGap,
                deliveryFee = deliveryFee,
                packingFee = packingFee,
                isOrderable = computedGap <= ORDERABLE_EPSILON && checkoutMarker,
            )
        }
        if (checkoutMarker) {
            return OrderConstraints(
                subtotal = subtotal ?: 0.0,
                minimumOrder = minimumOrder,
                deliveryFee = deliveryFee,
                packingFee = packingFee,
                isOrderable = true,
            )
        }
        if (values.any { isMinimumOrderText(it) }) {
            return OrderConstraints(
                subtotal = subtotal ?: 0.0,
                minimumOrder = minimumOrder,
                deliveryFee = deliveryFee,
                packingFee = packingFee,
                isOrderable = false,
            )
        }
        return null
    }

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
        text == "清空" ||
            contentDescription == "清空" ||
            text == "清空购物车" ||
            contentDescription == "清空购物车"

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
        val value = text?.trim() ?: return 0
        val addedCount = Regex("已添加\\s*(\\d+)\\s*份").find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (addedCount != null) return addedCount.coerceAtLeast(0)

        val normalized = value.removePrefix("x").removePrefix("×")
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
        if (value.matches(Regex("[0-9.￥¥元]+"))) return false
        if (value in CART_SPEC_LABELS ||
            value.startsWith("装入口袋") ||
            value.startsWith("选用后") ||
            value.contains("打包费") ||
            value == "优惠后"
        ) return false
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

    private val CART_SPEC_LABELS = setOf(
        "标准",
        "少冰",
        "正常冰",
        "常温",
        "七分糖(推荐)",
        "正常糖",
        "五分糖",
        "三分糖",
        "不额外加糖",
    )

    private fun parseMinimumOrder(text: String): Double? {
        val afterLabel = Regex("(?:起送价|起送)\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
            .find(text)?.groupValues?.getOrNull(1)
        val beforeLabel = Regex("[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*起送")
            .find(text)?.groupValues?.getOrNull(1)
        return (afterLabel ?: beforeLabel)?.toDoubleOrNull()
    }

    private fun parseLabeledAmount(values: List<String>, labels: List<String>): Double? {
        values.forEachIndexed { index, value ->
            val label = labels.firstOrNull { value.contains(it) } ?: return@forEachIndexed
            val suffix = value.substringAfter(label)
            parseAmount(suffix)?.let { return it }
            values.drop(index + 1).take(2).forEach { next ->
                parseAmount(next)?.let { return it }
            }
        }
        return null
    }

    private fun parseAmount(text: String): Double? {
        val value = text.trim()
        val amount = Regex("[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元)?")
            .matchEntire(value)?.groupValues?.getOrNull(1)
            ?: return null
        return amount.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private val GAP_PATTERN = Regex(
        "(?:差|还差|再买)\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元)?\\s*(?:可达)?\\s*起送",
    )

    private const val ORDERABLE_EPSILON = 0.009
}
