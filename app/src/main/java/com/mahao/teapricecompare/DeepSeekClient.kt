package com.mahao.teapricecompare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Thin client for DeepSeek's chat-completions API, used only for the two "fuzzy" steps that
 * plain resource-id/text matching can't handle robustly: picking the right store among several
 * similar search results, and parsing a final price out of an unfamiliar UI layout.
 */
class DeepSeekClient(private val apiKey: String) {

    private suspend fun chat(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("model", "deepseek-v4-flash")
            put("temperature", 0)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                },
            )
        }

        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) {
                throw IllegalStateException("DeepSeek API error $responseCode: $responseText")
            }
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Given a list of candidate store names from a search-results page, returns the index of the
     * one that best matches [storeKeyword], or null if none plausibly match.
     */
    suspend fun matchStore(storeKeyword: String, candidates: List<String>): Int? {
        val numbered = candidates.mapIndexed { i, name -> "$i: $name" }.joinToString("\n")
        val content = chat(
            systemPrompt = "你是外卖店铺名称匹配助手。只回复一个数字（候选编号）或者 -1（如果没有合理匹配），不要输出任何其他文字。",
            userPrompt = "目标店铺关键词：$storeKeyword\n候选列表：\n$numbered",
        )
        return content.trim().toIntOrNull()?.takeIf { it in candidates.indices }
    }

    /** Extracts the final payable price (already including delivery fee / minus coupons) from raw UI text. */
    suspend fun parseFinalPrice(uiText: String): Double? {
        val content = chat(
            systemPrompt = "你是外卖订单价格解析助手。根据给出的界面文字片段，找出用户最终需要支付的价格（合计/应付/实付，已包含配送费、已减去优惠券）。只回复一个数字，单位是元，不要带货币符号，如果找不到就回复 -1，不要输出任何其他文字。",
            userPrompt = uiText,
        )
        return content.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    }

    suspend fun parseDrawerPrice(uiText: String, mode: MeituanRoute): Double? {
        val content = chat(
            systemPrompt = "你是美团购物车价格解析助手。当前模式是${if (mode == MeituanRoute.PICKUP) "自取" else "外送"}。只提取当前模式底部购物车的用户实际需要支付金额：优先读取‘到手约’、‘合计’或‘应付’后的金额；外送如果显示‘差xx起送’或‘再买xx可达起送’，说明暂时不能下单，回复 -1。不要读取商品原价、优惠金额、配送费单项或起送差额。只回复数字，找不到回复 -1。",
            userPrompt = uiText,
        )
        return content.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    }
}
