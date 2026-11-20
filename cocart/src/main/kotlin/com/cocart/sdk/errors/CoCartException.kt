package com.cocart.sdk.errors

open class CoCartException(
    message: String,
    val statusCode: Int? = null,
    val data: Map<String, Any?>? = null
) : Exception(message) {

    /** The API error code from the response, e.g. `cocart_item_not_in_cart`, or `null`. */
    fun errorCode(): String? = data?.get("code") as? String

    /**
     * The cart sent with the error, when CoCart sends one: `cocart_item_not_in_cart`
     * (an item key that isn't in the cart) carries the cart as it is now, so you can
     * show it without another request. `null` otherwise.
     */
    @Suppress("UNCHECKED_CAST")
    fun getCart(): Map<String, Any?>? {
        val details = data?.get("data") as? Map<*, *> ?: return null
        val cart = details["cart"] as? Map<String, Any?> ?: return null
        return cart.ifEmpty { null }
    }

    /**
     * Every field at fault, as `{path: message}` (`data.fields`), e.g.
     * `billing.postcode` to "Enter a valid postcode.". CoCart Plus reports them all at once.
     * Empty when the error has none.
     */
    fun getFields(): Map<String, String> {
        val fields = details()?.get("fields") as? Map<*, *> ?: return emptyMap()
        return fields.entries
            .mapNotNull { (path, message) -> (message as? String)?.let { path.toString() to it } }
            .toMap()
    }

    /** The first field at fault (`data.field`), e.g. `billing.postcode`, or `null`. */
    fun getField(): String? = (details()?.get("field") as? String)?.ifEmpty { null }

    /** The underlying plugin's own reason, when there is one (`data.reason`), or `null`. */
    fun getReason(): String? = (details()?.get("reason") as? String)?.ifEmpty { null }

    private fun details(): Map<*, *>? = data?.get("data") as? Map<*, *>
}
