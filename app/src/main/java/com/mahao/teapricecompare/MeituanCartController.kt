package com.mahao.teapricecompare

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

enum class CartConsentState {
    NOT_ASKED,
    ACCEPTED,
    DECLINED,
}

enum class CartClearStatus {
    CLEARED,
    NOT_AUTHORIZED,
    CLEAR_FAILED,
}

data class CartClearResult(
    val status: CartClearStatus,
    val reason: String? = null,
) {
    val isSuccess: Boolean get() = status == CartClearStatus.CLEARED
}

data class CartItemRow(
    val storeName: String,
    val productName: String,
    val quantity: Int,
    val rowKey: String,
)

data class CartObservation(
    val hasEmptyMarker: Boolean,
    val rows: List<CartItemRow>,
    val unknownRowCount: Int = 0,
)

/** The small accessibility boundary needed by the cart controller and its pure state tests. */
interface MeituanCartAccessibility {
    fun openCart(): Boolean
    fun readCart(): CartObservation?
    fun clickMinus(row: CartItemRow): Boolean
}

private data class NodeCartRow(
    val storeName: String,
    val productName: String,
    val quantity: Int,
    val minusNode: AccessibilityNodeInfo,
)

/**
 * Clears only rows that are structurally identifiable in the current store's pending-payment
 * cart. It never uses coordinates and never persists consent.
 */
class MeituanCartController(
    private val accessibility: MeituanCartAccessibility = TeaAccessibilityAdapter,
) {

    val consentState: CartConsentState
        get() = sessionConsent

    val canCompare: Boolean
        get() = consentState == CartConsentState.ACCEPTED

    fun acceptConsent() {
        sessionConsent = CartConsentState.ACCEPTED
    }

    fun rejectConsent() {
        sessionConsent = CartConsentState.DECLINED
    }

    suspend fun clearCart(): CartClearResult {
        if (!canCompare) {
            return CartClearResult(
                status = CartClearStatus.NOT_AUTHORIZED,
                reason = "需要先同意清空店内待付款购物车后才能自动比价",
            )
        }
        if (!accessibility.openCart()) {
            return failed("没有找到当前店内待付款购物车")
        }
        delay(500)

        repeat(MAX_CLEAR_ACTIONS) {
            val observation = accessibility.readCart() ?: return failed("无法读取店内待付款购物车节点")
            val hasInvalidRows = observation.rows.any { row ->
                !MeituanSelectors.isCartProductRow(
                    productName = row.productName,
                    quantityText = row.quantity.toString(),
                    hasMinusControl = true,
                    storeName = row.storeName,
                )
            }
            if (observation.unknownRowCount > 0 || hasInvalidRows) {
                return failed("店内待付款购物车存在未识别商品行，未执行删除")
            }
            if (observation.hasEmptyMarker && observation.rows.isEmpty()) {
                return CartClearResult(CartClearStatus.CLEARED)
            }

            val row = observation.rows.firstOrNull()
                ?: return failed("店内待付款购物车存在未识别商品行，未执行删除")
            if (row.quantity <= 0 || !accessibility.clickMinus(row)) {
                return failed("无法通过无障碍节点减少「${row.productName}」")
            }
            // The next loop reads a new root and new row nodes after this action.
            delay(250)
        }
        return failed("店内待付款购物车清空动作超过安全次数，已停止")
    }

    private fun failed(reason: String) = CartClearResult(CartClearStatus.CLEAR_FAILED, reason)

    companion object {
        private const val MAX_CLEAR_ACTIONS = 100
        @Volatile
        private var sessionConsent = CartConsentState.NOT_ASKED

        /** Test-only reset; production has no persistence or reset UI. */
        fun resetSessionForTests() {
            sessionConsent = CartConsentState.NOT_ASKED
        }
    }
}

private object TeaAccessibilityAdapter : MeituanCartAccessibility {
    override fun openCart(): Boolean {
        val root = TeaAccessibilityService.currentRoot() ?: return false
        val currentObservation = readCart(root)
        val hasDrawerHeader = findNode(root) { node ->
            MeituanSelectors.isCartDrawerMarker(node.text?.toString(), node.contentDescription?.toString())
        } != null
        val hasClearAction = findNode(root) { node ->
            MeituanSelectors.isCartClearAction(node.text?.toString(), node.contentDescription?.toString())
        } != null
        val hasCartTitle = findNode(root) { node ->
            node.text?.toString()?.contains("购物车") == true
        } != null
        if ((hasDrawerHeader && (hasClearAction || currentObservation.rows.isNotEmpty())) ||
            (currentObservation.hasEmptyMarker && hasCartTitle)
        ) {
            return true
        }
        val marker = TeaAccessibilityService.findNode { node ->
            MeituanSelectors.isCartEntry(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName,
            )
        }
        if (marker != null && TeaAccessibilityService.click(marker)) return true

        // After the first product is selected, some Meituan builds expose only the collapsed
        // bottom summary and omit a semantic cart node. This is the store-page cart entry, not
        // the Meituan home/global cart; clearCart() is called only after a product was selected.
        return TeaAccessibilityService.tap(CART_ENTRY_X, CART_ENTRY_Y)
    }

    override fun readCart(): CartObservation? =
        TeaAccessibilityService.currentRoot()?.let(::readCart)

    override fun clickMinus(row: CartItemRow): Boolean {
        val root = TeaAccessibilityService.currentRoot() ?: return false
        val matchingRows = findNodeRows(root, findCurrentStoreName(root)).filter {
            it.storeName == row.storeName && it.productName == row.productName
        }
        if (matchingRows.size != 1) return false
        return clickNodeAction(matchingRows.single().minusNode)
    }

