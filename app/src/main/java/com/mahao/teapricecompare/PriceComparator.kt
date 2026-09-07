package com.mahao.teapricecompare

import android.content.Context

data class CompareOutcome(
    val results: List<PriceResult>,
) {
    /** Lowest-priced platform among the ones that succeeded, or null if all failed. */
    val cheapest: PriceResult? get() = results.filter { it.isSuccess }.minByOrNull { it.price!! }
}

/**
 * Runs every platform target on a [FavoriteOrder] and reports prices for the user to compare.
 * Stops at each platform's checkout summary — never submits or pays. The user still opens the
 * winning platform themselves and taps "提交订单"/"去支付" on their own.
 */
class PriceComparator(private val context: Context, private val deepSeekApiKey: String?) {

    suspend fun compare(order: FavoriteOrder): CompareOutcome {
        val results = order.targets.map { (platform, target) ->
            when (platform) {
                Platform.JD -> JdAutomator(context).run(target)
                Platform.MEITUAN, Platform.MEITUAN_DELIVERY, Platform.MEITUAN_PICKUP -> {
                    val apiKey = deepSeekApiKey
                    if (apiKey.isNullOrBlank()) {
                        PriceResult(platform, error = "美团比价需要先在设置里填写 DeepSeek API Key")
                    } else {
                        val route = when (platform) {
                            Platform.MEITUAN -> MeituanRoute.VOUCHER
                            Platform.MEITUAN_DELIVERY -> MeituanRoute.DELIVERY
                            Platform.MEITUAN_PICKUP -> MeituanRoute.PICKUP
                            else -> error("不是美团平台")
                        }
                        MeituanAutomator(context, route).runFullFlow(target, apiKey)
                    }
                }
            }
        }
        return CompareOutcome(results)
    }
}
