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
        val statusText = findViewById<TextView>(R.id.compareStatusText)
        val resultText = findViewById<TextView>(R.id.compareResultText)

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
            val result = MeituanMvpComparator(this@MeituanCompareActivity).compare(
                PlatformTarget(storeKeyword, productKeyword),
                apiKey,
            )
            statusText.text = if (result.error == null) "比价完成" else "比价没有完成"
            resultText.text = formatResult(result)
        }

        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
        )
    }

    private fun formatResult(result: MeituanComparisonResult): String {
        result.error?.let { return "失败：$it" }
        if (result.stores.isEmpty()) return "没有识别到候选店铺。"

        fun format(mode: MeituanModePrice): String =
            mode.price?.let { "¥${"%.2f".format(it)}" } ?: (mode.error ?: "不可用")

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
            append("\n\n仅展示价格，不会自动提交订单或支付。")
        }
    }

    private fun MeituanRoute.displayName(): String = when (this) {
        MeituanRoute.VOUCHER -> "买券"
        MeituanRoute.DELIVERY -> "外卖"
        MeituanRoute.PICKUP -> "自取"
    }
}
