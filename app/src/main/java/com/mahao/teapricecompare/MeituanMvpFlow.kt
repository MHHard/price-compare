package com.mahao.teapricecompare

import android.content.Intent

data class MeituanMvpRun(
    val storeName: String,
    val route: MeituanRoute,
)

fun buildMeituanMvpRunPlan(storeNames: List<String>): List<MeituanMvpRun> =
    storeNames.flatMap { storeName ->
        listOf(
            MeituanMvpRun(storeName, MeituanRoute.DELIVERY),
            MeituanMvpRun(storeName, MeituanRoute.VOUCHER),
        )
    }

fun meituanLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP

fun prepareMeituanLaunchIntent(intent: Intent): Intent = intent.apply {
    addFlags(meituanLaunchFlags())
}

fun priceResultToMeituanStoreComparison(
    storeName: String,
    result: PriceResult,
): MeituanStoreComparison {
    val modes = result.meituanPrices
    fun MeituanModePrice.withResultContext(): MeituanModePrice = copy(
        candidates = result.candidates.ifEmpty { candidates },
        orderConstraints = result.orderConstraints ?: orderConstraints,
    )

    val delivery = modes?.delivery?.withResultContext() ?: MeituanModePrice(
        MeituanRoute.DELIVERY,
        price = if (result.platform == Platform.MEITUAN_DELIVERY) result.price else null,
        error = result.error,
        candidates = result.candidates,
        orderConstraints = result.orderConstraints,
    )
    val pickup = modes?.pickup?.withResultContext() ?: MeituanModePrice(
        MeituanRoute.PICKUP,
        error = result.error ?: "自取价格未读取",
        candidates = result.candidates,
        orderConstraints = result.orderConstraints,
    )
    val voucher = if (result.platform == Platform.MEITUAN) {
        MeituanModePrice(
            MeituanRoute.VOUCHER,
            price = result.price,
            error = result.error,
            candidates = result.candidates,
            orderConstraints = result.orderConstraints,
        )
    } else {
        MeituanModePrice(
            MeituanRoute.VOUCHER,
            error = "本店买券暂未读取",
            candidates = result.candidates,
            orderConstraints = result.orderConstraints,
        )
    }
    return MeituanStoreComparison(
        storeName = storeName,
        merchantDistance = result.merchantDistance ?: modes?.merchantDistance,
        voucher = voucher,
        delivery = delivery,
        pickup = pickup,
    )
}

fun MeituanComparisonResult.toSnapshot(target: PlatformTarget): ComparisonSnapshot {
    val derived = ComparisonResultState.from(stores, budgetExceeded)
    val status = when {
        budgetExceeded -> ComparisonStatus.BUDGET_EXCEEDED
        error != null && cheapest == null -> ComparisonStatus.FAILED
        error != null -> ComparisonStatus.PARTIAL
        else -> derived.status
    }
    return ComparisonSnapshot(
        queryId = queryId ?: "unknown",
        target = target,
        stores = stores,
        status = status,
        usageSummary = usageSummary,
        cartNotice = "查价结束后店内待付款购物车保持本次查询内容，不会自动恢复。",
        failureReason = error,
    )
}
