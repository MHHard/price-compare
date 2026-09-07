package com.mahao.teapricecompare

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var store: FavoriteOrderStore
    private lateinit var adapter: FavoriteOrderAdapter
    private val cartController = MeituanCartController()
    private var lastAccessibilityEnabled: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 让系统栏图标（状态栏文字、导航栏按钮）在深色主题下保持亮色
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        store = FavoriteOrderStore(this)
        adapter = FavoriteOrderAdapter(
            onEdit = { order ->
                startActivity(
                    Intent(this, FavoriteOrderEditActivity::class.java)
                        .putExtra(FavoriteOrderEditActivity.EXTRA_ORDER_ID, order.id),
                )
            },
            onDelete = { order ->
                store.delete(order.id)
                refreshList()
                Snackbar.make(window.decorView, "已删除「${order.name}」", Snackbar.LENGTH_SHORT).show()
            },
            onCompare = { order ->
                order.targets.meituanTarget()?.let { target ->
                    openMeituanCompare(target, order.id)
                }
                    ?: showMissingMeituanTarget(order)
            },
        )

        findViewById<RecyclerView>(R.id.favoriteOrderRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(false)
        }

        findViewById<View>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.openDebugDumpButton).setOnClickListener {
            startActivity(Intent(this, AccessibilityDumpActivity::class.java))
        }
        findViewById<View>(R.id.addFavoriteOrderButton).setOnClickListener {
            startActivity(Intent(this, FavoriteOrderEditActivity::class.java))
        }

        findViewById<Button>(R.id.quickCompareButton).setOnClickListener {
            val storeKeyword = findViewById<EditText>(R.id.quickStoreInput).text.toString().trim()
            val productKeyword = findViewById<EditText>(R.id.quickProductInput).text.toString().trim()
            if (storeKeyword.isBlank() || productKeyword.isBlank()) {
                Snackbar.make(window.decorView, "请填写店铺/品牌和想吃的商品", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openMeituanCompare(PlatformTarget(storeKeyword, productKeyword))
        }
        findViewById<TextView>(R.id.quickServiceStatus).setOnClickListener {
            if (!TeaAccessibilityService.isEnabled(this)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // Insets：顶部 topBar 避开状态栏，列表避开 bottomBar
        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.favoriteOrderRecyclerView),
            bottom = findViewById(R.id.bottomBar),
            extraBottomDp = 8,
        )

        showMeituanCartConsentIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        updateAccessibilityStatus()
    }

    private fun refreshList() {
        val orders = store.list()
        adapter.submitList(orders)
        findViewById<TextView>(R.id.emptyStateText).visibility =
            if (orders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateAccessibilityStatus() {
        val enabled = TeaAccessibilityService.isEnabled(this)
        findViewById<TextView>(R.id.quickServiceStatus).apply {
            text = if (enabled) {
                "● 无障碍服务已开启，可以自动进入美团并读取价格"
            } else {
                "○ 无障碍服务未开启，点击这里去系统设置"
            }
            setTextColor(getColor(if (enabled) R.color.status_online else R.color.status_error))
        }
        if (enabled == lastAccessibilityEnabled) return
        lastAccessibilityEnabled = enabled
        if (!enabled) {
            Snackbar.make(
                window.decorView,
                "无障碍服务未开启，自动比价不可用",
                Snackbar.LENGTH_LONG,
            ).setAction("去开启") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
        }
    }

    private fun openMeituanCompare(target: PlatformTarget, orderId: String? = null) {
        if (!cartController.canCompare) {
            Snackbar.make(
                window.decorView,
                getString(R.string.meituan_cart_consent_required),
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        val intent = Intent(this, MeituanCompareActivity::class.java)
            .putExtra(MeituanCompareActivity.EXTRA_STORE_KEYWORD, target.storeKeyword)
            .putExtra(MeituanCompareActivity.EXTRA_PRODUCT_KEYWORD, target.productKeyword)
        orderId?.let { intent.putExtra(MeituanCompareActivity.EXTRA_ORDER_ID, it) }
        startActivity(intent)
    }

    private fun showMissingMeituanTarget(order: FavoriteOrder) {
        Snackbar.make(
            window.decorView,
            "这条查询还没有配置美团店铺和商品",
            Snackbar.LENGTH_LONG,
        ).setAction("去编辑") {
            startActivity(
                Intent(this, FavoriteOrderEditActivity::class.java)
                    .putExtra(FavoriteOrderEditActivity.EXTRA_ORDER_ID, order.id),
            )
        }.show()
    }

    private fun showMeituanCartConsentIfNeeded() {
        if (cartController.canCompare) return
        AlertDialog.Builder(this)
            .setTitle(R.string.meituan_cart_consent_title)
            .setMessage(R.string.meituan_cart_consent_message)
            .setNegativeButton(R.string.meituan_cart_consent_reject) { _, _ ->
                cartController.rejectConsent()
            }
            .setPositiveButton(R.string.meituan_cart_consent_accept) { _, _ ->
                cartController.acceptConsent()
            }
            .setCancelable(false)
            .show()
    }

    private fun Map<Platform, PlatformTarget>.meituanTarget(): PlatformTarget? =
        this[Platform.MEITUAN_DELIVERY]
            ?: this[Platform.MEITUAN]
            ?: this[Platform.MEITUAN_PICKUP]
}
