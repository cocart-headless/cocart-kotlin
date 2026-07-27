package com.cocart.sdk.http

import org.json.JSONObject

class CoCartResponse(
    private val data: Map<String, Any?>,
    private val headers: Map<String, String>,
    val statusCode: Int
) {
    fun get(path: String): Any? {
        val parts = path.split(".")
        var current: Any? = data
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> part.toIntOrNull()?.let { current.getOrNull(it) }
                else -> return null
            } ?: return null
        }
        return current
    }

    fun has(path: String): Boolean = get(path) != null

    fun getItems(): List<Any?> = (data["items"] as? List<*>) ?: emptyList()

    fun getTotals(): Map<String, Any?> = (data["totals"] as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    fun getItemCount(): Int = (data["items_count"] as? Number)?.toInt() ?: 0

    fun getCartKey(): String? =
        headers["Cart-Key"]
            ?: headers["CoCart-API-Cart-Key"]
            ?: data["cart_key"] as? String

    fun getCartHash(): String? = data["cart_hash"] as? String

    fun getNotices(): List<Any?> = (data["notices"] as? List<*>) ?: emptyList()

    fun getCurrency(): Map<String, Any?>? = (data["currency"] as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value }

    fun getCacheStatus(): String? = headers["CoCart-Cache"]

    /**
     * Get the cart's tax lines, normalized to a list of `{key, name, price}` entries.
     *
     * The server returns `taxes` either as an array already, or (legacy) as an
     * object keyed by tax rate code (e.g. `{ "US-US-1": { name, price } }`) —
     * the latter is mapped to the same `{key, name, price}` shape.
     */
    fun getTaxes(): List<Map<String, Any?>> {
        return when (val raw = data["taxes"]) {
            is List<*> -> raw.filterIsInstance<Map<*, *>>()
                .map { entry -> entry.entries.associate { it.key.toString() to it.value } }
            is Map<*, *> -> raw.entries.map { (key, tax) ->
                val taxMap = tax as? Map<*, *>
                mapOf(
                    "key" to key.toString(),
                    "name" to taxMap?.get("name"),
                    "price" to taxMap?.get("price")
                )
            }
            else -> emptyList()
        }
    }

    /** Check if the cart has any tax lines. */
    fun hasTaxes(): Boolean = getTaxes().isNotEmpty()

    fun isNotModified(): Boolean = statusCode == 304

    fun toMap(): Map<String, Any?> = data.toMap()

    fun toJson(): String = JSONObject(data).toString()
}
