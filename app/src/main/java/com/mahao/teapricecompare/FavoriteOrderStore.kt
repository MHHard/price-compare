package com.mahao.teapricecompare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** JSON-file backed CRUD store for [FavoriteOrder]s, one file under the app's filesDir. */
class FavoriteOrderStore(context: Context) {

    private val file = File(context.filesDir, "favorite_orders.json")

    fun list(): List<FavoriteOrder> {
        if (!file.exists()) return emptyList()
        val root = JSONArray(file.readText())
        return (0 until root.length()).map { i -> root.getJSONObject(i).toFavoriteOrder() }
    }

    fun get(id: String): FavoriteOrder? = list().firstOrNull { it.id == id }

    fun add(name: String, targets: Map<Platform, PlatformTarget>): FavoriteOrder {
        val order = FavoriteOrder(id = UUID.randomUUID().toString(), name = name, targets = targets)
        save(list() + order)
        return order
    }

    fun update(order: FavoriteOrder) {
        save(list().map { if (it.id == order.id) order else it })
    }

    fun saveComparison(orderId: String, snapshot: ComparisonSnapshot): Boolean {
        val orders = list()
        if (orders.none { it.id == orderId }) return false
        save(orders.map { order ->
            if (order.id == orderId) order.copy(lastComparison = snapshot) else order
        })
        return true
    }

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
    }

    private fun save(orders: List<FavoriteOrder>) {
        val root = JSONArray()
        orders.forEach { root.put(it.toJson()) }
        file.writeText(root.toString())
    }

    private fun FavoriteOrder.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        val targetsJson = JSONObject()
        targets.forEach { (platform, target) ->
            targetsJson.put(
                platform.name,
                JSONObject().apply {
                    put("storeKeyword", target.storeKeyword)
                    put("productKeyword", target.productKeyword)
                },
            )
        }
        put("targets", targetsJson)
        lastComparison?.let { put("lastComparison", ComparisonSnapshotCodec.toJson(it)) }
    }

    private fun JSONObject.toFavoriteOrder(): FavoriteOrder {
        val targetsJson = getJSONObject("targets")
        val targets = mutableMapOf<Platform, PlatformTarget>()
        targetsJson.keys().forEach { key ->
            val platform = runCatching { Platform.valueOf(key) }.getOrNull() ?: return@forEach
            val targetJson = targetsJson.getJSONObject(key)
            targets[platform] = PlatformTarget(
                storeKeyword = targetJson.getString("storeKeyword"),
                productKeyword = targetJson.getString("productKeyword"),
            )
        }
        val snapshot = opt("lastComparison")
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { value -> runCatching { ComparisonSnapshotCodec.fromJson(value as JSONObject) }.getOrNull() }
        return FavoriteOrder(
            id = getString("id"),
            name = getString("name"),
            targets = targets,
            lastComparison = snapshot,
        )
    }
}

internal object ComparisonSnapshotCodec {

    fun toJson(snapshot: ComparisonSnapshot): JSONObject = JSONObject().apply {
        put("queryId", snapshot.queryId)
        put("target", JSONObject().apply {
            put("storeKeyword", snapshot.target.storeKeyword)
            put("productKeyword", snapshot.target.productKeyword)
        })
        put("stores", JSONArray().apply { snapshot.stores.forEach { put(it.toJson()) } })
        put("status", snapshot.status.name)
        put("createdAt", snapshot.createdAt)
        put("usageSummary", snapshot.usageSummary.toJson())
        putNullable("cartNotice", snapshot.cartNotice)
        putNullable("failureReason", snapshot.failureReason)
    }

    fun fromJson(json: JSONObject): ComparisonSnapshot {
        val targetJson = json.getJSONObject("target")
        val storesJson = json.optJSONArray("stores") ?: JSONArray()
        val status = runCatching {
            ComparisonStatus.valueOf(json.getString("status"))
        }.getOrElse { ComparisonStatus.FAILED }
        val stores = (0 until storesJson.length()).mapNotNull { index ->
            storesJson.optJSONObject(index)?.let { runCatching { it.toStoreComparison() }.getOrNull() }
        }
        return ComparisonSnapshot(
            queryId = json.getString("queryId"),
            target = PlatformTarget(
                storeKeyword = targetJson.getString("storeKeyword"),
                productKeyword = targetJson.getString("productKeyword"),
            ),
            stores = stores,
            status = status,
            createdAt = json.optLong("createdAt", 0L).coerceAtLeast(0L),
            usageSummary = json.optJSONObject("usageSummary")?.toUsageSummary() ?: UsageSummary(),
            cartNotice = json.optionalText("cartNotice"),
            failureReason = json.optionalText("failureReason"),
        )
    }

