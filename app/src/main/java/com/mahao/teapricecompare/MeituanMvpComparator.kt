package com.mahao.teapricecompare

import android.content.Context
import kotlinx.coroutines.delay

/**
 * MVP entry point for the product idea: search Meituan, compare several real stores, and return
 * the cheapest usable purchase mode. Delivery and pickup are read in one store visit; voucher
 * uses the existing voucher flow as a separate visit because Meituan exposes a different page.
 */
class MeituanMvpComparator(private val context: Context) {

    suspend fun compare(
        target: PlatformTarget,
        apiKey: String,
        maxStores: Int = 5,
    ): MeituanComparisonResult {
        if (target.storeKeyword.isBlank() || target.productKeyword.isBlank()) {
            return MeituanComparisonResult(error = "美团比价需要填写店铺和饮品关键词")
        }
        if (apiKey.isBlank()) {
            return MeituanComparisonResult(error = "美团比价需要先填写 DeepSeek API Key")
        }

        val deliveryAutomator = MeituanAutomator(context, MeituanRoute.DELIVERY)
        val search = deliveryAutomator.runSearch(target)
        if (!search.isSuccess) return MeituanComparisonResult(error = search.error)

        val candidates = deliveryAutomator.collectStoreCandidates(target.storeKeyword, maxStores)
        if (candidates.isEmpty()) {
            return MeituanComparisonResult(error = "美团外卖结果页没有识别到候选店铺")
        }

        // Keep the list grounded in visible UI, but let Flash choose the most plausible first
        // candidate when several names contain the same brand keyword.
        val preferredIndex = runCatching {
            DeepSeekClient(apiKey).matchStore(target.storeKeyword, candidates)
        }.getOrNull()?.takeIf { it in candidates.indices }
        val orderedCandidates = preferredIndex?.let { index ->
            listOf(candidates[index]) + candidates.filterIndexed { candidateIndex, _ -> candidateIndex != index }
        } ?: candidates

        val comparisons = linkedMapOf<String, MeituanStoreComparison>()
        for ((index, storeName) in orderedCandidates.withIndex()) {
            if (index > 0) {
                val clearResult = deliveryAutomator.clearCart()
                if (!clearResult.isSuccess) {
                    return MeituanComparisonResult(
                        stores = orderedCandidates.mapNotNull(comparisons::get),
                        error = clearResult.reason ?: "美团购物车清空失败",
                    )
                }
            }
            val comparison = deliveryAutomator.compareCurrentDeliveryStore(target, storeName, apiKey)
            comparisons[storeName] = comparison
            if (index < orderedCandidates.lastIndex && !deliveryAutomator.leaveStoreToSearchResults()) {
                break
            }
        }

        // Voucher is a different Meituan search surface. It is deliberately isolated from the
        // delivery/pickup pass so the delivery result page can be reused for all candidates.
        orderedCandidates.forEach { storeName ->
            resetMeituanNavigation()
            val voucherAutomator = MeituanAutomator(context, MeituanRoute.VOUCHER)
            val voucherResult = voucherAutomator.runFullFlow(
                target.copy(storeKeyword = storeName),
                apiKey,
            )
            val voucher = priceResultToMeituanStoreComparison(storeName, voucherResult).voucher
            val previous = comparisons[storeName]
            comparisons[storeName] = if (previous != null) {
                previous.copy(voucher = voucher)
            } else {
                priceResultToMeituanStoreComparison(storeName, voucherResult)
            }
        }

        return MeituanComparisonResult(
            stores = orderedCandidates.mapNotNull(comparisons::get),
        )
    }

    private suspend fun resetMeituanNavigation() {
        repeat(6) {
            val packageName = TeaAccessibilityService.currentRoot()?.packageName?.toString()
            if (packageName != Platform.MEITUAN.packageName) return@repeat
            if (!TeaAccessibilityService.pressBack()) return@repeat
            delay(350)
        }
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(Platform.MEITUAN.packageName)
            ?: return
        context.startActivity(prepareMeituanLaunchIntent(launchIntent))
        delay(1400)
    }
}
