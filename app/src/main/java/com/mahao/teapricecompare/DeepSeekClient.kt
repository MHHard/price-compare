package com.mahao.teapricecompare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ChatCompletionResult(
    val content: String? = null,
    val usage: DeepSeekUsage? = null,
    val model: String? = null,
    val requestId: String? = null,
    val responseCode: Int? = null,
    val error: String? = null,
    val usageEstimated: Boolean = false,
    val ledgerError: String? = null,
) {
    val success: Boolean
        get() = error == null && ledgerError == null && responseCode != null && responseCode in 200..299

    val hasCompleteUsage: Boolean
        get() = usage?.isComplete == true
}

/** DeepSeek chat client with optional query-level accounting for Agent/Comparator integrations. */
class DeepSeekClient(
    private val apiKey: String,
    private val queryBudget: QueryBudget? = null,
    private val usageLedgerStore: UsageLedgerStore? = null,
    private val queryId: String? = null,
    private val defaultPhase: String = "deepseek",
    private val priceCatalog: DeepSeekPriceCatalog = DeepSeekPriceCatalog.flashOffPeak,
    private val usdToCnyRate: Double = SettingsStore.DEFAULT_USD_TO_CNY_RATE,
) {

    /** Public metadata-preserving entry point for later Agent/Comparator callers. */
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        phase: String = defaultPhase,
        responseFormatJson: Boolean = false,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        requiresRecoveryStep: Boolean = false,
    ): ChatCompletionResult {
        val boundedMaxTokens = maxTokens.coerceIn(1, MAX_REQUEST_MAX_TOKENS)
        val estimatedUsage = estimatedUsage(systemPrompt, userPrompt, boundedMaxTokens)
        val reservation = queryBudget?.reserve(
            estimatedUsage.totalTokens,
            priceCatalog.cost(estimatedUsage),
            requiresRecoveryStep = requiresRecoveryStep,
        )
        if (queryBudget != null && reservation == null) {
            return ChatCompletionResult(error = "Query budget exceeded before request")
        }

        val startedAt = System.currentTimeMillis()
        val result = try {
            withContext(Dispatchers.IO) {
                performRequest(systemPrompt, userPrompt, responseFormatJson, boundedMaxTokens)
            }
        } catch (exception: Exception) {
            ChatCompletionResult(error = sanitizeClientError(exception.message))
        }
        var accountedResult = result.copy(usageEstimated = result.usage?.isComplete != true)
        val usage = usageForAccounting(accountedResult, estimatedUsage)
        val costUsd = priceCatalog.cost(usage)
        if (queryBudget != null && reservation != null) {
            queryBudget.settle(reservation, usage, costUsd)
        }
        val ledgerStore = usageLedgerStore
        val currentQueryId = queryId
        if (ledgerStore != null && currentQueryId != null) {
            val rate = safeExchangeRate(usdToCnyRate)
            val persisted = runCatching {
                ledgerStore.append(
                    UsageLedgerRecord(
                        queryId = currentQueryId,
                        requestId = result.requestId,
                        apiRequestId = result.requestId,
                        phase = phase,
                        model = result.model ?: priceCatalog.model,
                        startedAt = startedAt,
                        durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                        usage = usage,
                        priceVersion = priceCatalog.priceVersion,
                        billingPeriod = priceCatalog.billingPeriod,
                        costUsd = costUsd,
                        usdToCnyRate = rate,
                        costCny = safeCostCny(costUsd, rate),
                        success = accountedResult.success,
                        error = accountedResult.error,
                        usageEstimated = accountedResult.usageEstimated,
                    ),
                )
            }.getOrDefault(false)
            if (!persisted) {
                accountedResult = accountedResult.copy(ledgerError = "usage_ledger_write_failed")
            }
        }
        return accountedResult
    }

    internal fun safeExchangeRate(rate: Double): Double =
        rate.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    internal fun safeCostCny(costUsd: Double, rate: Double): Double {
        val safeUsd = costUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val safeRate = safeExchangeRate(rate)
        return (safeUsd * safeRate).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    internal fun estimatedUsage(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
    ): DeepSeekUsage {
        val boundedMaxTokens = maxTokens.coerceIn(1, MAX_REQUEST_MAX_TOKENS)
        val promptBytes = (systemPrompt + userPrompt).toByteArray(StandardCharsets.UTF_8).size.toLong()
        val estimatedPromptTokens = (promptBytes + ESTIMATED_MESSAGE_OVERHEAD_TOKENS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedTotalTokens = (promptBytes + ESTIMATED_MESSAGE_OVERHEAD_TOKENS + boundedMaxTokens)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return DeepSeekUsage(
            promptTokens = estimatedPromptTokens,
            completionTokens = boundedMaxTokens,
            cacheMissTokens = estimatedPromptTokens,
            totalTokens = estimatedTotalTokens,
        )
    }

    internal fun usageForAccounting(
        result: ChatCompletionResult,
        estimatedUsage: DeepSeekUsage,
    ): DeepSeekUsage = result.usage?.takeIf { it.isComplete } ?: estimatedUsage

    /** Returns the best matching candidate index, or null when no candidate is plausible. */
    suspend fun matchStore(storeKeyword: String, candidates: List<String>): Int? {
        val numbered = candidates.mapIndexed { i, name -> "$i: $name" }.joinToString("\n")
        val result = complete(
            systemPrompt = "你是外卖店铺名称匹配助手。只回复一个数字（候选编号）或者 -1（如果没有合理匹配），不要输出任何其他文字。",
            userPrompt = "目标店铺关键词：$storeKeyword\n候选列表：\n$numbered",
            phase = "match_store",
            maxTokens = 32,
        )
        return result.content?.trim()?.toIntOrNull()?.takeIf { it in candidates.indices }
    }

    /** Extracts the final payable price from raw UI text. */
    suspend fun parseFinalPrice(uiText: String): Double? {
        val result = complete(
            systemPrompt = "你是外卖订单价格解析助手。根据给出的界面文字片段，找出用户最终需要支付的价格（合计/应付/实付，已包含配送费、已减去优惠券）。只回复一个数字，单位是元，不要带货币符号，如果找不到就回复 -1，不要输出任何其他文字。",
            userPrompt = uiText,
            phase = "parse_final_price",
            maxTokens = 32,
        )
        return result.content?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    suspend fun parseDrawerPrice(uiText: String, mode: MeituanRoute): Double? {
        val result = complete(
            systemPrompt = "你是美团店内待付款购物车价格解析助手。当前模式是${if (mode == MeituanRoute.PICKUP) "自取" else "外送"}。只提取当前店铺、当前模式底部待付款购物车的用户实际需要支付金额：优先读取‘到手约’、‘合计’或‘应付’后的金额；外送如果显示‘差xx起送’或‘再买xx可达起送’，说明暂时不能下单，回复 -1。不要读取商品原价、优惠金额、配送费单项或起送差额。只回复数字，找不到回复 -1。",
            userPrompt = uiText,
            phase = "parse_drawer_price",
            maxTokens = 32,
        )
        return result.content?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    private fun performRequest(
        systemPrompt: String,
        userPrompt: String,
        responseFormatJson: Boolean,
        maxTokens: Int,
    ): ChatCompletionResult {
        val requestBody = JSONObject().apply {
            put("model", priceCatalog.model)
            put("temperature", 0)
            put("max_tokens", maxTokens)
            if (responseFormatJson) put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                },
            )
        }

        val connection = URL(API_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseChatCompletionResponse(responseText, responseCode)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseChatCompletionResponse(responseText: String, responseCode: Int): ChatCompletionResult {
        val json = runCatching { JSONObject(responseText) }.getOrNull()
        val usage = json?.optJSONObject("usage")?.let(DeepSeekUsage::fromJson)
        val model = json?.optString("model")?.takeIf { it.isNotBlank() }
        val requestId = json?.optString("id")?.takeIf { it.isNotBlank() }
        if (responseCode !in 200..299) {
            return ChatCompletionResult(
                usage = usage,
                model = model,
                requestId = requestId,
                responseCode = responseCode,
                error = "DeepSeek API error $responseCode",
            )
        }
        val content = json?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
        return if (json == null || content == null) {
            ChatCompletionResult(
                usage = usage,
                model = model,
                requestId = requestId,
                responseCode = responseCode,
                error = "Invalid DeepSeek response",
            )
        } else {
            ChatCompletionResult(
                content = content,
                usage = usage,
                model = model,
                requestId = requestId,
                responseCode = responseCode,
            )
        }
    }

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DEFAULT_MAX_TOKENS = 512
        private const val MAX_REQUEST_MAX_TOKENS = 2_048
        private const val ESTIMATED_MESSAGE_OVERHEAD_TOKENS = 64L
    }
}

private fun sanitizeClientError(error: String?): String =
    (error ?: "DeepSeek request failed")
        .replace(Regex("(?i)authorization\\s*:\\s*[^,;\\s]+"), "authorization: [redacted]")
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._-]+"), "Bearer [redacted]")
        .replace(Regex("(?i)sk-[A-Za-z0-9_-]+"), "[redacted-key]")
        .take(500)
