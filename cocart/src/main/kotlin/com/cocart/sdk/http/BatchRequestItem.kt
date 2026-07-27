package com.cocart.sdk.http

/**
 * A single queued sub-request for [HttpClient.batch] / `CoCart.batch()`.
 *
 * @param method HTTP method (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`)
 * @param path   Full versioned route, e.g. `/cocart/v2/cart/item/abc123`
 * @param body   Optional request body for the sub-request
 */
data class BatchRequestItem(
    val method: String,
    val path: String,
    val body: Map<String, Any?>? = null
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>("method" to method, "path" to path)
        body?.let { map["body"] = it }
        return map
    }
}
