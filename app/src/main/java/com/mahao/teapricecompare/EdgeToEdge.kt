package com.mahao.teapricecompare

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge 适配：状态栏、导航栏、刘海/挖孔、IME 键盘。
 *
 * 用法（任一 Activity 的 setContentView 之后）：
 *
 *   EdgeToEdge.apply(contentView, topBar, scrollView, bottomBar)
 *
 * - topBar:   顶部 app bar（高度 60dp），paddingTop = statusBars
 * - scroll:   列表 / 滚动容器，paddingTop = topBar 高度，paddingBottom = gestureBars
 * - bottom:   底部固定栏（如"新增"按钮），paddingBottom = gestureBars + 8dp
 *
 * 任何一个参数传 null 都会被忽略，方便只关心部分位置。
 */
object EdgeToEdge {

    fun setupWindow(activity: android.app.Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    /**
     * 三个独立 padding 目标视图 + 根 contentView 监听 IME。
     * topBar / scroll / bottom 任意一个可传 null。
     */
    fun apply(
        root: View,
        topBar: View? = null,
        scroll: View? = null,
        bottom: View? = null,
        extraBottomDp: Int = 16,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val gesture = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom

            topBar?.let { v ->
                v.updatePadding(top = bars.top)
            }
            scroll?.let { v ->
                val bottomInset = if (ime.bottom > 0) ime.bottom else maxOf(bars.bottom, gesture)
                v.updatePadding(bottom = bottomInset + dp(root, extraBottomDp))
            }
            bottom?.let { v ->
                val bottomInset = if (ime.bottom > 0) ime.bottom else maxOf(bars.bottom, gesture)
                v.updatePadding(bottom = bottomInset + dp(root, 8))
            }
            insets
        }
    }

    /** 仅把状态栏 / 导航栏 inset 喂给某个 view 的 padding。 */
    fun applyStatusAndNavPadding(view: View, horizontal: Boolean = false) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                top = bars.top,
                bottom = bars.bottom,
                left = if (horizontal) bars.left else v.paddingLeft,
                right = if (horizontal) bars.right else v.paddingRight,
            )
            insets
        }
    }

    private fun dp(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).toInt()
}
