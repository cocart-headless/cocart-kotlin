package com.cocart.sdk.http

import org.json.JSONObject
import java.util.TreeMap

class CoCartResponse(
    private val data: Map<String, Any?>,
    headers: Map<String, String>,
    val statusCode: Int,
    /** The body when the route answered with a list (categories, tags, attributes, reviews, variations). */
    private val list: List<Any?>? = null
) {
    // Header names are case-insensitive (HTTP/2 sends them lowercase).
    private val headers: Map<String, String> =
        TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }

    /** Dot-notation access: `get("totals.total")`, `get("items.0.name")`; a list body starts at an index: `get("0.name")`. */
    fun get(path: String): Any? {
        val parts = path.split(".")
        var current: Any? = list ?: data
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

    /** A response header, ignoring the name's case. */
    fun getHeader(name: String): String? = headers[name]

    fun getItems(): List<Any?> = (data["items"] as? List<*>) ?: emptyList()

    fun getTotals(): Map<String, Any?> = (data["totals"] as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    fun getItemCount(): Int = (data["item_count"] as? Number)?.toInt() ?: 0

    fun getCartKey(): String? =
        headers["Cart-Key"]
            ?: headers["CoCart-API-Cart-Key"]
            ?: data["cart_key"] as? String

    fun getCartHash(): String? = data["cart_hash"] as? String

    /**
     * The notices, keyed by type (`success`, `error`, `notice`, `info`), each a list of
     * messages, e.g. `{"success": ["“Beanie” has been added to your cart."]}`. Empty when
     * there are none (CoCart Starter 5 sends `{}`, CoCart 4.x `[]`).
     */
    fun getNotices(): Map<String, List<String>> {
        val notices = data["notices"] as? Map<*, *> ?: return emptyMap()
        return notices.entries.associate { (type, messages) ->
            type.toString() to ((messages as? List<*>)?.map { it.toString() } ?: emptyList())
        }
    }

    /**
     * The shipping packages, as a list in the order CoCart sent them. Each package has its
     * `rates` keyed by rate ID, and `chosen_method`. CoCart sends the packages keyed by
     * package ID under `shipping.packages`; see [getShipping] for the whole field.
     */
    fun getShippingMethods(): List<Map<String, Any?>> {
        val packages = when (val shipping = data["shipping"]) {
            is List<*> -> shipping
            is Map<*, *> -> shipping["packages"]
            else -> null
        }
        return valuesOf(packages)
    }

    /**
     * The cart's whole `shipping` field: `total_packages`, `show_package_details`,
     * `has_calculated_shipping` and `packages` (keyed by package ID). `null` when the
     * response has none.
     */
    fun getShipping(): Map<String, Any?>? = stringKeyed(data["shipping"] as? Map<*, *>)

    /**
     * The cart's fees, as a list. The cart keys fees by fee ID (`{}` when there are none;
     * `[]` from CoCart 4.x).
     */
    fun getFees(): List<Map<String, Any?>> = valuesOf(data["fees"])

    /**
     * The cart's `extensions`: data plugins add to the cart, such as subscription
     * `recurring_totals` (see docs/extension-data.md). Empty when there is none.
     */
    fun getExtensions(): Map<String, Any?> = stringKeyed(data["extensions"] as? Map<*, *>) ?: emptyMap()

    fun getCurrency(): Map<String, Any?>? = (data["currency"] as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value }

    fun getCacheStatus(): String? = headers["CoCart-Cache"]

    /**
     * The `RateLimit-*` headers CoCart Plus sends when rate limiting is on. A value is
     * `null` when its header wasn't sent.
     */
    fun getRateLimit(): RateLimitInfo {
        fun number(name: String): Long? = headers[name]?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }?.toLong()
        return RateLimitInfo(
            limit = number("RateLimit-Limit"),
            remaining = number("RateLimit-Remaining"),
            reset = number("RateLimit-Reset"),
            retryAfter = number("RateLimit-Retry-After")
        )
    }

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

    /** The body as an object; empty when the route answered with a list (see [toList]). */
    fun toMap(): Map<String, Any?> = data.toMap()

    /** The body as a list, for routes that answer with one (categories, tags, attributes, reviews, variations, payment methods, downloads); empty otherwise. */
    fun toList(): List<Any?> = list ?: emptyList()

    fun toJson(): String = list?.let { org.json.JSONArray(it).toString() } ?: JSONObject(data).toString()

    private fun stringKeyed(map: Map<*, *>?): Map<String, Any?>? =
        map?.entries?.associate { it.key.toString() to it.value }

    /** The values of an object keyed by ID, or the elements of a list, as maps. */
    private fun valuesOf(value: Any?): List<Map<String, Any?>> {
        val values = when (value) {
            is Map<*, *> -> value.values
            is List<*> -> value
            else -> return emptyList()
        }
        return values.mapNotNull { stringKeyed(it as? Map<*, *>) }
    }
}

/**
 * The `RateLimit-*` headers: [limit] requests per window, [remaining] in this window,
 * [reset] (the Unix time the window resets) and [retryAfter] (seconds to wait after a 429).
 */
data class RateLimitInfo(
    val limit: Long?,
    val remaining: Long?,
    val reset: Long?,
    val retryAfter: Long?
)
