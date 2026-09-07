package com.mahao.teapricecompare

import org.json.JSONArray
import org.json.JSONObject

enum class RecoveryAction {
    RETRY_CURRENT,
    SCROLL_AND_SCAN,
    SWITCH_CATEGORY,
    SEARCH_VARIANT,
    OPEN_CANDIDATE,
    SKIP_STORE,
    ASK_USER,
    STOP,
}

enum class RecoveryStage {
    SEARCH,
    STORE,
    CATEGORY,
    PRODUCT,
    CART,
    CHECKOUT,
}

enum class RecoveryFailureCode {
    STORE_CARD_NOT_FOUND,
    PRODUCT_NOT_FOUND,
    PRICE_NOT_FOUND,
    BUNDLE_NOT_FOUND,
    SPEC_ACTION_NOT_FOUND,
    PAGE_UNCLEAR,
}

data class QueryBudgetSnapshot(
    val callsUsed: Int,
    val maxCalls: Int,
    val totalTokensUsed: Int,
    val maxTotalTokens: Int,
    val costUsdUsed: Double,
    val maxCostUsd: Double,
    val recoveryStepsUsed: Int,
    val maxRecoverySteps: Int,
) {
    val callsRemaining: Int get() = (maxCalls - callsUsed).coerceAtLeast(0)
    val tokensRemaining: Int get() = (maxTotalTokens - totalTokensUsed).coerceAtLeast(0)
    val recoveryStepsRemaining: Int get() = (maxRecoverySteps - recoveryStepsUsed).coerceAtLeast(0)
}

data class AutomationObservation(
    val queryId: String,
    val route: MeituanRoute,
    val stage: RecoveryStage,
    val failureCode: RecoveryFailureCode,
    val storeKeyword: String,
    val productKeyword: String,
    val failureMessage: String,
    val attemptedKeywords: List<String> = emptyList(),
    val attemptedCategories: List<String> = emptyList(),
    val attemptedStores: List<String> = emptyList(),
    val attemptedActions: List<String> = emptyList(),
    val visibleTexts: List<String> = emptyList(),
    val controls: List<String> = emptyList(),
    val orderConstraints: OrderConstraints? = null,
    val budget: QueryBudgetSnapshot,
) {
    fun toPrompt(): String = buildString {
        appendLine("query_id=$queryId")
        appendLine("route=${route.name}")
        appendLine("stage=${stage.name}")
        appendLine("failure_code=${failureCode.name}")
        appendLine("store_keyword=${safe(storeKeyword, 80)}")
        appendLine("product_keyword=${safe(productKeyword, 80)}")
        appendLine("failure_message=${safe(failureMessage, 240)}")
        appendLine("attempted_keywords=${attemptedKeywords.joinToString(" | ") { safe(it, 60) }}")
        appendLine("attempted_categories=${attemptedCategories.joinToString(" | ") { safe(it, 60) }}")
        appendLine("attempted_stores=${attemptedStores.joinToString(" | ") { safe(it, 80) }}")
        appendLine("attempted_actions=${attemptedActions.joinToString(" | ") { safe(it, 60) }}")
        appendLine("visible_texts=")
        visibleTexts.asSequence()
            .map { safe(it, 120) }
            .filter { it.isNotBlank() }
            .take(MAX_VISIBLE_ITEMS)
            .forEach { appendLine("- $it") }
        appendLine("controls=${controls.asSequence().map { safe(it, 100) }.take(MAX_CONTROLS).joinToString(" | ")}")
        orderConstraints?.let {
            appendLine(
                "order_constraints=subtotal=${it.subtotal},minimum=${it.minimumOrder},gap=${it.gap}," +
                    "delivery_fee=${it.deliveryFee},packing_fee=${it.packingFee},orderable=${it.isOrderable}",
            )
        }
        appendLine(
            "budget=calls=${budget.callsUsed}/${budget.maxCalls},tokens=${budget.totalTokensUsed}/" +
                "${budget.maxTotalTokens},cost_usd=${budget.costUsdUsed}/${budget.maxCostUsd}," +
                "recovery_steps=${budget.recoveryStepsUsed}/${budget.maxRecoverySteps}",
        )
    }.take(MAX_PROMPT_CHARS)

    private fun safe(value: String, maxLength: Int): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(maxLength)

    companion object {
        private const val MAX_VISIBLE_ITEMS = 80
        private const val MAX_CONTROLS = 40
        private const val MAX_PROMPT_CHARS = 6_000
    }
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val keywords: List<String> = emptyList(),
    val category: String? = null,
    val candidateIndex: Int? = null,
    val reason: String = "",
    val confidence: Double = 0.0,
    val expectedState: String? = null,
) {
    internal fun validationError(): String? {
        if (expectedState.isNullOrBlank()) return "缺少 expected_state"
        if (expectedState.length > MAX_EXPECTED_STATE_LENGTH) return "expected_state 过长"
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return "confidence 无效"
        if (reason.length > MAX_REASON_LENGTH) return "reason 过长"
        if (keywords.size > MAX_KEYWORDS || keywords.any { it.isBlank() || it.length > MAX_KEYWORD_LENGTH }) {
            return "keywords 无效"
        }
        if (category?.let { it.isBlank() || it.length > MAX_CATEGORY_LENGTH } == true) {
            return "category 无效"
        }
        if (candidateIndex != null && candidateIndex < 0) return "candidate_index 无效"
        return when (action) {
            RecoveryAction.SEARCH_VARIANT ->
                if (keywords.isEmpty()) "SEARCH_VARIANT 缺少 keywords" else null
            RecoveryAction.SWITCH_CATEGORY ->
                if (category.isNullOrBlank()) "SWITCH_CATEGORY 缺少 category" else null
            RecoveryAction.OPEN_CANDIDATE ->
                if (candidateIndex == null) "OPEN_CANDIDATE 缺少 candidate_index" else null
            else -> null
        }
    }

    companion object {
        fun fromJson(raw: String): RecoveryDecision? =
            runCatching { fromJson(JSONObject(raw)) }.getOrNull()

        fun fromJson(json: JSONObject): RecoveryDecision? {
            val keys = json.keys().asSequence().toSet()
            if (!keys.all { it in ALLOWED_KEYS }) return null
            val action = (json.opt("action") as? String)?.takeIf { it.isNotBlank() }
                ?.let { value -> runCatching { RecoveryAction.valueOf(value) }.getOrNull() }
                ?: return null
            val keywords = if (json.has("keywords")) {
                val array = json.optJSONArray("keywords") ?: return null
                readStringArray(array) ?: return null
            } else {
                emptyList()
            }
            val candidateIndex = if (json.has("candidate_index")) {
                val number = json.opt("candidate_index") as? Number ?: return null
                number.toInt().takeIf { number.toDouble() == it.toDouble() }
            } else {
                null
            }
            if (json.has("candidate_index") && candidateIndex == null) return null
            val confidence = if (json.has("confidence")) {
                (json.opt("confidence") as? Number)?.toDouble() ?: return null
            } else {
                0.0
            }
            val category = if (json.has("category")) {
                (json.opt("category") as? String)?.takeIf { it.isNotBlank() } ?: return null
            } else {
                null
            }
            val reason = if (json.has("reason")) {
                json.opt("reason") as? String ?: return null
            } else {
                ""
            }
            val expectedState = if (json.has("expected_state")) {
                (json.opt("expected_state") as? String)?.takeIf { it.isNotBlank() } ?: return null
            } else {
                null
            }
            val decision = RecoveryDecision(
                action = action,
                keywords = keywords,
                category = category,
                candidateIndex = candidateIndex,
                reason = reason,
                confidence = confidence,
                expectedState = expectedState,
            )
            return decision.takeIf { it.validationError() == null }
        }

        fun stop(reason: String, expectedState: String): RecoveryDecision = RecoveryDecision(
            action = RecoveryAction.STOP,
            reason = reason.take(MAX_REASON_LENGTH),
            expectedState = expectedState.take(MAX_EXPECTED_STATE_LENGTH),
        )

        private fun readStringArray(array: JSONArray): List<String>? {
            if (array.length() !in 1..MAX_KEYWORDS) return null
            val values = mutableListOf<String>()
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: return null
                values += value
            }
            return values
        }

        private val ALLOWED_KEYS = setOf(
            "action",
            "keywords",
            "category",
            "candidate_index",
            "reason",
            "confidence",
            "expected_state",
        )
        private const val MAX_KEYWORDS = 3
        private const val MAX_KEYWORD_LENGTH = 80
        private const val MAX_CATEGORY_LENGTH = 80
        private const val MAX_REASON_LENGTH = 240
        private const val MAX_EXPECTED_STATE_LENGTH = 40
    }
}

