package com.mahao.teapricecompare

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.textfield.TextInputEditText

class ApiKeyEditActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.setupWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_key_edit)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val store = SettingsStore(this)
        val apiKeyEditText = findViewById<TextInputEditText>(R.id.apiKeyEditText)
        apiKeyEditText.setText(store.deepSeekApiKey.orEmpty())

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<View>(R.id.saveApiKeyButton).setOnClickListener {
            store.deepSeekApiKey = apiKeyEditText.text.toString().trim()
            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        EdgeToEdge.apply(
            root = findViewById(R.id.rootView),
            topBar = findViewById(R.id.topBar),
            scroll = findViewById(R.id.scrollView),
            bottom = findViewById(R.id.bottomBar),
        )
    }
}
