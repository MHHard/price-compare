package com.mahao.teapricecompare

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Drives JD's "秒送/外卖" flow end to end: search a store, open it, add the matching drink to
 * the cart, and read the final payable price off the checkout summary page. Never taps
 * "立即支付" — the whole point is to compare prices, not to place the order.
 *
 * Selectors below were captured by hand from a real device (JD app, 2026-09) — brittle by
 * nature, expect them to need updating whenever JD changes this UI.
 */
class JdAutomator(private val context: Context) {

    suspend fun run(target: PlatformTarget): PriceResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(Platform.JD.packageName)
            ?: return PriceResult(Platform.JD, error = "未安装京东")
        context.startActivity(launchIntent)
        delay(1500)

        // JD resumes on whatever tab was last open. We only know reliable selectors for the
        // 秒送/外卖 tab's search bar — "外卖" text/desc also appears on unrelated promo tiles on
        // JD's general shopping home, so guessing which one to tap is too risky (it can land on
        // the wrong section entirely). Require the user to have that tab open already.
        val searchBar = waitForNode(5000) { it.contentDescription?.toString() == "搜索栏" }
            ?: return PriceResult(Platform.JD, error = "没有找到外卖搜索栏，请先在京东App里手动打开一次「外卖」页面再重试")
        TeaAccessibilityService.click(searchBar)
        delay(800)

        val input = waitForNode(5000) { it.viewIdResourceName?.endsWith(":id/og") == true }
            ?: return PriceResult(Platform.JD, error = "找不到搜索输入框")
        TeaAccessibilityService.setText(input, target.storeKeyword)
        delay(500)

        val searchButton = waitForNode(3000) {
            it.viewIdResourceName?.endsWith(":id/of") == true && it.text?.toString() == "搜索"
        } ?: return PriceResult(Platform.JD, error = "找不到搜索按钮")
        TeaAccessibilityService.click(searchButton)
        delay(2000)

        // Results list -> tap the first store card (search already filters by the keyword,
        // so we don't need fuzzy matching here — JD's own ranking picks the best match)
        waitForNode(6000) { it.viewIdResourceName?.endsWith(":id/ls") == true }
            ?: return PriceResult(Platform.JD, error = "没有搜索结果")

        if (!openFirstStoreCard()) {
            return PriceResult(Platform.JD, error = "点击店铺卡片后没有进入店铺页")
        }

        // Store page -> find the drink by keyword and tap it to open the spec sheet
        val product = waitForNode(6000) {
            it.contentDescription?.toString()?.contains(target.productKeyword) == true
        } ?: return PriceResult(Platform.JD, error = "店铺里没有找到「${target.productKeyword}」")
        TeaAccessibilityService.click(product)
        delay(1000)

        // Spec sheet -> go to checkout summary (does NOT pay)
        val checkoutButton = waitForNode(3000) { it.text?.toString() == "去结算" }
            ?: return PriceResult(Platform.JD, error = "没有找到去结算按钮")
        TeaAccessibilityService.click(checkoutButton)
        delay(2000)

        return readFinalPrice()
    }

    private suspend fun readFinalPrice(): PriceResult {
        val label = waitForNode(5000) { it.text?.toString() == "应付总额" }
            ?: return PriceResult(Platform.JD, error = "结算页没有找到应付总额")
        val labelBounds = Rect().also { label.getBoundsInScreen(it) }

        val priceNode = TeaAccessibilityService.findAllNodes { it.text?.toString()?.startsWith("¥") == true }
            .firstOrNull { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                bounds.top == labelBounds.top
            }

        val price = priceNode?.text?.toString()?.removePrefix("¥")?.toDoubleOrNull()
            ?: return PriceResult(Platform.JD, error = "解析价格失败")
        return PriceResult(Platform.JD, price = price)
    }

    /** The first store result is the first non-filter-bar child of the results RecyclerView. */
    private fun findFirstStoreCard(): AccessibilityNodeInfo? {
        val recycler = TeaAccessibilityService.findNode { it.viewIdResourceName?.endsWith(":id/ls") == true }
            ?: return null
        if (recycler.childCount < 2) return null
        val storeContainer = recycler.getChild(1) ?: return null
        for (i in 0 until storeContainer.childCount) {
            val child = storeContainer.getChild(i) ?: continue
            if (child.className?.toString() == "android.view.ViewGroup") return child
        }
        return storeContainer
    }

    /**
     * The store card is a custom-drawn ViewGroup with no exposed text — we can't tell which
     * pixel actually opens the store, so this tries a few plausible tap points inside its
     * bounds (logo area, name-row area, dead center) until the store page's "商家" tab shows up,
     * backing out with the system back button between attempts if a tap leads somewhere else.
     */
    private suspend fun openFirstStoreCard(): Boolean {
        val offsets = listOf(
            0.10 to 0.35, // near the store logo thumbnail, upper area
            0.30 to 0.20, // store name row, left-ish
            0.50 to 0.50, // dead center
            0.20 to 0.60, // lower-left, below the name row
        )
        for ((fx, fy) in offsets) {
            val card = findFirstStoreCard() ?: return false
            val bounds = Rect().also { card.getBoundsInScreen(it) }
            val x = bounds.left + (bounds.width() * fx).toInt()
            val y = bounds.top + (bounds.height() * fy).toInt()
            TeaAccessibilityService.tap(x, y)
            delay(2000)

            if (waitForNode(2000) { it.text?.toString() == "商家" } != null) return true

            // Didn't land on the store page — if we're not still on the results list either,
            // something unexpected opened (e.g. a coupon popup); back out before retrying.
            if (waitForNode(500) { it.viewIdResourceName?.endsWith(":id/ls") == true } == null) {
                TeaAccessibilityService.pressBack()
                delay(1000)
            }
        }
        return false
    }
}