/** Calls DeepSeek only for a failure/ambiguity observation and returns a typed safe decision. */
class AgentRecoveryPlanner(
    private val client: DeepSeekClient,
    private val queryBudget: QueryBudget? = null,
) {

    suspend fun decide(observation: AutomationObservation): RecoveryDecision {
        if (queryBudget != null && !queryBudget.canStartRecovery()) {
            return RecoveryDecision.stop("已达到本次查询的 Agent 预算限制", observation.stage.name)
        }
        val result = client.complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = observation.toPrompt(),
            phase = "agent_recovery",
            responseFormatJson = true,
            maxTokens = MAX_RESPONSE_TOKENS,
            requiresRecoveryStep = true,
        )
        if (!result.success || result.content.isNullOrBlank()) {
            return RecoveryDecision.stop(
                result.error ?: result.ledgerError ?: "Agent 未返回可用恢复决策",
                observation.stage.name,
            )
        }
        return RecoveryDecision.fromJson(result.content)
            ?: RecoveryDecision.stop("Agent 返回的恢复 JSON 不符合白名单契约", observation.stage.name)
    }

    companion object {
        private const val MAX_RESPONSE_TOKENS = 256
        private const val SYSTEM_PROMPT = """
你是美团自动查价流程的失败恢复判断器。只能返回 JSON，不要输出 Markdown 或其他文字。
action 只能是 RETRY_CURRENT、SCROLL_AND_SCAN、SWITCH_CATEGORY、SEARCH_VARIANT、OPEN_CANDIDATE、SKIP_STORE、ASK_USER、STOP。
SEARCH_VARIANT 必须提供 1 到 3 个页面可能存在的关键词；SWITCH_CATEGORY 必须提供页面已有的 category；OPEN_CANDIDATE 必须提供 candidate_index；所有动作必须提供 expected_state。
不要返回价格、坐标、脚本、URL、支付、提交订单或任何页面外凭据。不能凭空编造商品；无法确认时返回 ASK_USER 或 STOP。
"""
    }
}