    private fun MeituanStoreComparison.toJson(): JSONObject = JSONObject().apply {
        put("storeName", storeName)
        putNullable("merchantDistance", merchantDistance)
        put("voucher", voucher.toJson())
        put("delivery", delivery.toJson())
        put("pickup", pickup.toJson())
    }

    private fun JSONObject.toStoreComparison(): MeituanStoreComparison = MeituanStoreComparison(
        storeName = getString("storeName"),
        merchantDistance = optionalText("merchantDistance"),
        voucher = getJSONObject("voucher").toModePrice(),
        delivery = getJSONObject("delivery").toModePrice(),
        pickup = getJSONObject("pickup").toModePrice(),
    )

    private fun MeituanModePrice.toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        if (price == null) put("price", JSONObject.NULL) else put("price", price)
        putNullable("error", error)
        put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
        orderConstraints?.let { put("orderConstraints", it.toJson()) }
    }

    private fun JSONObject.toModePrice(): MeituanModePrice {
        val candidatesJson = optJSONArray("candidates") ?: JSONArray()
        val candidates = (0 until candidatesJson.length()).mapNotNull { index ->
            candidatesJson.optJSONObject(index)?.let { runCatching { it.toProductCandidate() }.getOrNull() }
        }
        return MeituanModePrice(
            mode = MeituanRoute.valueOf(getString("mode")),
            price = optionalNonNegativeDouble("price"),
            error = optionalText("error"),
            candidates = candidates,
            orderConstraints = optJSONObject("orderConstraints")?.toOrderConstraints(),
        )
    }

    private fun ProductCandidate.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        if (price == null) put("price", JSONObject.NULL) else put("price", price)
        putNullable("specSummary", specSummary)
        put("isTarget", isTarget)
        put("isAddable", isAddable)
    }

    private fun JSONObject.toProductCandidate(): ProductCandidate = ProductCandidate(
        name = getString("name"),
        price = optionalNonNegativeDouble("price"),
        specSummary = optionalText("specSummary"),
        isTarget = optBoolean("isTarget", false),
        isAddable = optBoolean("isAddable", false),
    )

    private fun OrderConstraints.toJson(): JSONObject = JSONObject().apply {
        put("subtotal", subtotal)
        put("minimumOrder", minimumOrder)
        put("gap", gap)
        put("deliveryFee", deliveryFee)
        put("packingFee", packingFee)
        put("isOrderable", isOrderable)
    }

    private fun JSONObject.toOrderConstraints(): OrderConstraints = OrderConstraints(
        subtotal = nonNegativeDouble("subtotal"),
        minimumOrder = nonNegativeDouble("minimumOrder"),
        gap = nonNegativeDouble("gap"),
        deliveryFee = nonNegativeDouble("deliveryFee"),
        packingFee = nonNegativeDouble("packingFee"),
        isOrderable = optBoolean("isOrderable", false),
    )

    private fun UsageSummary.toJson(): JSONObject = JSONObject().apply {
        put("agentCalls", agentCalls)
        put("totalTokens", totalTokens)
        put("costUsd", costUsd)
        put("costCny", costCny)
        put("recoverySteps", recoverySteps)
    }

    private fun JSONObject.toUsageSummary(): UsageSummary = UsageSummary(
        agentCalls = optInt("agentCalls", 0).coerceAtLeast(0),
        totalTokens = optInt("totalTokens", 0).coerceAtLeast(0),
        costUsd = nonNegativeDouble("costUsd"),
        costCny = nonNegativeDouble("costCny"),
        recoverySteps = optInt("recoverySteps", 0).coerceAtLeast(0),
    )

    private fun JSONObject.optionalText(key: String): String? {
        val value = opt(key)
        return if (value == null || value == JSONObject.NULL) null else value as? String
    }

    private fun JSONObject.optionalNonNegativeDouble(key: String): Double? {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return null
        val number = value as? Number ?: return null
        return number.toDouble().takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun JSONObject.nonNegativeDouble(key: String): Double =
        optionalNonNegativeDouble(key) ?: 0.0

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }
}
