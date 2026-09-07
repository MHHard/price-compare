package com.mahao.teapricecompare

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class LocalBundleScan(
    val candidates: List<ProductCandidate> = emptyList(),
    val error: String? = null,
)

/**
 * Reads only the currently visible product cards. A candidate is usable for local bundling only
 * when one card exposes one price and an explicit add/spec action. Anything ambiguous is left for
 * the later Agent recovery path instead of being guessed here.
 */
object MeituanProductScanner {

    fun scan(targetKeyword: String): LocalBundleScan {
        val root = TeaAccessibilityService.currentRoot()
            ?: return LocalBundleScan(error = "无法读取当前店铺商品列表")
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root) { node ->
            if (node.className?.toString() == "android.widget.TextView" && isVisible(node)) {
                textNodes += node
            }
        }

        val candidates = textNodes.asSequence()
            .filter { node -> MeituanSelectors.isProductNameCandidate(node.text?.toString()) }
            .mapNotNull { node -> readCandidate(node, targetKeyword) }
            .filter { !it.isTarget }
            .distinctBy { it.name }
            .take(MAX_CANDIDATES)
            .toList()

        return if (candidates.isNotEmpty()) {
            LocalBundleScan(candidates = candidates)
        } else {
            LocalBundleScan(error = "当前页面没有识别到带价格和可加购入口的凑单商品")
        }
    }

    private fun readCandidate(
        productNode: AccessibilityNodeInfo,
        targetKeyword: String,
    ): ProductCandidate? {
        var ancestor = productNode.parent
        repeat(MAX_ANCESTOR_DEPTH) {
            val card = ancestor ?: return null
            val cardNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(card, cardNodes)
            val actions = cardNodes.filter { node ->
                MeituanSelectors.isProductAddAction(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                ) && isVisible(node)
            }.distinctBy { nodeKey(it) }
            val prices = cardNodes.mapNotNull { node ->
                if (!isVisible(node)) return@mapNotNull null
                MeituanSelectors.parseProductPrice(node.text?.toString())
            }.distinct()
            val unavailable = cardNodes.any { node ->
                val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .joinToString(" ")
                text.contains("售罄") || text.contains("已售完") ||
                    text.contains("暂不可售") || text.contains("下架")
            }
            if (actions.size == 1 && prices.size == 1 && !unavailable) {
                val name = productNode.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return null
                return ProductCandidate(
                    name = name,
                    price = prices.single(),
                    specSummary = cardNodes.asSequence()
                        .mapNotNull { it.text?.toString()?.trim() }
                        .firstOrNull { it.startsWith("已选规格") || it.startsWith("规格") },
                    isTarget = targetKeyword.isNotBlank() && name.contains(targetKeyword),
                    isAddable = true,
                )
            }
            ancestor = card.parent
        }
        return null
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
        ).joinToString("|")
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return bounds.left >= 264 && bounds.top < 2376 && bounds.bottom > 1450 &&
            bounds.width() > 0 && bounds.height() > 0
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        out += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectNodes(it, out) }
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        visitor: (AccessibilityNodeInfo) -> Unit,
    ) {
        visitor(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectNodes(it, visitor) }
        }
    }

    private const val MAX_ANCESTOR_DEPTH = 6
    private const val MAX_CANDIDATES = 20
}
