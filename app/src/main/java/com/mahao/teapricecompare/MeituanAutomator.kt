package com.mahao.teapricecompare

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

data class MeituanSearchResult(val error: String? = null) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Drives Meituan's search -> store -> add-to-cart -> checkout flow and reads the final payable
 * price off the checkout summary. Never taps "提交订单"/"去支付" — the whole point is to compare
 * prices, not to place the order.
 *
 */
class MeituanAutomator(
    private val context: Context,
    private val route: MeituanRoute = MeituanRoute.VOUCHER,
    private val queryBudget: QueryBudget? = null,
    private val usageLedgerStore: UsageLedgerStore? = null,
    private val queryId: String? = null,
    private val recoveryPlanner: AgentRecoveryPlanner? = null,
    private val usdToCnyRate: Double = SettingsStore.DEFAULT_USD_TO_CNY_RATE,
) {

    private var activeStoreKeyword: String = ""

    private val resultPlatform = when (route) {
        MeituanRoute.VOUCHER -> Platform.MEITUAN
        MeituanRoute.DELIVERY -> Platform.MEITUAN_DELIVERY
        MeituanRoute.PICKUP -> Platform.MEITUAN_PICKUP
    }

    suspend fun runSearchAndOpenStore(target: PlatformTarget): MeituanSearchResult {
        val searchResult = runSearch(target)
        if (!searchResult.isSuccess) return searchResult
        return openFirstStore(target.storeKeyword)
    }

    /** Collect store names that are actually exposed by the Meituan delivery result page. */
    suspend fun collectStoreCandidates(storeKeyword: String, maxStores: Int = 5): List<String> {
        if (route != MeituanRoute.DELIVERY) return emptyList()
        val names = linkedSetOf<String>()
        waitForNode(3000) {
            MeituanSelectors.isDeliverySearchResult(
                packageName = it.packageName?.toString(),
                resourceId = it.viewIdResourceName,
            )
        }
        val scrollable = TeaAccessibilityService.findAllNodes { node ->
            node.isScrollable && Rect().also { node.getBoundsInScreen(it) }.width() >= 700
        }.firstOrNull()

        waitForNode(8000) { node ->
            if (node.className?.toString() != "android.widget.TextView") return@waitForNode false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            MeituanSelectors.isDeliveryStoreCandidate(
                text = node.text?.toString(),
                storeKeyword = storeKeyword,
                top = bounds.top,
                width = bounds.width(),
                height = bounds.height(),
            )
        } ?: return emptyList()

        repeat(4) { page ->
            TeaAccessibilityService.findAllNodes { node ->
                if (node.className?.toString() != "android.widget.TextView") return@findAllNodes false
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                MeituanSelectors.isDeliveryStoreCandidate(
                    text = node.text?.toString(),
                    storeKeyword = storeKeyword,
                    top = bounds.top,
                    width = bounds.width(),
                    height = bounds.height(),
                )
            }.forEach { node ->
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(names::add)
            }
            if (names.size >= maxStores || page == 3 || scrollable == null) return@repeat
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(700)
        }
        // The next step opens candidates by name, so start from the top and let that helper
        // scroll forward again when a candidate came from a later result page.
        repeat(4) {
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(200)
        }
        return names.take(maxStores)
    }

    /** Compares external delivery and self-pickup for the store currently being inspected. */
    suspend fun compareCurrentDeliveryStore(
        target: PlatformTarget,
        storeName: String,
        apiKey: String,
    ): MeituanStoreComparison {
        if (!MeituanCartController().canCompare) {
            return unavailableStoreComparison(storeName, null, "需要先同意清空店内待付款购物车后才能自动比价")
        }
        activeStoreKeyword = target.storeKeyword
        val opened = openDeliveryStoreByName(storeName, target.storeKeyword)
        if (!opened.isSuccess) {
            return unavailableStoreComparison(storeName, null, opened.error ?: "进入店铺失败")
        }
        val clearResult = clearCurrentStoreCart()
        if (!clearResult.isSuccess) {
            return unavailableStoreComparison(
                storeName,
                null,
                clearResult.reason ?: "当前店内待付款购物车清空失败",
            )
        }
        val distance = readMerchantDistance()
        val addResult = addProductToCart(target.productKeyword)
        if (!addResult.isSuccess) {
            return unavailableStoreComparison(storeName, distance, addResult.error ?: "商品加入购物车失败")
        }
        val pickupBeforeBundle = if (route == MeituanRoute.DELIVERY) {
            if (!openCartDrawerAndSelectMode()) {
                return unavailableStoreComparison(storeName, distance, "没有打开底部购物车抽屉")
            }
            captureDrawerModePrice(MeituanRoute.PICKUP, target.productKeyword, apiKey)
        } else {
            null
        }
        val localBundle = if (route == MeituanRoute.DELIVERY) {
            ensureMinimumOrder(target)
        } else {
            LocalBundleResult()
        }
        if (!openCartDrawerAndSelectMode()) {
            return unavailableStoreComparison(storeName, distance, "没有打开底部购物车抽屉")
        }
        val prices = captureBothModePrices(
            target.productKeyword,
            apiKey,
            distance,
            localBundle,
            pickupBeforeBundle,
        )
        return MeituanStoreComparison(
            storeName = storeName,
            merchantDistance = distance,
            delivery = prices.delivery,
            pickup = prices.pickup,
        )
    }

    /** Returns from the store/cart layer to the delivery search result page without ordering. */
    suspend fun leaveStoreToSearchResults(): Boolean {
        repeat(3) {
            if (TeaAccessibilityService.findNode { node ->
                    MeituanSelectors.isDeliverySearchResult(
                        node.packageName?.toString(),
                        node.viewIdResourceName,
                    )
                } != null
            ) return true
            if (!TeaAccessibilityService.pressBack()) return false
            delay(700)
        }
        return TeaAccessibilityService.findNode { node ->
            MeituanSelectors.isDeliverySearchResult(
                node.packageName?.toString(),
                node.viewIdResourceName,
            )
        } != null
    }

    private fun unavailableStoreComparison(
        storeName: String,
        distance: String?,
        reason: String,
    ): MeituanStoreComparison = MeituanStoreComparison(
        storeName = storeName,
        merchantDistance = distance,
        delivery = MeituanModePrice(MeituanRoute.DELIVERY, error = reason),
        pickup = MeituanModePrice(MeituanRoute.PICKUP, error = reason),
        voucher = MeituanModePrice(MeituanRoute.VOUCHER, error = "本店买券暂未读取"),
    )

    /** Full flow: search, open store, add the drink to cart, go to checkout, read the final total (never pays). */
    suspend fun runFullFlow(target: PlatformTarget, apiKey: String): PriceResult {
        if (!MeituanCartController().canCompare) {
            return PriceResult(resultPlatform, error = "需要先同意清空店内待付款购物车后才能自动比价")
        }
        if (target.productKeyword.isBlank()) {
            return PriceResult(resultPlatform, error = "美团饮品关键词不能为空")
        }
        activeStoreKeyword = target.storeKeyword
        val openResult = runSearchAndOpenStore(target)
        if (!openResult.isSuccess) return PriceResult(resultPlatform, error = openResult.error)
        val merchantDistance = if (route != MeituanRoute.VOUCHER) readMerchantDistance() else null

        val clearResult = clearCurrentStoreCart()
        if (!clearResult.isSuccess) {
            return PriceResult(
                resultPlatform,
                error = clearResult.reason ?: "当前店内待付款购物车清空失败",
                merchantDistance = merchantDistance,
            )
        }

        val addResult = addProductToCart(target.productKeyword)
        if (!addResult.isSuccess) {
            return PriceResult(resultPlatform, error = addResult.error)
        }

        val pickupBeforeBundle = if (route == MeituanRoute.DELIVERY) {
            if (!openCartDrawerAndSelectMode()) {
                return PriceResult(
                    resultPlatform,
                    error = "商品已加入购物车，但没有打开底部购物车抽屉",
                    merchantDistance = merchantDistance,
                )
            }
            captureDrawerModePrice(MeituanRoute.PICKUP, target.productKeyword, apiKey)
        } else {
            null
        }
        val localBundle = if (route == MeituanRoute.DELIVERY) {
            ensureMinimumOrder(target)
        } else {
            LocalBundleResult()
        }
        val modePrices = if (route != MeituanRoute.VOUCHER) {
            if (!openCartDrawerAndSelectMode()) {
                return PriceResult(
                    resultPlatform,
                    error = "商品已加入购物车，但没有打开底部购物车抽屉",
                    merchantDistance = merchantDistance,
                )
            }
            captureBothModePrices(
                target.productKeyword,
                apiKey,
                merchantDistance,
                localBundle,
                pickupBeforeBundle,
            )
        } else {
            null
        }

        if (!openCheckout()) {
            return PriceResult(
                resultPlatform,
                error = "没有找到去结算入口，或当前模式商品不可用/没有达到起送价",
                merchantDistance = merchantDistance,
                meituanPrices = modePrices,
            )
        }

        return readFinalPriceViaAi(apiKey, merchantDistance, modePrices)
    }

    suspend fun clearCurrentStoreCart(): CartClearResult = MeituanCartController().clearCart()

    private suspend fun findProductNodeDeterministic(
        productKeyword: String,
        maxScrolls: Int = 5,
    ): AccessibilityNodeInfo? {
        findProductInCurrentCategory(productKeyword, maxScrolls)?.let { return it }

        if (route == MeituanRoute.VOUCHER) return null

        val categories = TeaAccessibilityService.findAllNodes { node ->
            if (node.className?.toString() != "android.widget.TextView") return@findAllNodes false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString().orEmpty()
            text.isNotBlank() &&
                text != "推荐" &&
                text != "温馨提示" &&
                bounds.left < 264 &&
                bounds.top >= 1500 &&
                bounds.top < 2376
        }.distinctBy { it.text?.toString() }

        for (category in categories) {
            val bounds = Rect().also { category.getBoundsInScreen(it) }
            if (!TeaAccessibilityService.tap(bounds.centerX(), bounds.centerY())) continue
            delay(600)
            findProductInCurrentCategory(productKeyword, maxScrolls = 3)?.let { return it }
        }
        return null
    }

    private suspend fun findProductNode(productKeyword: String, maxScrolls: Int = 5): AccessibilityNodeInfo? {
        findProductNodeDeterministic(productKeyword, maxScrolls)?.let { return it }
        val planner = recoveryPlanner ?: return null
        return recoverProductNode(productKeyword, planner)
    }

    private suspend fun recoverProductNode(
        productKeyword: String,
        planner: AgentRecoveryPlanner,
    ): AccessibilityNodeInfo? {
        val attemptedKeywords = linkedSetOf(productKeyword)
        val attemptedCategories = linkedSetOf<String>()
        val attemptedActions = mutableListOf<String>()
        repeat(MAX_RECOVERY_STEPS) {
            val observation = buildRecoveryObservation(
                stage = RecoveryStage.PRODUCT,
                failureCode = RecoveryFailureCode.PRODUCT_NOT_FOUND,
                failureMessage = "店铺页面没有通过本地规则找到目标商品",
                productKeyword = productKeyword,
                attemptedKeywords = attemptedKeywords.toList(),
                attemptedCategories = attemptedCategories.toList(),
                attemptedActions = attemptedActions,
            )
            val decision = planner.decide(observation)
            attemptedActions += decision.action.name
            val recoveredNode = recoverProductWithDecision(
                productKeyword = productKeyword,
                decision = decision,
                attemptedKeywords = attemptedKeywords,
                attemptedCategories = attemptedCategories,
            )
            if (recoveredNode != null) return recoveredNode
            if (decision.action in setOf(
                    RecoveryAction.SKIP_STORE,
                    RecoveryAction.ASK_USER,
                    RecoveryAction.STOP,
                )
            ) return null
        }
        return null
    }

    private suspend fun recoverProductWithDecision(
        productKeyword: String,
        decision: RecoveryDecision,
        attemptedKeywords: MutableSet<String>,
        attemptedCategories: MutableSet<String>,
    ): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = null
        val actions = object : RecoveryActions {
            override suspend fun retryCurrent(): Boolean {
                candidate = findProductNodeDeterministic(productKeyword, maxScrolls = 1)
                return candidate != null
            }

            override suspend fun scrollAndScan(): Boolean = scrollProductListOnce()

            override suspend fun switchCategory(category: String): Boolean {
                attemptedCategories += category
                return switchVisibleCategory(category)
            }

            override suspend fun searchVariant(keyword: String): Boolean {
                attemptedKeywords += keyword
                candidate = findProductNodeDeterministic(keyword, maxScrolls = 2)
                return candidate != null
            }

            override suspend fun openCandidate(index: Int): Boolean = false

            override suspend fun skipStore(): Boolean = true
        }
        val result = RecoveryExecutor(actions, currentState = "PRODUCT_LIST").execute(decision)
        if (!result.isSuccess) return null
        if (decision.action == RecoveryAction.SKIP_STORE) return null
        candidate?.let { return it }
        return findProductNodeDeterministic(productKeyword, maxScrolls = 2)
    }

    private suspend fun scrollProductListOnce(): Boolean {
        val scrollable = TeaAccessibilityService.findAllNodes { node ->
            node.isScrollable && Rect().also { node.getBoundsInScreen(it) }.width() >= 300
        }.maxByOrNull { node ->
            Rect().also { node.getBoundsInScreen(it) }.centerX()
        } ?: return false
        val moved = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (moved) delay(600)
        return moved
    }

    private suspend fun switchVisibleCategory(category: String): Boolean {
        val normalized = category.trim()
        if (normalized.isBlank()) return false
        val node = TeaAccessibilityService.findNode { current ->
            if (current.className?.toString() != "android.widget.TextView") return@findNode false
            val bounds = Rect().also { current.getBoundsInScreen(it) }
            bounds.left < 264 && bounds.top in 1500..2376 &&
                current.text?.toString()?.trim() == normalized
        } ?: return false
        val clicked = TeaAccessibilityService.click(node)
        if (clicked) delay(600)
        return clicked
    }

    private suspend fun findProductInCurrentCategory(
        productKeyword: String,
        maxScrolls: Int,
    ): AccessibilityNodeInfo? {
        waitForNode(2000) { isVisibleProductNode(it, productKeyword) }
            ?.let { return it }
        val scrollable = TeaAccessibilityService.findAllNodes { it.isScrollable }
            .maxByOrNull { node ->
                Rect().also { node.getBoundsInScreen(it) }.centerX()
            }
            ?: return null
        repeat(maxScrolls) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(600)
            waitForNode(1500) { isVisibleProductNode(it, productKeyword) }
                ?.let { return it }
        }
        return null
    }

    private fun isProductNode(node: AccessibilityNodeInfo, productKeyword: String): Boolean =
        node.text?.toString()?.contains(productKeyword) == true ||
            node.contentDescription?.toString()?.contains(productKeyword) == true

    /** Finds the action inside the same product card, which is usually labelled "选规格". */
    private fun findAddToCartControl(productNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val productBounds = Rect().also { productNode.getBoundsInScreen(it) }
        var ancestor: AccessibilityNodeInfo? = productNode.parent
        repeat(6) {
            val current = ancestor ?: return null
            val currentBounds = Rect().also { current.getBoundsInScreen(it) }

            val labeledAction = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(current, labeledAction) {
                it.text?.toString() == "选规格" ||
                    it.contentDescription?.toString() == "选规格" ||
                    it.text?.toString() == "抢购" ||
                    it.contentDescription?.toString() == "抢购" ||
                    it.text?.toString() == "加入购物车" ||
                    it.contentDescription?.toString()?.contains("加入购物车") == true
            }
            labeledAction
                .filter { isInside(it, currentBounds) && isVisibleAction(it) }
                .minByOrNull { node ->
                    Rect().also { node.getBoundsInScreen(it) }.centerY()
                }
                ?.let { return it }

            val clickable = mutableListOf<AccessibilityNodeInfo>()
            collectClickable(current, clickable)
            val sameRow = clickable.filter {
                val bounds = Rect().also { r -> it.getBoundsInScreen(r) }
                isVisibleAction(it) &&
                    kotlin.math.abs(bounds.centerY() - productBounds.centerY()) <= productBounds.height()
            }
            if (sameRow.isNotEmpty()) {
                return sameRow.maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.right }
            }
            ancestor = current.parent
        }
        return null
    }

    private fun isInside(node: AccessibilityNodeInfo, containerBounds: Rect): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return containerBounds.contains(bounds)
    }

    private fun isVisibleProductNode(
        node: AccessibilityNodeInfo,
        productKeyword: String,
    ): Boolean {
        if (!isProductNode(node, productKeyword)) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // A product title can still be exposed in the tree while its "选规格" row is
        // below the viewport. Require enough room for that action, otherwise scroll first.
        return bounds.left >= 264 && bounds.top < 2120 && bounds.bottom > 1511 && bounds.height() >= 40
    }

    private fun isVisibleAction(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return bounds.width() > 0 && bounds.height() > 0 && bounds.top < 2376 && bounds.bottom > 1511
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        if (predicate(node)) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out, predicate) }
        }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickable(it, out) }
        }
    }

    private data class LocalBundleResult(
        val candidates: List<ProductCandidate> = emptyList(),
        val orderConstraints: OrderConstraints? = null,
        val error: String? = null,
    )

    private data class BundlePlanSearch(
        val candidates: List<ProductCandidate> = emptyList(),
        val plan: BundlePlan? = null,
        val error: String? = null,
    )

    /**
     * The delivery minimum is handled with visible, locally verified products first. This method
     * deliberately returns a failure detail instead of inventing a product; the later recovery
     * Agent can use that detail when this deterministic path has no safe answer.
     */
    private suspend fun findBundlePlanWithRecovery(
        target: PlatformTarget,
        gap: Double,
    ): BundlePlanSearch {
        var scan = MeituanProductScanner.scan(target.productKeyword)
        BundleCalculator.choose(scan.candidates, gap)?.let {
            return BundlePlanSearch(scan.candidates, it)
        }
        val planner = recoveryPlanner ?: return BundlePlanSearch(
            candidates = scan.candidates,
            error = scan.error,
        )
        val attemptedActions = mutableListOf<String>()
        val attemptedKeywords = linkedSetOf(target.productKeyword)
        val attemptedCategories = linkedSetOf<String>()
        repeat(MAX_RECOVERY_STEPS) {
            val decision = planner.decide(
                buildRecoveryObservation(
                    stage = RecoveryStage.PRODUCT,
                    failureCode = RecoveryFailureCode.BUNDLE_NOT_FOUND,
                    failureMessage = "外送还差${formatMoney(gap)}，本地没有找到足够且可验证的凑单组合",
                    productKeyword = target.productKeyword,
                    attemptedKeywords = attemptedKeywords.toList(),
                    attemptedCategories = attemptedCategories.toList(),
                    attemptedActions = attemptedActions,
                ),
            )
            attemptedActions += decision.action.name
            val actions = object : RecoveryActions {
                override suspend fun retryCurrent(): Boolean = true

                override suspend fun scrollAndScan(): Boolean = scrollProductListOnce()

                override suspend fun switchCategory(category: String): Boolean {
                    attemptedCategories += category
                    return switchVisibleCategory(category)
                }

                override suspend fun searchVariant(keyword: String): Boolean {
                    attemptedKeywords += keyword
                    return findProductNodeDeterministic(keyword, maxScrolls = 2) != null
                }

                override suspend fun openCandidate(index: Int): Boolean = false

                override suspend fun skipStore(): Boolean = true
            }
            val execution = RecoveryExecutor(actions, currentState = "PRODUCT_LIST").execute(decision)
            if (execution.isSuccess && decision.action != RecoveryAction.SKIP_STORE) {
                scan = MeituanProductScanner.scan(target.productKeyword)
                BundleCalculator.choose(scan.candidates, gap)?.let {
                    return BundlePlanSearch(scan.candidates, it)
                }
            }
            if (decision.action in setOf(
                    RecoveryAction.SKIP_STORE,
                    RecoveryAction.ASK_USER,
                    RecoveryAction.STOP,
                )
            ) {
                return BundlePlanSearch(
                    candidates = scan.candidates,
                    error = decision.reason.ifBlank { scan.error },
                )
            }
        }
        return BundlePlanSearch(candidates = scan.candidates, error = scan.error)
    }

    private suspend fun ensureMinimumOrder(target: PlatformTarget): LocalBundleResult {
        if (!openCartDrawerAndSelectMode()) {
            return LocalBundleResult(error = "没有打开店内待付款购物车，无法读取外送起送状态")
        }

        var constraints = readCurrentOrderConstraints()
        if (constraints?.isOrderable == true) {
            return LocalBundleResult(orderConstraints = constraints)
        }
        if (constraints == null) {
            return LocalBundleResult(error = "无法识别外送起送状态，未自动添加凑单商品")
        }
        if (constraints.gap <= 0.0) {
            return LocalBundleResult(
                orderConstraints = constraints,
                error = "外送起送差额无法可靠识别，未自动添加凑单商品",
            )
        }

        if (!TeaAccessibilityService.pressBack()) {
            return LocalBundleResult(
                orderConstraints = constraints,
                error = "已读取起送差额，但无法返回商品列表进行凑单",
            )
        }
        delay(500)

        val bundleSearch = findBundlePlanWithRecovery(target, constraints.gap)
        val plan = bundleSearch.plan
            ?: return LocalBundleResult(
                candidates = bundleSearch.candidates,
                orderConstraints = constraints,
                error = bundleSearch.error ?: "外送还差${formatMoney(constraints.gap)}，没有找到可验证的凑单组合",
            )

        for ((index, candidate) in plan.items.withIndex()) {
            val addResult = addProductToCart(candidate)
            if (!addResult.isSuccess) {
                return LocalBundleResult(
                    candidates = bundleSearch.candidates,
                    orderConstraints = constraints,
                    error = "凑单商品「${candidate.name}」${addResult.error ?: "加入失败"}",
                )
            }
            if (!openCartDrawerAndSelectMode()) {
                return LocalBundleResult(
                    candidates = bundleSearch.candidates,
                    orderConstraints = constraints,
                    error = "凑单商品已尝试加入，但无法重新读取店内待付款购物车",
                )
            }
            constraints = readCurrentOrderConstraints()
                ?: return LocalBundleResult(
                    candidates = bundleSearch.candidates,
                    error = "凑单后无法识别外送起送状态",
                )
            if (constraints.isOrderable) {
                return LocalBundleResult(
                    candidates = bundleSearch.candidates,
                    orderConstraints = constraints,
                )
            }
            if (constraints.gap <= 0.0) {
                return LocalBundleResult(
                    candidates = bundleSearch.candidates,
                    orderConstraints = constraints,
                    error = "凑单后起送状态仍无法可靠确认",
                )
            }
            if (index < plan.items.lastIndex) {
                if (!TeaAccessibilityService.pressBack()) {
                    return LocalBundleResult(
                        candidates = bundleSearch.candidates,
                        orderConstraints = constraints,
                        error = "凑单过程中无法返回商品列表",
                    )
                }
                delay(500)
            }
        }

        return LocalBundleResult(
            candidates = bundleSearch.candidates,
            orderConstraints = constraints,
            error = "外送还差${formatMoney(constraints.gap)}，本地凑单后仍未达到起送价",
        )
    }

    private fun readCurrentOrderConstraints(): OrderConstraints? =
        MeituanSelectors.parseOrderConstraints(currentUiTexts())

    private fun currentUiTexts(): List<String> = TeaAccessibilityService.findAllNodes { true }
        .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private fun buildRecoveryObservation(
        stage: RecoveryStage,
        failureCode: RecoveryFailureCode,
        failureMessage: String,
        productKeyword: String,
        attemptedKeywords: List<String> = emptyList(),
        attemptedCategories: List<String> = emptyList(),
        attemptedActions: List<String> = emptyList(),
    ): AutomationObservation {
        val controls = TeaAccessibilityService.findAllNodes { it.isClickable }
            .map { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                listOfNotNull(
                    node.text?.toString()?.trim(),
                    node.contentDescription?.toString()?.trim(),
                    node.viewIdResourceName?.let { "id=$it" },
                    "area=${controlArea(bounds)}",
                ).joinToString(" ")
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(40)
        val budget = queryBudget?.let {
            QueryBudgetSnapshot(
                callsUsed = it.callsUsed,
                maxCalls = it.maxCalls,
                totalTokensUsed = it.totalTokensUsed,
                maxTotalTokens = it.maxTotalTokens,
                costUsdUsed = it.costUsdUsed,
                maxCostUsd = it.maxCostUsd,
                recoveryStepsUsed = it.recoveryStepsUsed,
                maxRecoverySteps = it.maxRecoverySteps,
            )
        } ?: QueryBudgetSnapshot(
            callsUsed = 0,
            maxCalls = QueryBudget.MAX_CALLS,
            totalTokensUsed = 0,
            maxTotalTokens = QueryBudget.MAX_TOTAL_TOKENS,
            costUsdUsed = 0.0,
            maxCostUsd = QueryBudget.MAX_COST_USD,
            recoveryStepsUsed = 0,
            maxRecoverySteps = QueryBudget.MAX_RECOVERY_STEPS,
        )
        return AutomationObservation(
            queryId = queryId ?: "standalone",
            route = route,
            stage = stage,
            failureCode = failureCode,
            storeKeyword = activeStoreKeyword,
            productKeyword = productKeyword,
            failureMessage = failureMessage,
            attemptedKeywords = attemptedKeywords,
            attemptedCategories = attemptedCategories,
            attemptedActions = attemptedActions,
            visibleTexts = currentUiTexts(),
            controls = controls,
            orderConstraints = readCurrentOrderConstraints(),
            budget = budget,
        )
    }

    private fun controlArea(bounds: Rect): String = when {
        bounds.left < 264 -> "category"
        bounds.top >= 2100 -> "bottom_action"
        bounds.top >= 1400 -> "product_area"
        else -> "header"
    }

    private fun deepSeek(apiKey: String, phase: String): DeepSeekClient = DeepSeekClient(
        apiKey = apiKey,
        queryBudget = queryBudget,
        usageLedgerStore = usageLedgerStore,
        queryId = queryId,
        defaultPhase = phase,
        usdToCnyRate = usdToCnyRate,
    )

    private fun formatMoney(value: Double): String = "¥${"%.2f".format(value)}"

    private suspend fun addProductToCart(productKeyword: String): MeituanStepResult =
        addProductToCart(
            ProductCandidate(
                name = productKeyword,
                isTarget = true,
                isAddable = true,
            ),
        )

    private suspend fun addProductToCart(candidate: ProductCandidate): MeituanStepResult {
        val product = findProductNode(candidate.name)
            ?: return MeituanStepResult.failure("店铺里没有找到「${candidate.name}」")
        val addControl = findAddToCartControl(product)
            ?: return MeituanStepResult.failure("找到了「${candidate.name}」，但没有找到对应的「选规格」入口")
        val bounds = Rect().also { addControl.getBoundsInScreen(it) }
        if (!TeaAccessibilityService.tap(bounds.centerX(), bounds.centerY())) {
            return MeituanStepResult.failure("点击「${candidate.name}」的「选规格」入口失败")
        }
        delay(800)

        // The current delivery UI opens a spec sheet whose selected defaults are already shown;
        // it has no "确定" button. Close that sheet and continue with the cart summary below it.
        val specPopup = waitForNode(2500) {
            MeituanSelectors.isSpecPopupMarker(
                it.text?.toString(),
                it.contentDescription?.toString(),
            )
        }
        if (specPopup != null) {
            val addToCart = waitForNode(1000) {
                MeituanSelectors.isSpecAddToCart(
                    it.text?.toString(),
                    it.contentDescription?.toString(),
                )
            }
            if (addToCart != null) {
                val addBounds = Rect().also { addToCart.getBoundsInScreen(it) }
                if (!TeaAccessibilityService.tap(addBounds.centerX(), addBounds.centerY())) {
                    return MeituanStepResult.failure("规格弹窗已打开，但点击加入购物车失败")
                }
            } else {
                // Some Meituan versions add the default spec immediately and expose only
                // the close control; do not require a nonexistent confirmation button.
                val closePopup = waitForNode(1500) {
                    it.contentDescription?.toString() == "关闭选规格"
                } ?: return MeituanStepResult.failure("规格弹窗已打开，但没有找到加入购物车或关闭入口")
                val closeBounds = Rect().also { closePopup.getBoundsInScreen(it) }
                if (!TeaAccessibilityService.tap(closeBounds.centerX(), closeBounds.centerY())) {
                    return MeituanStepResult.failure("规格弹窗已打开，但关闭失败")
                }
            }
            delay(800)
        } else if (waitForNode(800) {
            MeituanSelectors.isCartDrawerMarker(
                it.text?.toString(),
                it.contentDescription?.toString(),
            )
        } == null) {
            waitForNode(2500) { it.text?.toString() == "加入购物车" || it.text?.toString() == "确定" }
                ?.let {
                    val confirmBounds = Rect().also { node -> it.getBoundsInScreen(node) }
                    if (!TeaAccessibilityService.tap(confirmBounds.centerX(), confirmBounds.centerY())) {
                        return MeituanStepResult.failure("规格弹窗已打开，但点击确认失败")
                    }
                    delay(800)
                }
        }

        return if (waitForNode(2500) {
            it.text?.toString() == "去结算" ||
                it.contentDescription?.toString()?.contains("去结算") == true ||
                it.text?.toString() == "提交订单" ||
                it.contentDescription?.toString()?.contains("提交订单") == true
        } != null) {
            MeituanStepResult.success()
        } else if (waitForNode(1500) {
            MeituanSelectors.isCartSummaryMarker(
                it.text?.toString(),
                it.contentDescription?.toString(),
            )
        } != null) {
            MeituanStepResult.success()
        } else if (waitForNode(1200) {
            MeituanSelectors.isCartDrawerMarker(
                it.text?.toString(),
                it.contentDescription?.toString(),
            )
        } != null) {
            MeituanStepResult.failure("商品已加入购物车，但购物车抽屉没有出现去结算入口")
        } else {
            MeituanStepResult.failure("商品点击后未确认加入购物车，也没有出现「去结算」入口")
        }
    }

    private suspend fun openCartDrawerAndSelectMode(): Boolean {
        if (waitForNode(800) {
            MeituanSelectors.isCartDrawerMarker(
                it.text?.toString(),
                it.contentDescription?.toString(),
            )
        } == null) {
            if (!TeaAccessibilityService.tap(100, 2260)) return false
        }

        if (waitForNode(4000) {
                MeituanSelectors.isCartDrawerMarker(
                    it.text?.toString(),
                    it.contentDescription?.toString(),
                )
            } == null
        ) {
            return false
        }

        return selectDrawerMode(route)
    }

    private suspend fun selectDrawerMode(mode: MeituanRoute): Boolean {
        if (mode == MeituanRoute.VOUCHER) return true
        val modeTab = waitForNode(1500) { node ->
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (mode == MeituanRoute.PICKUP) {
                MeituanSelectors.isPickupDrawerTab(node.text?.toString(), bounds.left, bounds.top)
            } else {
                MeituanSelectors.isDeliveryDrawerTab(node.text?.toString(), bounds.left, bounds.top)
            }
        } ?: return false
        if (!TeaAccessibilityService.click(modeTab)) return false
        delay(600)
        return true
    }

    private suspend fun captureBothModePrices(
        productKeyword: String,
        apiKey: String,
        merchantDistance: String?,
        localBundle: LocalBundleResult = LocalBundleResult(),
        pickupBeforeBundle: MeituanModePrice? = null,
    ): MeituanPriceSnapshot {
        val delivery = captureDrawerModePrice(
            mode = MeituanRoute.DELIVERY,
            productKeyword = productKeyword,
            apiKey = apiKey,
            localBundle = localBundle,
        )
        val pickup = pickupBeforeBundle ?: captureDrawerModePrice(
            mode = MeituanRoute.PICKUP,
            productKeyword = productKeyword,
            apiKey = apiKey,
            localBundle = localBundle,
        )
        selectDrawerMode(route)
        return MeituanPriceSnapshot(
            delivery = delivery,
            pickup = pickup,
            merchantDistance = merchantDistance,
        )
    }

    private suspend fun captureDrawerModePrice(
        mode: MeituanRoute,
        productKeyword: String,
        apiKey: String,
        localBundle: LocalBundleResult = LocalBundleResult(),
    ): MeituanModePrice {
        if (!selectDrawerMode(mode)) {
            return MeituanModePrice(
                mode,
                error = "没有找到${mode.displayName()} Tab",
                candidates = localBundle.candidates,
                orderConstraints = localBundle.orderConstraints,
            )
        }
        val texts = currentUiTexts()
        val constraints = MeituanSelectors.parseOrderConstraints(texts) ?: localBundle.orderConstraints
        val unavailableMarker = if (mode == MeituanRoute.PICKUP) "仅外送" else "仅自取"
        if (texts.any { it.contains(productKeyword) && it.contains(unavailableMarker) }) {
            return MeituanModePrice(
                mode,
                error = "商品不支持${mode.displayName()}",
                candidates = localBundle.candidates,
                orderConstraints = constraints,
            )
        }
        val price = deepSeek(apiKey, "parse_drawer_price").parseDrawerPrice(texts.joinToString("\n"), mode)
        return if (price != null) {
            MeituanModePrice(
                mode,
                price = price,
                candidates = localBundle.candidates,
                orderConstraints = constraints,
            )
        } else {
            MeituanModePrice(
                mode,
                error = if (mode == MeituanRoute.DELIVERY && localBundle.error != null) {
                    localBundle.error
                } else {
                    "${mode.displayName()}暂未显示可下单价格"
                },
                candidates = localBundle.candidates,
                orderConstraints = constraints,
            )
        }
    }

    private fun MeituanRoute.displayName(): String = when (this) {
        MeituanRoute.DELIVERY -> "外送"
        MeituanRoute.PICKUP -> "自取"
        MeituanRoute.VOUCHER -> "美团券"
    }

    private fun readMerchantDistance(): String? {
        val texts = TeaAccessibilityService.findAllNodes { true }
            .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
        return MeituanSelectors.extractMerchantDistance(texts)
    }

    private data class MeituanStepResult(val error: String? = null) {
        val isSuccess: Boolean get() = error == null

        companion object {
            fun success() = MeituanStepResult()
            fun failure(message: String) = MeituanStepResult(message)
        }
    }

    private suspend fun openCheckout(): Boolean {
        // "抢购" can take Meituan straight to the order confirmation page. That page has
        // "提交订单" instead of "去结算"; treat it as the terminal price page and never tap it.
        if (waitForNode(1500) {
            it.text?.toString() == "提交订单" ||
                it.contentDescription?.toString()?.contains("提交订单") == true
        } != null) {
            return true
        }

        val checkoutEntry = waitForNode(4000) {
            it.text?.toString() == "去结算" || it.contentDescription?.toString()?.contains("去结算") == true
        } ?: return false
        if (!TeaAccessibilityService.click(checkoutEntry)) return false
        delay(1500)
        return waitForNode(4000) { node ->
            node.text?.toString()?.let {
                it == "提交订单" ||
                    it.contains("配送费") ||
                    it.contains("打包费") ||
                    it.contains("合计")
            } == true
        } != null
    }

    private suspend fun readFinalPriceViaAi(
        apiKey: String,
        merchantDistance: String?,
        meituanPrices: MeituanPriceSnapshot?,
    ): PriceResult {
        delay(500)
        val texts = TeaAccessibilityService.findAllNodes { true }
            .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        val price = deepSeek(apiKey, "parse_final_price").parseFinalPrice(texts)
            ?: return PriceResult(resultPlatform, error = "解析最终价格失败（应包含配送费/打包费）")
        return PriceResult(
            resultPlatform,
            price = price,
            merchantDistance = merchantDistance,
            meituanPrices = meituanPrices,
        )
    }

    suspend fun runSearch(target: PlatformTarget): MeituanSearchResult {
        if (target.storeKeyword.isBlank()) {
            return MeituanSearchResult("美团店铺关键词不能为空")
        }
        if (!MeituanCartController().canCompare) {
            return MeituanSearchResult("需要先同意清空店内待付款购物车后才能自动比价")
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(Platform.MEITUAN.packageName)
            ?: return MeituanSearchResult("未安装美团")
        context.startActivity(prepareMeituanLaunchIntent(launchIntent))
        delay(1500)

        if (waitForNode(5000) { it.packageName?.toString() == Platform.MEITUAN.packageName } == null) {
            return MeituanSearchResult("美团没有切到前台")
        }

        // Do not touch Meituan's home-page/global cart here. The cart is scoped to the store
        // and is cleared only after a store page has been opened, immediately before adding.
        closeCartDrawerIfOpen()

        if (route != MeituanRoute.VOUCHER) {
            // 外卖首页本身也会暴露一个 desc="外卖" 的频道图标。先判断搜索框，避免
            // 已经在外卖页时再次点击频道图标，把当前页面切走。
            val alreadyOnDeliveryHome = waitForNode(1200, predicate = MeituanSelectors::isDeliverySearchHome) != null
            if (!alreadyOnDeliveryHome) {
                val deliveryEntry = waitForNode(4000, predicate = MeituanSelectors::isDeliveryEntry)
                    ?: return MeituanSearchResult("美团首页没有找到可点击的「外卖」入口")
                val bounds = Rect().also { deliveryEntry.getBoundsInScreen(it) }
                if (!TeaAccessibilityService.tap(bounds.centerX(), bounds.centerY())) {
                    return MeituanSearchResult("点击美团「外卖」入口失败")
                }
                delay(1200)
            }
            if (waitForNode(5000, predicate = MeituanSelectors::isDeliverySearchHome) == null) {
                return MeituanSearchResult("进入美团外卖后没有找到外卖搜索框")
            }
        } else if (findSearchHome() == null) {
            return MeituanSearchResult("没有找到美团首页搜索栏")
        }

        val searchHome = TeaAccessibilityService.findNode(
            if (route != MeituanRoute.VOUCHER) {
                MeituanSelectors::isDeliverySearchHome
            } else {
                MeituanSelectors::isSearchHome
            },
        )
            ?: return MeituanSearchResult("没有找到美团首页搜索栏")
        if (!TeaAccessibilityService.click(searchHome)) {
            return MeituanSearchResult("点击美团搜索栏失败")
        }

        val input = waitForNode(
            5000,
            predicate = if (route != MeituanRoute.VOUCHER) {
                MeituanSelectors::isDeliverySearchInput
            } else {
                MeituanSelectors::isSearchInput
            },
        )
            ?: return MeituanSearchResult("找不到美团搜索输入框")
        if (!TeaAccessibilityService.setText(input, target.storeKeyword)) {
            return MeituanSearchResult("填写美团搜索关键词失败")
        }
        delay(400)

        val searchButton = waitForNode(
            3000,
            predicate = if (route != MeituanRoute.VOUCHER) {
                MeituanSelectors::isDeliverySearchButton
            } else {
                MeituanSelectors::isSearchButton
            },
        )
            ?: return MeituanSearchResult("找不到美团搜索按钮")
        if (!TeaAccessibilityService.click(searchButton)) {
            return MeituanSearchResult("点击美团搜索按钮失败")
        }

        val resultList = waitForNode(10000) {
            if (route != MeituanRoute.VOUCHER) {
                MeituanSelectors.isDeliverySearchResult(
                    packageName = it.packageName?.toString(),
                    resourceId = it.viewIdResourceName,
                )
            } else {
                MeituanSelectors.isSearchResultList(
                    packageName = it.packageName?.toString(),
                    resourceId = it.viewIdResourceName,
                )
            }
        } ?: return MeituanSearchResult("美团没有进入搜索结果页")

        if (resultList.packageName?.toString() != Platform.MEITUAN.packageName) {
            return MeituanSearchResult("美团结果页校验失败")
        }
        delay(400)
        TeaAccessibilityService.captureCurrentWindow(Platform.MEITUAN.packageName)
            ?: return MeituanSearchResult("已进入美团结果页，但没有抓到节点树")
        return MeituanSearchResult()
    }

    private suspend fun closeCartDrawerIfOpen() {
        if (TeaAccessibilityService.findNode { node ->
            MeituanSelectors.isCartDrawerMarker(node.text?.toString(), node.contentDescription?.toString()) ||
                MeituanSelectors.isEmptyCartMarker(node.text?.toString(), node.contentDescription?.toString()) ||
                MeituanSelectors.isCartClearAction(node.text?.toString(), node.contentDescription?.toString())
        } == null) return
        TeaAccessibilityService.pressBack()
        delay(500)
    }

    private suspend fun openFirstStore(storeKeyword: String): MeituanSearchResult {
            if (route != MeituanRoute.VOUCHER) {
            return openFirstDeliveryStore(storeKeyword)
        }

        val card = waitForNode(6000) { isFirstStoreCard(it, storeKeyword) }
            ?: return MeituanSearchResult("美团结果页没有找到店铺卡片")
        val bounds = Rect().also { card.getBoundsInScreen(it) }
        val x = bounds.left + bounds.width() / 2
        val y = bounds.top + (bounds.height() * if (route != MeituanRoute.VOUCHER) 0.12 else 0.08).toInt()
        if (!TeaAccessibilityService.tap(x, y)) {
            return MeituanSearchResult("点击美团店铺卡片失败")
        }

        if (!waitForStorePage(storeKeyword)) {
            return MeituanSearchResult("点击店铺卡片后没有进入店铺页")
        }
        TeaAccessibilityService.captureCurrentWindow(Platform.MEITUAN.packageName)
            ?: return MeituanSearchResult("已进入美团店铺页，但没有抓到节点树")
        return MeituanSearchResult()
    }

    private suspend fun openFirstDeliveryStore(storeKeyword: String): MeituanSearchResult {
        val title = waitForNode(6000) { node ->
            if (node.className?.toString() != "android.widget.TextView") return@waitForNode false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            bounds.top >= 900 && bounds.width() >= 250 &&
                node.text?.toString()?.contains(storeKeyword) == true
        } ?: return MeituanSearchResult("美团外卖结果页没有找到店铺名称")

        return openDeliveryStoreTitle(title, storeKeyword)
    }

    private suspend fun openDeliveryStoreByName(
        storeName: String,
        storeKeyword: String,
    ): MeituanSearchResult {
        val scrollable = TeaAccessibilityService.findAllNodes { node ->
            node.isScrollable && Rect().also { node.getBoundsInScreen(it) }.width() >= 700
        }.firstOrNull()
        repeat(4) { page ->
            val title = waitForNode(900) { node ->
                if (node.className?.toString() != "android.widget.TextView") return@waitForNode false
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = node.text?.toString()?.trim().orEmpty()
                bounds.top >= 650 && bounds.top < 2300 && bounds.width() >= 220 &&
                    (text == storeName || text.contains(storeName))
            }
            if (title != null) return openDeliveryStoreTitle(title, storeKeyword)
            if (page < 3 && scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(600)
            }
        }
        return MeituanSearchResult("结果页没有找到店铺「$storeName」")
    }

    private suspend fun openDeliveryStoreTitle(
        title: AccessibilityNodeInfo,
        storeKeyword: String,
    ): MeituanSearchResult {

        val titleBounds = Rect().also { title.getBoundsInScreen(it) }
        val tapPoints = listOf(
            titleBounds.centerX() to titleBounds.centerY(),
            (titleBounds.left + titleBounds.width() / 4) to titleBounds.centerY(),
            (titleBounds.left / 2) to (titleBounds.top + titleBounds.height() * 2),
        )
        for ((x, y) in tapPoints) {
            if (!TeaAccessibilityService.tap(x, y)) continue
            delay(1500)
            if (waitForStorePage(storeKeyword)) {
                TeaAccessibilityService.captureCurrentWindow(Platform.MEITUAN.packageName)
                return MeituanSearchResult()
            }
        }
        return MeituanSearchResult("点击美团外卖店铺「$storeKeyword」后没有进入店铺")
    }

    private fun isFirstStoreCard(node: AccessibilityNodeInfo, storeKeyword: String): Boolean {
        if (route != MeituanRoute.VOUCHER) {
            if (node.className?.toString() != "android.widget.FrameLayout") return false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() < 800 || bounds.height() < 500) return false
            return hasDescendant(node) { child ->
                child.text?.toString()?.contains(storeKeyword) == true ||
                    child.contentDescription?.toString()?.contains(storeKeyword) == true
            }
        }

        if (node.className?.toString() != "android.widget.Button" || !node.isClickable) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() < 800 || bounds.height() < 450) return false
        return hasDescendant(node) {
            it.contentDescription?.toString()?.contains(storeKeyword) == true
        }
    }

    private fun hasDescendant(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (predicate(child) || hasDescendant(child, predicate)) return true
        }
        return false
    }

    private suspend fun waitForStorePage(storeKeyword: String): Boolean {
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val texts = TeaAccessibilityService.findAllNodes { true }
                .flatMap { node ->
                    listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                }
            val isStorePage = if (route != MeituanRoute.VOUCHER) {
                MeituanSelectors.isDeliveryStorePage(texts, storeKeyword)
            } else {
                MeituanSelectors.isStorePage(texts, storeKeyword)
            }
            if (isStorePage) return true
            delay(300)
        }
        return false
    }

    private suspend fun findSearchHome() =
        repeatUntilFound(attempts = 3) {
            waitForNode(2500, predicate = MeituanSelectors::isSearchHome)
        }

    private suspend fun <T> repeatUntilFound(attempts: Int, find: suspend () -> T?): T? {
        repeat(attempts) { attempt ->
            find()?.let { return it }
            if (attempt < attempts - 1) {
                TeaAccessibilityService.pressBack()
                delay(700)
            }
        }
        return null
    }

    companion object {
        private const val MAX_RECOVERY_STEPS = QueryBudget.MAX_RECOVERY_STEPS
    }
}
