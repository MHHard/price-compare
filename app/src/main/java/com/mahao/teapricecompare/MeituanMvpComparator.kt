package com.mahao.teapricecompare

import android.content.Context
import kotlinx.coroutines.delay
import java.util.UUID

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

        val queryId = UUID.randomUUID().toString()
        val queryBudget = QueryBudget()
        val usageLedger = UsageLedgerStore(context)
        val usdToCnyRate = SettingsStore(context).usdToCnyRate
        val deepSeek = DeepSeekClient(
            apiKey = apiKey,
            queryBudget = queryBudget,
            usageLedgerStore = usageLedger,
            queryId = queryId,
            usdToCnyRate = usdToCnyRate,
        )
        val recoveryPlanner = AgentRecoveryPlanner(deepSeek, queryBudget)
        fun result(
            stores: List<MeituanStoreComparison> = emptyList(),
            error: String? = null,
        ): MeituanComparisonResult = MeituanComparisonResult(
            stores = stores,
            error = error,
            queryId = queryId,
            usageSummary = queryUsageSummary(queryBudget, usageLedger, queryId),
            budgetExceeded = queryBudget.isExhausted(),
        )

        val deliveryAutomator = MeituanAutomator(
            context = context,
            route = MeituanRoute.DELIVERY,
            queryBudget = queryBudget,
            usageLedgerStore = usageLedger,
            queryId = queryId,
            recoveryPlanner = recoveryPlanner,
            usdToCnyRate = usdToCnyRate,
        )
        val search = deliveryAutomator.runSearch(target)
        if (!search.isSuccess) return result(error = search.error)

        val candidates = deliveryAutomator.collectStoreCandidates(target.storeKeyword, maxStores)
        if (candidates.isEmpty()) {
            return result(error = "美团外卖结果页没有识别到候选店铺")
        }

        // Keep the list grounded in visible UI, but let Flash choose the most plausible first
        // candidate when several names contain the same brand keyword.
        val preferredIndex = if (candidates.size > 1) {
            runCatching {
                deepSeek.matchStore(target.storeKeyword, candidates)
            }.getOrNull()?.takeIf { it in candidates.indices }
        } else {
            null
        }
        val orderedCandidates = preferredIndex?.let { index ->
            listOf(candidates[index]) + candidates.filterIndexed { candidateIndex, _ -> candidateIndex != index }
        } ?: candidates

        val comparisons = linkedMapOf<String, MeituanStoreComparison>()
        for ((index, storeName) in orderedCandidates.withIndex()) {
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
            val voucherAutomator = MeituanAutomator(
                context = context,
                route = MeituanRoute.VOUCHER,
                queryBudget = queryBudget,
                usageLedgerStore = usageLedger,
                queryId = queryId,
                recoveryPlanner = recoveryPlanner,
                usdToCnyRate = usdToCnyRate,
            )
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

        return result(
            stores = orderedCandidates.mapNotNull(comparisons::get),
        )
    }

    private fun queryUsageSummary(
        budget: QueryBudget,
        ledger: UsageLedgerStore,
        queryId: String,
    ): UsageSummary {
        val cny = ledger.readAll()
            .asSequence()
            .filter { it.queryId == queryId }
            .sumOf { it.costCny }
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        return budget.usageSummary().copy(costCny = cny)
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
