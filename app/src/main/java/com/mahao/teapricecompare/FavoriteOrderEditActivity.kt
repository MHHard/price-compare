package com.mahao.teapricecompare

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class FavoriteOrderEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ORDER_ID = "order_id"
        val EDITABLE_PLATFORMS = listOf(Platform.MEITUAN)
    }

    private lateinit var store: FavoriteOrderStore
    private var editingOrderId: String? = null

    /** 每平台 enabled 状态 + 输入框引用 */
    private val enabledPlatforms = mutableSetOf<Platform>()
    private val platformCards = mutableMapOf<Platform, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_order_edit)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        store = FavoriteOrderStore(this)

        // MVP 只保留一个美团输入；买券、外卖、自取由美团自动化流程判断。
        platformCards[Platform.MEITUAN] = findViewById(R.id.platformMeituanVoucher)

        EDITABLE_PLATFORMS.forEach { platform -> bindPlatformCard(platform) }

        // 标题 & 返回
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val titleView = findViewById<TextView>(R.id.screenTitle)
        val saveTop = findViewById<MaterialButton>(R.id.saveButton)
        val saveBottom = findViewById<MaterialButton>(R.id.saveButtonBottom)

        editingOrderId = intent.getStringExtra(EXTRA_ORDER_ID)
        if (editingOrderId != null) {
            titleView.setText(R.string.title_edit_favorite)
            loadExisting(editingOrderId!!)
        } else {
            setPlatformEnabled(Platform.MEITUAN, true)
        }

        val saveAction = View.OnClickListener { onSave() }
        saveTop.setOnClickListener(saveAction)
        saveBottom.setOnClickListener(saveAction)
        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
        )
    }

    private fun loadExisting(id: String) {
        val order = store.list().firstOrNull { it.id == id } ?: return
        findViewById<TextInputEditText>(R.id.nameEditText).setText(order.name)
        EDITABLE_PLATFORMS.forEach { p ->
            val target = order.targets[p]
                ?: order.targets[Platform.MEITUAN_DELIVERY]
                ?: order.targets[Platform.MEITUAN_PICKUP]
            setPlatformEnabled(p, target != null)
            val card = platformCards[p] ?: return@forEach
            if (target != null) {
                card.findViewById<TextInputEditText>(R.id.storeEditText).setText(target.storeKeyword)
                card.findViewById<TextInputEditText>(R.id.productEditText).setText(target.productKeyword)
            }
        }
    }

    private fun bindPlatformCard(platform: Platform) {
        val card = platformCards[platform] ?: return
        val ctx = this

        val logoBg = ContextCompat.getDrawable(ctx, logoBgFor(platform)) as GradientDrawable
        card.findViewById<FrameLayout>(R.id.platformLogo).background = logoBg
        card.findViewById<TextView>(R.id.platformLogoText).text = shortChar(platform)
        card.findViewById<TextView>(R.id.platformName).setText(nameFor(platform))
        card.findViewById<TextView>(R.id.platformBadge).text = "已开启"

        val toggle = card.findViewById<FrameLayout>(R.id.toggleContainer)
        toggle.setOnClickListener { setPlatformEnabled(platform, !enabledPlatforms.contains(platform)) }
    }

    private fun setPlatformEnabled(platform: Platform, enabled: Boolean) {
        val card = platformCards[platform] ?: return
        if (enabled) enabledPlatforms.add(platform) else enabledPlatforms.remove(platform)

        val toggle = card.findViewById<FrameLayout>(R.id.toggleContainer)
        val thumb = card.findViewById<View>(R.id.toggleThumb)
        val badge = card.findViewById<TextView>(R.id.platformBadge)
        val storeInput = card.findViewById<TextInputEditText>(R.id.storeEditText)
        val productInput = card.findViewById<TextInputEditText>(R.id.productEditText)

        toggle.setBackgroundResource(if (enabled) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
        (thumb.layoutParams as FrameLayout.LayoutParams).gravity = if (enabled)
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        else
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        thumb.requestLayout()
        badge.text = if (enabled) "已开启" else "已关闭"
        storeInput.isEnabled = enabled
        productInput.isEnabled = enabled
        storeInput.alpha = if (enabled) 1f else 0.4f
        productInput.alpha = if (enabled) 1f else 0.4f
    }

    private fun onSave() {
        val name = findViewById<TextInputEditText>(R.id.nameEditText).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_name_required), Toast.LENGTH_SHORT).show()
            return
        }

        val targets = mutableMapOf<Platform, PlatformTarget>()
        EDITABLE_PLATFORMS.forEach { platform ->
            if (!enabledPlatforms.contains(platform)) return@forEach
            val card = platformCards[platform] ?: return@forEach
            val storeKeyword = card.findViewById<TextInputEditText>(R.id.storeEditText)
                .text.toString().trim()
            val productKeyword = card.findViewById<TextInputEditText>(R.id.productEditText)
                .text.toString().trim()
            if (storeKeyword.isNotEmpty() && productKeyword.isNotEmpty()) {
                targets[platform] = PlatformTarget(storeKeyword, productKeyword)
            }
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_platform_required), Toast.LENGTH_SHORT).show()
            return
        }

        val id = editingOrderId
        if (id == null) {
            store.add(name, targets)
        } else {
            store.update(
                FavoriteOrder(
                    id = id,
                    name = name,
                    targets = targets,
                    lastComparison = store.get(id)?.lastComparison,
                ),
            )
        }
        finish()
    }

    private fun logoBgFor(platform: Platform) = when (platform) {
        Platform.JD -> R.drawable.bg_logo_jd
        Platform.MEITUAN -> R.drawable.bg_logo_mt_voucher
        Platform.MEITUAN_DELIVERY -> R.drawable.bg_logo_mt_delivery
        Platform.MEITUAN_PICKUP -> R.drawable.bg_logo_mt_pickup
    }

    private fun shortChar(platform: Platform) = when (platform) {
        Platform.JD -> "京"
        Platform.MEITUAN -> "美"
        Platform.MEITUAN_DELIVERY -> "外"
        Platform.MEITUAN_PICKUP -> "取"
    }

    private fun nameFor(platform: Platform) = when (platform) {
        Platform.JD -> R.string.platform_jd
        Platform.MEITUAN -> R.string.platform_meituan_voucher
        Platform.MEITUAN_DELIVERY -> R.string.platform_meituan_delivery
        Platform.MEITUAN_PICKUP -> R.string.platform_meituan_pickup
    }
}
