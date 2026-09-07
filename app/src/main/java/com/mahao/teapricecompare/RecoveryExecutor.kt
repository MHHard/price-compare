package com.mahao.teapricecompare

interface RecoveryActions {
    suspend fun retryCurrent(): Boolean
    suspend fun scrollAndScan(): Boolean
    suspend fun switchCategory(category: String): Boolean
    suspend fun searchVariant(keyword: String): Boolean
    suspend fun openCandidate(index: Int): Boolean
    suspend fun skipStore(): Boolean
}

data class RecoveryExecutionResult(
    val isSuccess: Boolean,
    val terminal: Boolean = false,
    val error: String? = null,
)

/** Re-checks the typed decision before dispatching one local, non-coordinate action. */
class RecoveryExecutor(
    private val actions: RecoveryActions,
    private val currentState: String? = null,
) {

    suspend fun execute(decision: RecoveryDecision): RecoveryExecutionResult {
        decision.validationError()?.let { return failure(it) }
        if (currentState != null && decision.expectedState != currentState) {
            return failure("expected_state 与当前页面状态不一致")
        }
        return when (decision.action) {
            RecoveryAction.RETRY_CURRENT -> run("重试当前页面") { actions.retryCurrent() }
            RecoveryAction.SCROLL_AND_SCAN -> run("滚动并扫描") { actions.scrollAndScan() }
            RecoveryAction.SWITCH_CATEGORY -> run("切换分类") {
                actions.switchCategory(decision.category!!)
            }
            RecoveryAction.SEARCH_VARIANT -> {
                var attempted = false
                var success = false
                for (keyword in decision.keywords) {
                    attempted = true
                    if (actions.searchVariant(keyword)) {
                        success = true
                        break
                    }
                }
                if (success) RecoveryExecutionResult(isSuccess = true)
                else failure(if (attempted) "搜索变体后仍未验证到页面状态" else "没有可搜索的变体")
            }
            RecoveryAction.OPEN_CANDIDATE -> run("打开候选") {
                actions.openCandidate(decision.candidateIndex!!)
            }
            RecoveryAction.SKIP_STORE -> run("跳过店铺") { actions.skipStore() }
            RecoveryAction.ASK_USER -> RecoveryExecutionResult(
                isSuccess = false,
                terminal = true,
                error = decision.reason.ifBlank { "需要用户确认后才能继续" },
            )
            RecoveryAction.STOP -> RecoveryExecutionResult(
                isSuccess = false,
                terminal = true,
                error = decision.reason.ifBlank { "Agent 要求停止恢复" },
            )
        }
    }

    private suspend fun run(
        label: String,
        action: suspend () -> Boolean,
    ): RecoveryExecutionResult = if (action()) {
        RecoveryExecutionResult(isSuccess = true)
    } else {
        failure("$label未通过本地验证")
    }

    private fun failure(message: String) = RecoveryExecutionResult(
        isSuccess = false,
        error = message,
    )
}
