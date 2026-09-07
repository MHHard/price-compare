package com.mahao.teapricecompare

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/** Polls [TeaAccessibilityService] until a matching node shows up or [timeoutMs] elapses. */
suspend fun waitForNode(
    timeoutMs: Long,
    intervalMs: Long = 300,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        TeaAccessibilityService.findNode(predicate)?.let { return it }
        delay(intervalMs)
    }
    return null
}

suspend fun waitForNodes(
    timeoutMs: Long,
    intervalMs: Long = 300,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): List<AccessibilityNodeInfo> {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = TeaAccessibilityService.findAllNodes(predicate)
        if (found.isNotEmpty()) return found
        delay(intervalMs)
    }
    return emptyList()
}
