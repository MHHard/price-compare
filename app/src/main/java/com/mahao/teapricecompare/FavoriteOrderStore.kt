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

    fun add(name: String, targets: Map<Platform, PlatformTarget>): FavoriteOrder {
        val order = FavoriteOrder(id = UUID.randomUUID().toString(), name = name, targets = targets)
        save(list() + order)
        return order
    }

    fun update(order: FavoriteOrder) {
        save(list().map { if (it.id == order.id) order else it })
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
        return FavoriteOrder(id = getString("id"), name = getString("name"), targets = targets)
    }
}
