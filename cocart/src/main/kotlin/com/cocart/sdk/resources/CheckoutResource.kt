package com.cocart.sdk.resources

import com.cocart.sdk.errors.CheckoutOutcomeUnknownException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.NetworkException
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Checkout (CoCart Plus 2.0): places orders and takes payment.
 *
 * The card never passes through this SDK: the gateway's own Android SDK (for Stripe,
 * stripe-android with your publishable key) collects it and returns a payment method ID,
 * and the order is placed with that ID.
 */
class CheckoutResource(
    private val http: HttpClient
) {
    private val route = "cocart/v2/checkout"
    private val api = "cocart/v2"

    /**
     * The checkout as it stands, like a receipt: items, coupons, fees, `totals` and `total_lines`,
     * the payment method, the addresses, each shipping package with its rates, and `actions`.
     *
     * Call it for the screen the customer reviews before paying: CoCart Plus remembers the total
     * it showed, and refuses the order if the total has changed by then
     * (`409 cocart_checkout_total_mismatch`). Don't call it just before placing the order.
     *
     * [params] takes `currency`, `formatted` (prices as formatted strings) and `thumb`
     * (item thumbnails). A boolean is sent as `"true"` or `"false"`.
     */
    suspend fun get(params: Map<String, Any?>? = null): CoCartResponse = http.getRaw(route, documentQuery(params))

    /**
     * Save addresses, the shipping method, the payment method, a coupon (`coupon_code` with
     * `coupon_action` `apply` or `remove`) or the currency, without placing the order. Returns the
     * checkout with what it did in `result`: `checkout_updated`, or `checkout_partly_updated` with
     * what wasn't applied in `result.not_applied` (`field`, `code`, `message`). Every address
     * field at fault is reported at once ([CoCartException.getFields]).
     */
    suspend fun update(data: Map<String, Any?>): CoCartResponse = http.putRaw(route, data)

    /** Create the order and attempt payment, sending `payment_data` as given (the low-level call behind [placeOrder]). */
    suspend fun process(data: Map<String, Any?>): CoCartResponse = http.postRaw(route, data)

    /**
     * Create the order and take payment.
     *
     * `payment_data` carries the gateway's tokens only, as a map of the keys the WooCommerce
     * gateway reads, e.g. `"wc-stripe-payment-method" to paymentMethod.id`. A free order needs
     * neither it nor `payment_method`. Calling again for the same cart lands on the same order.
     * A declined card is a failed result; errors before payment are thrown.
     *
     * The order is sent with a random `checkout_id` (or the one in [input]: 16 to 64 letters,
     * digits, `-` or `_`, else a [ValidationException] `checkout_invalid_checkout_id` before
     * anything is sent). When no answer comes back, or another request is still taking payment
     * for the cart (`409 cocart_payment_in_progress`), the order may have been placed: this throws
     * [CheckoutOutcomeUnknownException], and [getStatus] with its `checkoutId` tells how it ended.
     *
     * A total that changed since [get] or [update] is `409 cocart_checkout_total_mismatch`: get
     * the checkout again and show the new total.
     */
    suspend fun placeOrder(input: Map<String, Any?>): CheckoutResult {
        val checkoutId = if (input["checkout_id"] == null) newCheckoutId() else input["checkout_id"] as? String ?: ""
        if (!CHECKOUT_ID.matches(checkoutId)) {
            throw ValidationException(
                "checkout_id must be 16 to 64 letters, digits, \"-\" or \"_\".",
                null,
                mapOf("code" to "checkout_invalid_checkout_id")
            )
        }
        val body = withPaymentDataPairs(input + ("checkout_id" to checkoutId))
        return try {
            CheckoutResult.fromResponse(process(body))
        } catch (e: CoCartException) {
            if (isLostResponse(e) || isPaymentInProgress(e)) throw CheckoutOutcomeUnknownException(e, checkoutId)
            throw e
        }
    }

    /**
     * How a checkout ended, when [placeOrder] got no answer ([CheckoutOutcomeUnknownException]).
     * Ask from the same cart, or as the same customer. [CheckoutStatus.code] is:
     *
     * - `order_placed`: show the order (`orderId`, `orderKey`).
     * - `payment_required`: the order was placed but isn't paid; pay it with [payForOrder].
     * - `in_progress`: the checkout is still running; ask again in a few seconds.
     * - `not_placed`: nothing went through; place the order again.
     */
    suspend fun getStatus(checkoutId: String): CheckoutStatus {
        val data = http.getRaw("$route/status", mapOf("checkout_id" to checkoutId)).toMap()
        val result = data["result"] as? Map<*, *>
        val order = data["order"] as? Map<*, *>
        val status = order?.get("status") as? Map<*, *>
        return CheckoutStatus(
            checkoutId = data["checkout_id"] as? String ?: checkoutId,
            code = result?.get("code") as? String ?: "",
            message = result?.get("message") as? String ?: "",
            orderId = (order?.get("id") as? Number)?.toInt(),
            orderKey = order?.get("key") as? String,
            orderNumber = order?.get("number")?.toString(),
            orderStatus = status?.get("code") as? String
        )
    }

    /** A random ID for one checkout: `chk_` and 32 hex digits. */
    private fun newCheckoutId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "chk_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** No answer came back: the connection dropped, it timed out, or a proxy gave up. */
    private fun isLostResponse(e: CoCartException): Boolean =
        e is NetworkException || e.statusCode == 502 || e.statusCode == 504

    /** Another request is still taking payment for the same cart, so how this one ends isn't known yet. */
    private fun isPaymentInProgress(e: CoCartException): Boolean =
        e.statusCode == 409 && e.errorCode() == "cocart_payment_in_progress"

    /**
     * The store's form settings (no cart needed): fields, countries, shipping, account and store
     * settings, `order_received_url` for [confirmReturn], whether address search is available
     * (`address_autocomplete.enabled`, with its `providers`), and the terms (`store.terms_text`,
     * `terms_links`, `terms_url`, `privacy_url`).
     */
    suspend fun getConfig(): CoCartResponse = http.getRaw("$route/config")

    /** The payment methods for this cart: `payment_methods`, each with the customer's `saved_tokens`. */
    suspend fun getPaymentMethods(): CoCartResponse = http.getRaw("$route/payment-methods")

    /**
     * A logged-in customer's saved payment methods, for every gateway or one. Pay with one as
     * `wc-{gatewayId}-payment-token` in `payment_data`, or change a subscription's card with its
     * `id` as `token_id`. Empty for a guest.
     */
    suspend fun getSavedPaymentMethods(gatewayId: String? = null): List<SavedPaymentMethod> {
        val methods = getPaymentMethods().get("payment_methods") as? List<*>
        val cards = mutableListOf<SavedPaymentMethod>()
        for (method in methods.orEmpty()) {
            if (method !is Map<*, *>) continue
            val id = method["id"] as? String ?: continue
            if (gatewayId != null && id != gatewayId) continue
            val supportsSubscriptions = (method["supports"] as? Map<*, *>)?.get("subscriptions") == true
            for (token in (method["saved_tokens"] as? List<*>).orEmpty()) {
                if (token !is Map<*, *>) continue
                cards += SavedPaymentMethod(
                    id = (token["id"] as? Number)?.toInt() ?: token["id"]?.toString()?.toIntOrNull() ?: 0,
                    gatewayId = id,
                    label = token["label"]?.toString() ?: "",
                    type = token["type"]?.toString() ?: "",
                    expires = (token["expires"] as? String)?.takeIf { it.isNotEmpty() },
                    isDefault = token["is_default"] == true,
                    supportsSubscriptions = supportsSubscriptions
                )
            }
        }
        return cards
    }

    /**
     * Address suggestions (no cart needed; at least 3 characters). Without a server-side address
     * provider it's `404 cocart_address_search_no_provider`: check [getConfig]'s
     * `address_autocomplete.enabled` first.
     */
    suspend fun searchAddresses(
        query: String,
        country: String? = null,
        type: String? = null,
        provider: String? = null
    ): CoCartResponse = http.getRaw(
        "$api/address/search",
        listOfNotNull(
            "query" to query,
            country?.let { "country" to it },
            type?.let { "type" to it },
            provider?.let { "provider" to it }
        ).toMap()
    )

    /** A suggestion's full address (no cart needed). */
    suspend fun getAddressDetails(addressId: String, provider: String): CoCartResponse =
        http.getRaw("$api/address/details", mapOf("address_id" to addressId, "provider" to provider))

    /**
     * The order, for the thank-you screen. [orderKey] must match the order's key; a wrong key, or
     * someone else's order, is `404 cocart_order_not_found`. [params] takes `formatted` and
     * `meta_keys` (a list is comma-joined).
     */
    suspend fun getOrderReceived(orderId: Int, orderKey: String, params: Map<String, Any?>? = null): CoCartResponse =
        http.getRaw("$api/order-received/$orderId", documentQuery(params).orEmpty() + ("order_key" to orderKey))

    /**
     * Take payment for an unpaid (`pending` or `failed`) order, e.g. to retry a failed payment.
     * Read the result as for [placeOrder]: a card that needs 3D Secure gives `needsConfirmation`,
     * then call again with the same input. A declined card is a failed result, not an exception.
     * The order key is sent in the body, not the URL. An order that needs no payment or can't be
     * paid is a `409`, and so is another payment still running for it (`cocart_payment_in_progress`).
     */
    suspend fun payForOrder(orderId: Int, orderKey: String, input: Map<String, Any?>): CheckoutResult =
        CheckoutResult.fromResponse(
            http.postRaw("$api/order-received/$orderId/pay", withPaymentDataPairs(input) + ("order_key" to orderKey))
        )

    /**
     * Finish a payment after an off-site step, from the parameters the customer came back with.
     * Pass every query parameter the return URL carried, unchanged. The order key is sent in the
     * body. Safe to call more than once. See [confirmReturn] to do this straight from the URL.
     */
    suspend fun confirmOrderPayment(orderId: Int, orderKey: String, returnParams: Map<String, String> = emptyMap()): CoCartResponse =
        http.postRaw(
            "$api/order-received/$orderId/confirm",
            mapOf("order_key" to orderKey, "return_params" to returnParams)
        )

    /**
     * Finish a payment from the URL the app was opened with after an off-site step. Reads
     * the order ID (`order_id`) and key (`key`) from the URL and forwards every query
     * parameter. `isPending` means the gateway hasn't confirmed yet: call again shortly. Safe to
     * call more than once.
     */
    suspend fun confirmReturn(url: String, orderId: Int? = null, orderKey: String? = null): CheckoutResult {
        val params = queryParams(url)
        val id = orderId ?: params["order_id"]?.toIntOrNull() ?: 0
        val key = orderKey ?: params["key"].orEmpty()

        if (id <= 0 || key.isEmpty()) {
            throw CoCartException(
                "The return URL has no order ID or order key. Pass them if your link carries them elsewhere.",
                null,
                mapOf("code" to "checkout_return_missing_order")
            )
        }

        return CheckoutResult.fromResponse(confirmOrderPayment(id, key, params))
    }

    private companion object {
        /** What `checkout_id` CoCart Plus accepts: 16 to 64 letters, digits, `-` or `_`. */
        val CHECKOUT_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
    }

    /** A URL's query parameters, decoded. */
    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}