    private fun readCart(root: AccessibilityNodeInfo): CartObservation {
        val nodeRows = findNodeRows(root, findCurrentStoreName(root))
        val minusNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root) { node ->
            if (isMinus(node)) minusNodes += node
        }
        val unknownRowCount = minusNodes.count { minusNode ->
            nodeRows.none { it.minusNode === minusNode }
        }
        val rows = nodeRows.distinctBy { "${it.storeName}\u0000${it.productName}" }
            .map { nodeRow ->
                CartItemRow(
                    storeName = nodeRow.storeName,
                    productName = nodeRow.productName,
                    quantity = nodeRow.quantity,
                    rowKey = "${nodeRow.storeName}\u0000${nodeRow.productName}",
                )
            }
        val hasEmptyMarker = findNode(root) { node ->
            MeituanSelectors.isEmptyCartMarker(
                node.text?.toString(),
                node.contentDescription?.toString(),
            )
        } != null
        return CartObservation(hasEmptyMarker, rows, unknownRowCount)
    }

    private fun findNodeRows(root: AccessibilityNodeInfo, fallbackStoreName: String? = null): List<NodeCartRow> {
        val rows = mutableListOf<NodeCartRow>()
        collectNodes(root) { node ->
            if (isMinus(node)) findCartRow(node, fallbackStoreName)?.let(rows::add)
        }
        return rows
    }

    private fun findCartRow(minusNode: AccessibilityNodeInfo, fallbackStoreName: String?): NodeCartRow? {
        var container = minusNode.parent
        var depth = 0
        while (container != null && depth++ < MAX_ANCESTOR_DEPTH) {
            val texts = collectText(container)
            val stores = texts.filter(MeituanSelectors::isCartStoreNameCandidate).distinct()
            val quantities = texts.filter(MeituanSelectors::isCartQuantityText).distinct()
            val storeName = stores.singleOrNull() ?: fallbackStoreName
            val productNames = texts.filter { text ->
                MeituanSelectors.isCartProductNameCandidate(text, storeName)
            }.distinct()
            val minusCount = countNodes(container, ::isMinus)
            val productName = productNames.singleOrNull()
            val quantity = findCartQuantity(container, minusNode, quantities)
            if (minusCount == 1 && MeituanSelectors.isCartProductRow(
                    productName,
                    quantity.toString(),
                    hasMinusControl = true,
                    storeName = storeName,
                )
            ) {
                return NodeCartRow(
                    storeName = storeName!!,
                    productName = productName!!,
                    quantity = quantity,
                    minusNode = minusNode,
                )
            }
            container = container.parent
        }
        return null
    }

    private fun findCartQuantity(
        container: AccessibilityNodeInfo,
        minusNode: AccessibilityNodeInfo,
        textQuantities: List<String>,
    ): Int {
        var describedQuantity = 0
        collectNodes(container) { node ->
            describedQuantity = maxOf(
                describedQuantity,
                MeituanSelectors.parseCartQuantity(node.contentDescription?.toString()),
            )
        }
        if (describedQuantity > 0) return describedQuantity

        val minusBounds = Rect().also { minusNode.getBoundsInScreen(it) }
        return collectNodesWithBounds(container)
            .mapNotNull { (node, bounds) ->
                val quantity = MeituanSelectors.parseCartQuantity(node.text?.toString())
                if (quantity <= 0 || bounds.centerX() <= minusBounds.centerX() ||
                    kotlin.math.abs(bounds.centerY() - minusBounds.centerY()) > 80
                ) {
                    null
                } else {
                    quantity to kotlin.math.abs(bounds.centerX() - minusBounds.centerX())
                }
            }
            .minByOrNull { it.second }
            ?.first
            ?: textQuantities.firstOrNull()?.let(MeituanSelectors::parseCartQuantity)
            ?: 0
    }

    private fun findCurrentStoreName(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        collectNodes(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (text.length >= 5 &&
                bounds.top in 600..1100 &&
                bounds.width() >= 250 &&
                MeituanSelectors.isCartStoreNameCandidate(text)
            ) {
                candidates += text
            }
        }
        return candidates.distinct().maxByOrNull { text ->
            val titleShape = if (text.contains('(') || text.contains('（')) 10_000 else 0
            titleShape + text.length
        }
    }

    private fun collectNodesWithBounds(
        node: AccessibilityNodeInfo,
    ): List<Pair<AccessibilityNodeInfo, Rect>> {
        val nodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectNodes(node) { current ->
            nodes += current to Rect().also { current.getBoundsInScreen(it) }
        }
        return nodes
    }

    private fun isMinus(node: AccessibilityNodeInfo): Boolean =
        MeituanSelectors.isCartMinusControl(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName,
        )

    private fun collectText(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        collectNodes(node) { current ->
            current.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
        }
        return texts.distinct()
    }

    private fun countNodes(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Int {
        var count = 0
        collectNodes(node) { if (predicate(it)) count += 1 }
        return count
    }

    private fun collectNodes(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        visitor(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectNodes(it, visitor) }
        }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findNode(it, predicate) }?.let { return it }
        }
        return null
    }

    private const val MAX_ANCESTOR_DEPTH = 3
    private const val CART_ENTRY_X = 100
    private const val CART_ENTRY_Y = 2260

    private fun clickNodeAction(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
