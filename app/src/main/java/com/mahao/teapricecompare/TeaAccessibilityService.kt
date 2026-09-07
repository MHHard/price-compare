package com.mahao.teapricecompare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the foreground window content of the target delivery apps.
 * Phase 1 only exposes a raw node-tree dump for manual inspection; the
 * platform-specific search/navigate/read-price logic comes in Phase 2
 * once real UI structures have been captured from a device.
 */
class TeaAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: TeaAccessibilityService? = null

        /** Latest tree captured while a target app was actually in the foreground. */
        @Volatile
        var lastCapture: String? = null
            private set

        @Volatile
        private var lastCaptureAt = 0L

        private const val CAPTURE_THROTTLE_MS = 800L

        fun isRunning(): Boolean = instance != null

        /** Reads the OS-level accessibility settings, not just whether our service process is alive. */
        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = "${context.packageName}/${TeaAccessibilityService::class.java.name}"
            return enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
        }

        private fun buildTree(root: AccessibilityNodeInfo): String {
            val builder = StringBuilder()
            builder.append("package=").append(root.packageName).append('\n')
            appendNode(root, 0, builder)
            return builder.toString()
        }

        private fun appendNode(node: AccessibilityNodeInfo, depth: Int, builder: StringBuilder) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            builder.append("  ".repeat(depth))
                .append(node.className)
                .append(" id=").append(node.viewIdResourceName)
                .append(" text=\"").append(node.text).append('"')
                .append(" desc=\"").append(node.contentDescription).append('"')
                .append(" bounds=").append(bounds)
                .append('\n')
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                appendNode(child, depth + 1, builder)
            }
        }

        /** Root of whatever window is currently in front, or null if the service isn't connected. */
        fun currentRoot(): AccessibilityNodeInfo? = instance?.rootInActiveWindow

        /** Captures the current target window immediately instead of waiting for an event. */
        fun captureCurrentWindow(expectedPackageName: String): String? {
            val root = instance?.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != expectedPackageName) return null
            return buildTree(root).also {
                lastCapture = it
                lastCaptureAt = System.currentTimeMillis()
            }
        }

        /** First node in the current window matching [predicate], depth-first. */
        fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
            currentRoot()?.let { findNodeIn(it, predicate) }

        /** All nodes in the current window matching [predicate], depth-first order. */
        fun findAllNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
            val results = mutableListOf<AccessibilityNodeInfo>()
            currentRoot()?.let { collectNodes(it, predicate, results) }
            return results
        }

        private fun findNodeIn(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findNodeIn(child, predicate)?.let { return it }
            }
            return null
        }

        private fun collectNodes(
            node: AccessibilityNodeInfo,
            predicate: (AccessibilityNodeInfo) -> Boolean,
            out: MutableList<AccessibilityNodeInfo>,
        ) {
            if (predicate(node)) out.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectNodes(child, predicate, out)
            }
        }

        /**
         * Clicks [node]: tries the accessibility CLICK action on the nearest clickable ancestor
         * first, then falls back to synthesizing a real tap gesture at the node's on-screen
         * center — some apps handle touch without marking the view accessibility-clickable, so
         * ACTION_CLICK silently no-ops on them.
         */
        fun click(node: AccessibilityNodeInfo): Boolean {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    break
                }
                target = target.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return tap(bounds.centerX(), bounds.centerY())
        }

        /** Synthesizes a real touch tap at absolute screen coordinates. */
        fun tap(x: Int, y: Int): Boolean {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return instance?.dispatchGesture(gesture, null, null) ?: false
        }

        /** Sets [text] into an editable [node] (e.g. a search box). */
        fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        fun pressBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (Platform.fromPackageName(packageName) == null) return

        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < CAPTURE_THROTTLE_MS) return

        val root = rootInActiveWindow ?: return
        // rootInActiveWindow is read asynchronously relative to the event; by the time we get
        // here the foreground window may have already changed (e.g. user swiped to recents).
        // Only accept the capture if it still actually belongs to the app that fired the event.
        if (root.packageName?.toString() != packageName) return
        lastCapture = buildTree(root)
        lastCaptureAt = now
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}
