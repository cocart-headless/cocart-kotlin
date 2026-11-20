package com.cocart.sdk.resources

import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient

/**
 * The customer's account: profile, address, orders, downloads, reviews,
 * subscriptions and email verification (CoCart Plus).
 *
 * All methods require the customer to be authenticated (Basic Auth or JWT),
 * except [register], [getGuestOrder], [getGuestOrderDownloads] and
 * [requestOrderWithdrawal].
 */
class AccountResource(
    private val http: HttpClient
) {
    // All account routes bypass the namespace builder — use raw path directly
    private val base = "cocart/v2/my-account"

    // --- Registration (CoCart Plus) ---

    /**
     * Register a new customer. Doesn't need the customer logged in. Returns `201` with the
     * customer and `account_created` in `result`; log in next. An email or username already used
     * is `409` (`cocart_account_exists`, `cocart_registration_username_exists`).
     *
     * The username and password are sent as `requested_username` and
     * `requested_password`: CoCart treats non-empty `username` and `password`
     * fields as a Basic Auth attempt.
     *
     * @param username Optional if the store generates usernames from the email.
     * @param password Optional if the store generates passwords.
     */
    suspend fun register(email: String, username: String? = null, password: String? = null): CoCartResponse =
        wrapPluginError {
            val body = mutableMapOf<String, Any?>("email" to email)
            username?.let { body["requested_username"] = it }
            password?.let { body["requested_password"] = it }
            http.postRaw("cocart/v2/register", body)
        }

    // --- Profile ---

    /**
     * The signed-in customer: name, email, addresses, order stats and `actions`. [params] takes
     * `formatted` and `meta_keys` (a list is comma-joined).
     */
    suspend fun getProfile(params: Map<String, Any?>? = null): CoCartResponse = wrapPluginError {
        http.getRaw(base, documentQuery(params))
    }

    /**
     * Save the account details: send only what changes. Returns the customer with
     * `account_updated` in `result`. Every field at fault is reported at once
     * ([CoCartException.getFields]).
     */
    suspend fun updateProfile(data: Map<String, Any?>): CoCartResponse = wrapPluginError {
        http.postRaw(base, data)
    }

    /**
     * Change the customer's password (`POST my-account`), sending only `password_current`,
     * `password_1` and `password_2`. Returns the customer with `account_updated` in `result`.
     * A wrong current password is a [com.cocart.sdk.errors.ValidationException]
     * (`cocart_account_incorrect_current_password`).
     */
    suspend fun changePassword(current: String, password: String, confirm: String): CoCartResponse =
        wrapPluginError {
            http.postRaw(
                base,
                mapOf(
                    "password_current" to current,
                    "password_1" to password,
                    "password_2" to confirm
                )
            )
        }

    /**
     * Save the customer's billing and/or shipping address (`billing_address`,
     * `shipping_address`), each with unprefixed fields (`first_name`, `address_1`…).
     */
    suspend fun updateAddress(data: Map<String, Any?>): CoCartResponse = wrapPluginError {
        http.postRaw("$base/address", data)
    }

    // --- Orders ---

    /**
     * The customer's orders, newest first, with `pagination` (also in the `X-WP-Total` and
     * `X-WP-TotalPages` headers). [params] takes `page`, `per_page`, `order`, `status` (one, or a
     * list, comma-joined), `after` and `before` (ISO 8601 dates), `subscription_order_type`
     * (`parent`, `renewal`, `switch` or `resubscribe`), `formatted` and `meta_keys`.
     */
    suspend fun getOrders(params: Map<String, Any?>? = null): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders", documentQuery(params))
    }

    /**
     * One of the customer's orders. Someone else's order is `404 cocart_order_not_found`.
     * [params] takes `formatted` and `meta_keys`.
     */
    suspend fun getOrder(id: Int, params: Map<String, Any?>? = null): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id", documentQuery(params))
    }

    /**
     * A guest's order, looked up with its billing email. It comes without its `key`. Lookups are
     * rate limited (`429 cocart_guest_lookup_rate_limited`). [params] takes `formatted` and
     * `meta_keys`.
     */
    suspend fun getGuestOrder(id: Int, email: String, params: Map<String, Any?>? = null): CoCartResponse =
        wrapPluginError {
            http.getRaw("$base/orders/$id", documentQuery(params.orEmpty() + ("email" to email)))
        }

    /** Cancel one of the customer's orders where WooCommerce allows it (for example a pending or failed order). Returns the order with `result`. */
    suspend fun cancelOrder(id: Int): CoCartResponse = wrapPluginError {
        http.postRaw("$base/orders/$id/cancel")
    }

    // --- Downloads ---

    /** The files one of the customer's orders can download: `{ "downloads": [...] }`, as sent. */
    suspend fun getOrderDownloads(id: Int): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id/downloads")
    }

    /** The files a guest's order can download, looked up with its billing email: `{ "downloads": [...] }`, as sent. */
    suspend fun getGuestOrderDownloads(id: Int, email: String): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id/downloads", mapOf("email" to email))
    }

    /** Every file the customer can download: `{ "downloads": [...] }`. */
    suspend fun getDownloads(): CoCartResponse = wrapPluginError {
        http.getRaw("$base/downloads")
    }

    // --- Reviews ---

    /** The customer's product reviews (`GET products/reviews/mine`). */
    suspend fun getReviews(params: Map<String, String>? = null): CoCartResponse =
        http.get("products/reviews/mine", params)

    // --- Email verification (CoCart Plus) ---

    /** Confirm a pending email change, with the key and user ID from the emailed link. */
    suspend fun verifyEmail(key: String, userId: Int): CoCartResponse = wrapPluginError {
        http.getRaw("$base/verify-email", mapOf("key" to key, "user_id" to userId.toString()))
    }

    /**
     * Email the customer a link proving they own the account email, so WooCommerce
     * links their past guest orders to it (WooCommerce 11+). The response's `status`
     * is `sent`, or `throttled` (once a minute).
     */
    suspend fun sendOrderEmailVerification(): CoCartResponse = wrapPluginError {
        http.postRaw("$base/verify-order-email")
    }

    /** Verify the email with the key and user ID from [sendOrderEmailVerification]'s link. */
    suspend fun verifyOrderEmail(key: String, userId: Int): CoCartResponse = wrapPluginError {
        http.getRaw("$base/verify-order-email", mapOf("key" to key, "user_id" to userId.toString()))
    }

    // --- Subscriptions (CoCart Plus with WooCommerce Subscriptions) ---

    /**
     * The customer's subscriptions, with `pagination`. [params] takes `page`, `per_page`,
     * `order`, `status`, `formatted` and `meta_keys` (a list is comma-joined).
     */
    suspend fun getSubscriptions(params: Map<String, Any?>? = null): CoCartResponse = wrapPluginError {
        http.getRaw("$base/subscriptions", documentQuery(params))
    }

    /** One subscription. Pass the [email] to view it as a guest. [params] takes `formatted` and `meta_keys`. */
    suspend fun getSubscription(id: Int, email: String? = null, params: Map<String, Any?>? = null): CoCartResponse =
        wrapPluginError {
            http.getRaw("$base/subscriptions/$id", documentQuery(params.orEmpty() + listOfNotNull(email?.let { "email" to it })))
        }

    /** Suspend (put on hold) a subscription. */
    suspend fun suspendSubscription(id: Int): CoCartResponse = subscriptionAction(id, "suspend")

    /** Cancel a subscription. */
    suspend fun cancelSubscription(id: Int): CoCartResponse = subscriptionAction(id, "cancel")

    /** Reactivate a suspended subscription. */
    suspend fun reactivateSubscription(id: Int): CoCartResponse = subscriptionAction(id, "reactivate")

    /** Turn automatic renewal on or off. */
    suspend fun setSubscriptionAutoRenew(id: Int, enabled: Boolean): CoCartResponse =
        subscriptionAction(id, "auto-renew", mapOf("enabled" to enabled))

    /**
     * Change the card a subscription renews with: `payment_method` (gateway ID, e.g.
     * `stripe`), and `token_id` (a saved card's ID) or `payment_data` (a new card, e.g.
     * `mapOf("wc-stripe-payment-method" to paymentMethod.id)`).
     *
     * A new card may need 3D Secure, so this returns a [CheckoutResult], read as at
     * checkout: `isComplete` once changed (`payment_method_changed`, with `subscriptionId`); on
     * `needsConfirmation`, confirm `clientSecret` with the gateway's SDK and call again with the
     * same input; on `needsRedirect`, send the customer to `redirectUrl`. A declined card is a
     * failed result, not an exception. Another change still running is
     * `409 cocart_payment_in_progress`.
     */
    suspend fun updateSubscriptionPaymentMethod(id: Int, data: Map<String, Any?>): CheckoutResult =
        CheckoutResult.fromResponse(subscriptionAction(id, "payment-method", withPaymentDataPairs(data)))

    /** Put an ended subscription's items in the cart to subscribe again (checkout follows as usual). */
    suspend fun resubscribe(id: Int): CoCartResponse = subscriptionAction(id, "resubscribe")

    /** Undo [resubscribe]: restore the cart as it was before. */
    suspend fun cancelResubscribe(id: Int): CoCartResponse = wrapPluginError {
        http.deleteRaw("$base/subscriptions/$id/resubscribe")
    }

    private suspend fun subscriptionAction(id: Int, action: String, body: Map<String, Any?>? = null): CoCartResponse =
        wrapPluginError {
            http.postRaw("$base/subscriptions/$id/$action", body)
        }

    // --- Order withdrawal (CoCart Plus) ---

    /**
     * Submit an order withdrawal (right of withdrawal) request. Doesn't need the customer
     * logged in. [data] takes `first_name`, `last_name`, `email`, `email_confirmation`,
     * `order_number`, and optionally `withdrawal_type` (`full_order` or
     * `specific_items_only`) and `additional_details` (required for `specific_items_only`).
     */
    suspend fun requestOrderWithdrawal(data: Map<String, Any?>): CoCartResponse = wrapPluginError {
        http.postRaw("cocart/v2/order-withdrawal", data)
    }

    private suspend fun wrapPluginError(block: suspend () -> CoCartResponse): CoCartResponse {
        return try {
            block()
        } catch (e: CoCartException) {
            if (e.data?.get("code") == "rest_no_route") {
                throw CoCartException(
                    "CoCart plugin required for account endpoints",
                    e.statusCode,
                    mapOf("code" to "cocart_plugin_required")
                )
            }
            throw e
        }
    }
}

/**
 * The query for `formatted`, `meta_keys` and any other values given, as strings, or `null` when
 * there are none. A list is comma-joined, a boolean is `"true"` or `"false"`, and a `null` value is
 * left out.
 */
internal fun documentQuery(params: Map<String, Any?>?): Map<String, String>? {
    val query = mutableMapOf<String, String>()
    params?.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is Iterable<*> -> query[key] = value.joinToString(",")
            is Array<*> -> query[key] = value.joinToString(",")
            else -> query[key] = value.toString()
        }
    }
    return query.ifEmpty { null }
}
