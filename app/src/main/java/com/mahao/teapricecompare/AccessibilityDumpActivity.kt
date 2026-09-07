package com.mahao.teapricecompare

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AccessibilityDumpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_dump)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val resultText = findViewById<TextView>(R.id.dumpResultText)
        val statusText = findViewById<TextView>(R.id.serviceStatusText)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.openAccessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<View>(R.id.testJdSearchButton).setOnClickListener {
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = JdAutomator(this@AccessibilityDumpActivity)
                    .run(PlatformTarget(storeKeyword = sk, productKeyword = pk))
                resultText.text = if (r.isSuccess) "京东最终价格：¥${r.price}" else "失败：${r.error}"
            }
        }
        findViewById<View>(R.id.testMeituanSearchButton).setOnClickListener {
            if (!requireMeituanConsent()) return@setOnClickListener
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = MeituanAutomator(this@AccessibilityDumpActivity)
                    .runSearch(PlatformTarget(storeKeyword = sk, productKeyword = pk))
                resultText.text = if (r.isSuccess) "美团已进入搜索结果页" else "失败：${r.error}"
            }
        }
        findViewById<View>(R.id.testMeituanStoreButton).setOnClickListener {
            if (!requireMeituanConsent()) return@setOnClickListener
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = MeituanAutomator(this@AccessibilityDumpActivity)
                    .runSearchAndOpenStore(PlatformTarget(storeKeyword = sk, productKeyword = pk))
                resultText.text = if (r.isSuccess) "美团已进入店铺页" else "失败：${r.error}"
            }
        }
        findViewById<View>(R.id.testMeituanFullFlowButton).setOnClickListener {
            if (!requireMeituanConsent()) return@setOnClickListener
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val apiKey = SettingsStore(this).deepSeekApiKey
            if (apiKey.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.toast_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = MeituanAutomator(this@AccessibilityDumpActivity)
                    .runFullFlow(PlatformTarget(storeKeyword = sk, productKeyword = pk), apiKey)
                resultText.text = if (r.isSuccess) "美团最终应付：¥${r.price}" else "失败：${r.error}"
            }
        }
        findViewById<View>(R.id.testMeituanDeliveryButton).setOnClickListener {
            if (!requireMeituanConsent()) return@setOnClickListener
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val apiKey = SettingsStore(this).deepSeekApiKey
            if (apiKey.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.toast_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = MeituanAutomator(this@AccessibilityDumpActivity, MeituanRoute.DELIVERY)
                    .runFullFlow(PlatformTarget(storeKeyword = sk, productKeyword = pk), apiKey)
                resultText.text = if (r.isSuccess) {
                    "美团外卖${r.merchantDistance?.let { "，$it" }.orEmpty()}，最终应付：¥${r.price}${formatMeituanModePrices(r)}"
                } else "失败：${r.error}${formatMeituanModePrices(r)}"
            }
        }
        findViewById<View>(R.id.testMeituanPickupButton).setOnClickListener {
            if (!requireMeituanConsent()) return@setOnClickListener
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val apiKey = SettingsStore(this).deepSeekApiKey
            if (apiKey.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.toast_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (sk, pk) = readKeywords() ?: return@setOnClickListener
            lifecycleScope.launch {
                val r = MeituanAutomator(this@AccessibilityDumpActivity, MeituanRoute.PICKUP)
                    .runFullFlow(PlatformTarget(storeKeyword = sk, productKeyword = pk), apiKey)
                resultText.text = if (r.isSuccess) {
                    "美团自取${r.merchantDistance?.let { "，$it" }.orEmpty()}，最终应付：¥${r.price}${formatMeituanModePrices(r)}"
                } else "失败：${r.error}${formatMeituanModePrices(r)}"
            }
        }
        findViewById<View>(R.id.dumpButton).setOnClickListener {
            if (!TeaAccessibilityService.isEnabled(this)) {
                toastServiceRequired(); return@setOnClickListener
            }
            val dump = TeaAccessibilityService.lastCapture
            resultText.text = dump
                ?: "还没有捕捉到内容：请先打开京东/美团任意一个页面，停留几秒后再切回本App查看"
            dump?.let {
                getExternalFilesDir(null)?.resolve("last_dump.txt")?.writeText(it)
            }
        }

        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
            bottom = findViewById(R.id.bottomBar),
        )
    }

    override fun onResume() {
        super.onResume()
        val enabled = TeaAccessibilityService.isEnabled(this)
        findViewById<TextView>(R.id.serviceStatusText).text =
            if (enabled) "无障碍服务已开启" else "无障碍服务未开启"
    }

    private fun toastServiceRequired() {
        Toast.makeText(this, getString(R.string.toast_service_required), Toast.LENGTH_SHORT).show()
    }

    private fun requireMeituanConsent(): Boolean {
        if (MeituanCartController().canCompare) return true
        Toast.makeText(
            this,
            getString(R.string.meituan_cart_consent_required),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }

    private fun readKeywords(): Pair<String, String>? {
        val sk = findViewById<TextInputEditText>(R.id.testKeywordEditText).text.toString().trim()
        val pk = findViewById<TextInputEditText>(R.id.testProductKeywordEditText).text.toString().trim()
        if (sk.isEmpty()) return null
        return sk to pk
    }

    private fun formatMeituanModePrices(result: PriceResult): String {
        val prices = result.meituanPrices ?: return ""
        fun format(modePrice: MeituanModePrice): String =
            modePrice.price?.let { "¥$it" } ?: (modePrice.error ?: "不可用")
        return "\n同时抓取：外送 ${format(prices.delivery)}；自取 ${format(prices.pickup)}"
    }
}
