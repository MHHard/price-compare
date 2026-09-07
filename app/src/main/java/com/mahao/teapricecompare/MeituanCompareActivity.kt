package com.mahao.teapricecompare

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MeituanCompareActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STORE_KEYWORD = "store_keyword"
        const val EXTRA_PRODUCT_KEYWORD = "product_keyword"
        const val EXTRA_ORDER_ID = "order_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meituan_compare)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val storeKeyword = intent.getStringExtra(EXTRA_STORE_KEYWORD).orEmpty().trim()
        val productKeyword = intent.getStringExtra(EXTRA_PRODUCT_KEYWORD).orEmpty().trim()
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
        val statusText = findViewById<TextView>(R.id.compareStatusText)
        val resultText = findViewById<TextView>(R.id.compareResultText)
        val usageText = findViewById<TextView>(R.id.compareUsageText)

        if (storeKeyword.isBlank() || productKeyword.isBlank()) {
            statusText.text = "缺少查询条件"
            resultText.text = "请返回填写店铺/品牌和想吃的商品。"
            return
        }

        findViewById<TextView>(R.id.compareTargetText).text =
            "$storeKeyword · $productKeyword"
        val apiKey = SettingsStore(this).deepSeekApiKey
        if (apiKey.isNullOrBlank()) {
            statusText.text = "还没有配置 DeepSeek API Key"
            resultText.text = "请返回首页，打开设置填写 API Key 后再查。"
            return
        }

        lifecycleScope.launch {
            statusText.text = "正在打开美团，比较候选店铺…"
            resultText.text = "会读取买券、外卖和自取。不会提交订单。"
            val target = PlatformTarget(storeKeyword, productKeyword)
            val result = MeituanMvpComparator(this@MeituanCompareActivity).compare(target, apiKey)
            orderId?.let { FavoriteOrderStore(this@MeituanCompareActivity).saveComparison(it, result.toSnapshot(target)) }
            statusText.text = comparisonStatusText(result)
            resultText.text = formatResult(result)
            usageText.text = formatUsage(result)
        }

        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
        )
    }

    private fun formatResult(result: MeituanComparisonResult): String {
        if (result.stores.isEmpty()) {
            return result.error?.let { "失败：$it" } ?: "没有识别到候选店铺。"
        }

        fun format(mode: MeituanModePrice): String {
            val price = mode.price?.let { "¥${"%.2f".format(it)}" }
            val constraint = mode.orderConstraints
            val availability = when {
                price == null -> mode.error ?: "不可用"
                constraint?.isOrderable == false -> {
                    val gap = constraint.gap.takeIf { it > 0.0 }
                    if (gap != null) "$price（还差${"¥%.2f".format(gap)}起送）" else "$price（暂不可下单）"
                }
                else -> price
            }
            val candidates = mode.candidates
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、") { candidate ->
                    candidate.price?.let { "${candidate.name} ¥${"%.2f".format(it)}" } ?: candidate.name
                }
            return if (candidates != null) "$availability；凑单候选：$candidates" else availability
        }

        return buildString {
            result.cheapest?.let { (store, mode) ->
                append("最低推荐\n")
                append(store.storeName).append(" · ")
                append(mode.mode.displayName()).append(" ")
                append(format(mode))
                store.merchantDistance?.let { append(" · $it") }
                append("\n\n")
            }
            append("候选店铺\n")
            result.stores.forEachIndexed { index, store ->
                if (index > 0) append("\n\n")
                append(store.storeName)
                store.merchantDistance?.let { append("（$it）") }
                append("\n买券：").append(format(store.voucher))
                append("\n外卖：").append(format(store.delivery))
                append("\n自取：").append(format(store.pickup))
            }
            result.error?.let { append("\n\n本次未完整完成：").append(it) }
            append("\n\n仅展示价格，不会自动提交订单或支付。店内待付款购物车不会自动恢复。")
        }
    }

    private fun formatUsage(result: MeituanComparisonResult): String {
        val usage = result.usageSummary
        val budget = if (result.budgetExceeded) " · 已达到预算上限" else ""
        return "DeepSeek 用量：${usage.agentCalls}/${QueryBudget.MAX_CALLS} 次 · " +
            "${usage.totalTokens}/${QueryBudget.MAX_TOTAL_TOKENS} tokens · " +
            "${'$'}${"%.6f".format(usage.costUsd)}/${"%.2f".format(QueryBudget.MAX_COST_USD)} · " +
            "¥${"%.4f".format(usage.costCny)} · 恢复 ${usage.recoverySteps}/${QueryBudget.MAX_RECOVERY_STEPS}$budget"
    }

    private fun MeituanRoute.displayName(): String = when (this) {
        MeituanRoute.VOUCHER -> "买券"
        MeituanRoute.DELIVERY -> "外卖"
        MeituanRoute.PICKUP -> "自取"
    }
}

internal fun comparisonStatusText(result: MeituanComparisonResult): String {
    val status = ComparisonResultState.from(result.stores, budgetExceeded = result.budgetExceeded).status
    return when {
        status == ComparisonStatus.BUDGET_EXCEEDED -> "已达到本次预算"
        result.error != null && status == ComparisonStatus.NO_VERIFIED_PRICE -> "比价没有完成"
        status == ComparisonStatus.PARTIAL -> "部分完成"
        status == ComparisonStatus.NO_VERIFIED_PRICE -> "未找到可验证价格"
        result.error != null && status == ComparisonStatus.SUCCESS -> "部分完成"
        status == ComparisonStatus.SUCCESS -> "比价完成"
        else -> "比价没有完成"
    }
}
