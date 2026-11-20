package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.BatchRequestItem
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.validation.validateProductId
import com.cocart.sdk.validation.validateQuantity
import java.net.URLEncoder

class CartResource(
    private val http: HttpClient,
    private val auth: AuthManager,
    private val options: CoCartOptions
) {
    suspend fun create(): CoCartResponse = http.post("cart")

    suspend fun get(params: Map<String, String>? = null): CoCartResponse =
        http.get("cart", params)

    suspend fun getFiltered(fields: List<String>): CoCartResponse {
        val fieldParam = if (options.mainPlugin == MainPlugin.LEGACY) "fields" else "_fields"
        return http.get("cart", mapOf(fieldParam to fields.joinToString(",")))
    }

    /**
     * Add an item to the cart.
     *
     * [options] holds the request's other fields: `variation` (a variable product's
     * attributes), `item_data` (custom data), `email`, `price` and `return_item`, and the
     * choices for CoCart Plus's supported plugins: `convert_to_sub` (a subscription plan's
     * `value`, `"0"` for one-time), `recipient_email` (a gift's recipient) and
     * `item_data` `{"warranty": key}` (a warranty option). Other plugins' fields pass
     * through as given.
     */
    suspend fun addItem(
        id: Int,
        qty: Double = 1.0,
        options: Map<String, Any?>? = null
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        val body = mutableMapOf<String, Any?>("id" to id.toString(), "quantity" to quantityString(qty))
        options?.forEach { (k, v) -> body[k] = v }
        return http.post("cart/add-item", body)
    }

    /** Add an item to the cart by SKU. Both resolve a non-numeric id before falling back to a 404. */
    suspend fun addItem(
        id: String,
        qty: Double = 1.0,
        options: Map<String, Any?>? = null
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        val body = mutableMapOf<String, Any?>("id" to id.toString(), "quantity" to quantityString(qty))
        options?.forEach { (k, v) -> body[k] = v }
        return http.post("cart/add-item", body)
    }

    suspend fun add(id: Int, qty: Double = 1.0): CoCartResponse = addItem(id, qty)

    suspend fun add(id: String, qty: Double = 1.0): CoCartResponse = addItem(id, qty)

    suspend fun addVariation(
        id: Int,
        qty: Double = 1.0,
        attributes: Map<String, String>
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        return http.post("cart/add-item", mapOf(
            "id" to id.toString(),
            "quantity" to quantityString(qty),
            "variation" to attributes
        ))
    }

    /** Add a variable product to the cart by SKU. See [addItem] for why a SKU is accepted here. */
    suspend fun addVariation(
        id: String,
        qty: Double = 1.0,
        attributes: Map<String, String>
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        return http.post("cart/add-item", mapOf(
            "id" to id.toString(),
            "quantity" to quantityString(qty),
            "variation" to attributes
        ))
    }

    /**
     * Add multiple children of a WooCommerce Grouped Product to the cart in a
     * single request, via the dedicated `cart/add-items` endpoint.
     *
     * This is NOT a generic "add several unrelated products" call — the server
     * requires a single grouped product ID plus a map of that group's child
     * product IDs to quantities. For adding unrelated products in one request,
     * use [com.cocart.sdk.CoCart.batch] instead.
     *
     * @param groupedProductId The parent grouped product's ID.
     * @param items Map of child product ID => quantity.
     */
    suspend fun addItems(groupedProductId: Int, items: Map<String, Int>): CoCartResponse {
        validateProductId(groupedProductId)
        if (items.isEmpty()) throw ValidationException("addItems() requires at least one item.")
        return http.post(
            "cart/add-items",
            mapOf("id" to groupedProductId.toString(), "quantity" to items.mapValues { it.value.toString() })
        )
    }

    /**
     * Add multiple children of a Grouped Product to the cart by the parent's
     * SKU. See [addItem] for why a SKU is accepted for [groupedProductId] —
     * note the child [items] map keys must still be numeric IDs, they are
     * not resolved as SKUs server-side.
     */
    suspend fun addItems(groupedProductId: String, items: Map<String, Int>): CoCartResponse {
        validateProductId(groupedProductId)
        if (items.isEmpty()) throw ValidationException("addItems() requires at least one item.")
        return http.post(
            "cart/add-items",
            mapOf("id" to groupedProductId.toString(), "quantity" to items.mapValues { it.value.toString() })
        )
    }

    /** Overload accepting a list of `{id, quantity}` entries instead of a map. */
    suspend fun addItems(groupedProductId: Int, items: List<Map<String, Any?>>): CoCartResponse {
        val quantity = items.associate { entry ->
            val childId = entry["id"]?.toString()
                ?: throw ValidationException("addItems() entries require an id.")
            val qty = (entry["quantity"] as? Number)?.toInt()
                ?: throw ValidationException("addItems() entries require a quantity.")
            childId to qty
        }
        return addItems(groupedProductId, quantity)
    }

    /** Overload accepting a list of `{id, quantity}` entries instead of a map, by the parent's SKU. */
    suspend fun addItems(groupedProductId: String, items: List<Map<String, Any?>>): CoCartResponse {
        val quantity = items.associate { entry ->
            val childId = entry["id"]?.toString()
                ?: throw ValidationException("addItems() entries require an id.")
            val qty = (entry["quantity"] as? Number)?.toInt()
                ?: throw ValidationException("addItems() entries require a quantity.")
            childId to qty
        }
        return addItems(groupedProductId, quantity)
    }

    suspend fun updateItem(
        itemKey: String,
        qty: Double,
        options: Map<String, Any?>? = null
    ): CoCartResponse {
        validateQuantity(qty)
        val body = mutableMapOf<String, Any?>("quantity" to quantityString(qty))
        options?.forEach { (k, v) -> body[k] = v }
        return http.post("cart/item/$itemKey", body)
    }

    /**
     * Update multiple items' quantities, one request per item, sequentially.
     * Returns the response from the last update (reflects the fully-updated cart).
     *
     * There is no real bulk-update endpoint, so this loops [updateItem] per
     * entry. For a true single round trip, use [batchUpdateItems] instead
     * (requires CoCart Plus).
     */
    suspend fun updateItems(items: Map<String, Double>): CoCartResponse =
        updateItemsSequentially(items.entries.map { (key, qty) -> key to qty })

    /** Overload accepting a list of `{item_key, quantity}` entries instead of a map. */
    suspend fun updateItems(items: List<Map<String, Any?>>): CoCartResponse =
        updateItemsSequentially(items.map(::toItemKeyQuantityPair))

    /**
     * Update multiple items' quantities in a single request via [com.cocart.sdk.CoCart.batch]
     * (requires CoCart Plus). Unlike [updateItems], this is a true single round trip
     * instead of one sequential request per item.
     */
    suspend fun batchUpdateItems(items: Map<String, Double>): CoCartResponse =
        batchUpdateItemsInternal(items.entries.map { (key, qty) -> key to qty })

    /** Overload accepting a list of `{item_key, quantity}` entries instead of a map. */
    suspend fun batchUpdateItems(items: List<Map<String, Any?>>): CoCartResponse =
        batchUpdateItemsInternal(items.map(::toItemKeyQuantityPair))

    suspend fun removeItem(itemKey: String): CoCartResponse =
        http.delete("cart/item/$itemKey")

    /**
     * Remove multiple items from the cart, one request per item, sequentially.
     * Returns the response from the last removal (reflects the fully-updated cart).
     *
     * There is no real bulk-remove endpoint, so this loops [removeItem] per key.
     * For a true single round trip, use [batchRemoveItems] instead (requires CoCart Plus).
     */
    suspend fun removeItems(itemKeys: List<String>): CoCartResponse {
        if (itemKeys.isEmpty()) throw ValidationException("removeItems() requires at least one item key.")
        var response: CoCartResponse? = null
        for (itemKey in itemKeys) {
            response = removeItem(itemKey)
        }
        return response!!
    }

    /**
     * Remove multiple items in a single request via [com.cocart.sdk.CoCart.batch]
     * (requires CoCart Plus). Unlike [removeItems], this is a true single round
     * trip instead of one sequential request per item.
     */
    suspend fun batchRemoveItems(itemKeys: List<String>): CoCartResponse {
        if (itemKeys.isEmpty()) throw ValidationException("batchRemoveItems() requires at least one item key.")
        val requests = itemKeys.map { itemKey ->
            BatchRequestItem(method = "DELETE", path = batchPath("cart/item/$itemKey"))
        }
        return http.batch(requests)
    }

    private fun toItemKeyQuantityPair(entry: Map<String, Any?>): Pair<String, Double> {
        val itemKey = entry["item_key"] as? String
            ?: throw ValidationException("updateItems() entries require an item_key.")
        val qty = (entry["quantity"] as? Number)?.toDouble()
            ?: throw ValidationException("updateItems() entries require a quantity.")
        return itemKey to qty
    }

    private suspend fun updateItemsSequentially(entries: List<Pair<String, Double>>): CoCartResponse {
        if (entries.isEmpty()) throw ValidationException("updateItems() requires at least one item.")
        var response: CoCartResponse? = null
        for ((itemKey, qty) in entries) {
            response = updateItem(itemKey, qty)
        }
        return response!!
    }

    private suspend fun batchUpdateItemsInternal(entries: List<Pair<String, Double>>): CoCartResponse {
        if (entries.isEmpty()) throw ValidationException("batchUpdateItems() requires at least one item.")
        val requests = entries.map { (itemKey, qty) ->
            BatchRequestItem(
                method = "POST",
                path = batchPath("cart/item/$itemKey"),
                body = mapOf("quantity" to quantityString(qty))
            )
        }
        return http.batch(requests)
    }

    private fun batchPath(path: String): String = "/${options.namespace}/v2/${path.trimStart('/')}"

    /** CoCart takes quantities as strings: "2", or "1.5" for a product sold by weight. */
    private fun quantityString(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toLong().toString() else qty.toString()

    /** The cart with only the given field, e.g. `fees`, using the configured plugin's filter parameter. */
    private suspend fun getField(field: String): CoCartResponse = getFiltered(listOf(field))

    /** Restore a removed item to the cart (`PUT cart/item/{item_key}`). */
    suspend fun restoreItem(itemKey: String): CoCartResponse =
        http.put("cart/item/$itemKey")

    /** Items removed from the cart that can be restored. */
    suspend fun getRemovedItems(): CoCartResponse = getField("removed_items")

    suspend fun clear(): CoCartResponse = http.post("cart/clear")

    suspend fun empty(): CoCartResponse = clear()

    suspend fun calculate(): CoCartResponse = http.post("cart/calculate")

    suspend fun update(data: Map<String, Any?>): CoCartResponse = http.post("cart/update", data)

    /**
     * Switch a cart item to another subscription plan, or to one-time with `"0"`
     * (CoCart Plus).
     *
     * The options are in the item's `extensions.subscription_schemes`; pass one's
     * `value`. Posts the `update-item-schema` callback on `POST cart/update`.
     *
     * @param itemKey The cart item's key.
     * @param value   The option's `value`.
     */
    suspend fun changePlan(itemKey: String, value: String): CoCartResponse {
        if (itemKey.isEmpty()) throw ValidationException("changePlan() requires an item key.")
        return update(mapOf(
            "namespace" to "update-item-schema",
            "item_key" to itemKey,
            "convert_to_sub" to value
        ))
    }

    /** A lighter cart response for a mini cart (CoCart Plus). */
    suspend fun getMini(params: Map<String, String>? = null): CoCartResponse =
        http.get("cart/mini", params)

    /**
     * Remove every item but keep the cart's coupons, fees and customer details
     * (CoCart Plus, `DELETE cart/items`). [clear] empties the whole cart.
     */
    suspend fun removeAllItems(): CoCartResponse = http.delete("cart/items")

    suspend fun getTotals(formatted: Boolean = false): CoCartResponse =
        http.get("cart/totals", if (formatted) mapOf("html" to "true") else null)

    suspend fun getItemCount(): CoCartResponse = http.get("cart/items/count")

    suspend fun getItems(params: Map<String, String>? = null): CoCartResponse =
        http.get("cart/items", params)

    suspend fun getItem(itemKey: String): CoCartResponse = http.get("cart/item/$itemKey")

    // CoCart Plus methods

    /**
     * Apply a coupon (CoCart Plus). Pass the customer's [email] for coupons restricted
     * to certain emails when the cart doesn't know it yet.
     */
    suspend fun applyCoupon(code: String, email: String? = null): CoCartResponse {
        val body = mutableMapOf<String, Any?>("code" to code)
        email?.takeIf { it.isNotEmpty() }?.let { body["email"] = it }
        return http.post("cart/apply-coupon", body)
    }

    /**
     * Remove a coupon (CoCart Plus).
     *
     * The code goes in the URL path, so it's encoded: codes can contain spaces, `%`,
     * `#`, `?` and other characters. A code containing `/` can't be removed by code
     * (CoCart Plus matches any path segment without `/`); [removeCoupons] removes all
     * of them instead.
     */
    suspend fun removeCoupon(code: String): CoCartResponse =
        http.delete("cart/coupons/${encodePathSegment(code)}")

    /** Remove every coupon from the cart (CoCart Plus). */
    suspend fun removeCoupons(): CoCartResponse = http.delete("cart/coupons")

    /** The coupons applied to the cart. */
    suspend fun getCoupons(): CoCartResponse = getField("coupons")

    /**
     * Check the applied coupons are still valid (CoCart Plus). Pass the customer's
     * [email] to check coupons restricted to certain emails.
     */
    suspend fun checkCoupons(email: String? = null): CoCartResponse =
        http.get("cart/coupons/validate", email?.takeIf { it.isNotEmpty() }?.let { mapOf("email" to it) })

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Update customer billing (and optionally shipping) address on the cart.
     *
     * Posts to `cart/update` — billing fields are sent unprefixed
     * (`first_name`, `address_1`, ...) and shipping fields are sent
     * `s_`-prefixed (`s_first_name`, `s_address_1`, ...), which the server
     * always validates as required for any address field the destination
     * country marks required, independent of whether
     * `ship_to_different_address` is set. If [shipping] is omitted or empty,
     * billing is mirrored into the `s_` fields so that check passes and the
     * shipping address matches billing, same as leaving "ship to a different
     * address" unchecked at a normal WooCommerce checkout.
     *
     * @param billing  Billing address fields (unprefixed, e.g. `first_name`, `address_1`, `city`, `postcode`, `country`, `email`, `phone`)
     * @param shipping Shipping address fields, if different from billing. Omit to mirror billing.
     */
    suspend fun updateCustomer(
        billing: Map<String, String> = emptyMap(),
        shipping: Map<String, String>? = null
    ): CoCartResponse {
        val hasDistinctShipping = !shipping.isNullOrEmpty()
        val shipTo = if (hasDistinctShipping) shipping!! else billing

        val body = mutableMapOf<String, Any?>("namespace" to "update-customer")
        billing.forEach { (key, value) -> body[key] = value }
        shipTo.forEach { (key, value) -> body["s_$key"] = value }
        if (hasDistinctShipping) {
            body["ship_to_different_address"] = true
        }

        return http.post("cart/update", body)
    }

    /** The customer's details on the cart. */
    suspend fun getCustomer(): CoCartResponse = getField("customer")

    /**
     * The cart's shipping packages and their rates. Read them with
     * [CoCartResponse.getShippingMethods] (a list of packages) or
     * [CoCartResponse.getShipping] (the whole field).
     */
    suspend fun getShippingMethods(): CoCartResponse = getField("shipping")

    /**
     * Select a shipping rate for a package (CoCart Plus).
     *
     * Posts `rate_id` (and optional `package_id`) to `POST cart/set-shipping-method`.
     * Omit [packageId] to apply the rate to every package.
     *
     * @param rateId    The chosen rate's key, e.g. `flat_rate:2` (see a shipping package's `rates` map).
     * @param packageId Restrict the selection to one package. Omit to apply to all packages.
     */
    suspend fun setShippingMethod(rateId: String, packageId: String? = null): CoCartResponse {
        val body = mutableMapOf<String, Any?>("rate_id" to rateId)
        packageId?.let { body["package_id"] = it }
        return http.post("cart/set-shipping-method", body)
    }

    /**
     * There is no address-taking shipping-calculation endpoint in the CoCart
     * REST API — `cart/shipping-methods/calculate` (what this method used to
     * call) does not exist. To calculate shipping, call [updateCustomer] with
     * the destination address first (the server recalculates totals as part
     * of that request); this method now just delegates to [calculate],
     * ignoring [address]. Prefer [calculate] directly.
     */
    @Deprecated(
        "There is no shipping-calculation endpoint that accepts an address. " +
            "Call updateCustomer() with the destination address, then use calculate() directly.",
        ReplaceWith("calculate()")
    )
    suspend fun calculateShipping(address: Map<String, Any?>? = null): CoCartResponse {
        return calculate()
    }

    /** The cart's fees. Read them as a list with [CoCartResponse.getFees]. */
    suspend fun getFees(): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("getFees() requires CoCart Plus")
        }
        return getField("fees")
    }

    /**
     * Add a fee to the cart (CoCart Plus).
     *
     * @param taxClass The tax class to use when the fee is taxable.
     */
    suspend fun addFee(
        name: String,
        amount: Double,
        taxable: Boolean = false,
        taxClass: String? = null
    ): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("addFee() requires CoCart Plus")
        }
        // CoCart Plus takes the amount as a string (a number is rejected as rest_invalid_param).
        val body = mutableMapOf<String, Any?>("name" to name, "amount" to amountString(amount), "taxable" to taxable)
        taxClass?.takeIf { it.isNotEmpty() }?.let { body["tax_class"] = it }
        return http.post("cart/add-fee", body)
    }

    /** Remove all fees from the cart (CoCart Plus). */
    suspend fun removeFees(): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("removeFees() requires CoCart Plus")
        }
        return http.post("cart/remove-fees")
    }

    private fun amountString(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()

    /** Cross-sell products for the items in the cart. */
    suspend fun getCrossSells(): CoCartResponse = getField("cross_sells")
}
