package com.mahao.teapricecompare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeySummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        // API Key 行：点击进入子页
        apiKeySummary = findViewById(R.id.apiKeySummary)
        findViewById<View>(R.id.apiKeyRow).setOnClickListener {
            startActivity(Intent(this, ApiKeyEditActivity::class.java))
        }

        // 暗色模式 segmented
        findViewById<View>(R.id.segSystem).setOnClickListener { setNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        findViewById<View>(R.id.segDark).setOnClickListener { setNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        findViewById<View>(R.id.segLight).setOnClickListener { setNightMode(AppCompatDelegate.MODE_NIGHT_NO) }

        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
        )
    }

    override fun onResume() {
        super.onResume()
        val stored = SettingsStore(this).deepSeekApiKey
        apiKeySummary.text = if (stored.isNullOrBlank()) "未设置"
        else "sk-${"•".repeat(4)}${stored.takeLast(4)}"
    }

    private fun setNightMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
        // 重绘 segmented 视觉（系统会自动 recreate，这里只更新颜色）
        refreshSegmented(mode)
    }

    private fun refreshSegmented(mode: Int) {
        val active = when (mode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> R.id.segSystem
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.segDark
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.segLight
            else -> R.id.segDark
        }
        listOf(R.id.segSystem, R.id.segDark, R.id.segLight).forEach { id ->
            val tv = findViewById<TextView>(id)
            if (id == active) {
                tv.setBackgroundResource(R.drawable.bg_segment_on)
                tv.setTextColor(getColor(R.color.text_on_primary))
            } else {
                tv.background = null
                tv.setTextColor(getColor(R.color.text_tertiary))
            }
        }
    }
}
