package com.cocart.sdk.resources

import com.cocart.sdk.http.CoCartResponse

/**
 * The outcome of a call that takes payment: placing an order, paying for an unpaid order,
 * returning from an off-site step, or changing a subscription's payment method (CoCart Plus 2.0).
 *
 * Read from the response's `result`. A declined card is a result ([isFailed]), not an
 * exception: exceptions are kept for requests the store refuses before it tries to take payment.
 *
 * Printing a result ([toString]) leaves out [clientSecret], [orderKey], [redirectUrl] (which can
 * carry the order key), [actionData] and the response.
 */
class CheckoutResult private constructor(
    /**
     * `result.code`, as sent: `order_placed`, `action_required`, `payment_pending`,
     * `payment_failed`, or `payment_method_changed` for a subscription. A value a store or
     * gateway adds comes through as it is.
     */
    val code: String,
    /**
     * The document returned (`object`): `order`, or `subscription` after a subscription's payment
     * method change. Named `objectType` because `object` is a Kotlin keyword.
     */
    val objectType: String?,
    /** The order's ID, when the document is an order. */
    val orderId: Int?,
    /** The order's key, needed to view or pay for it as a guest. */
    val orderKey: String?,
    /** The order number, for display. Can differ from the ID. */
    val orderNumber: String?,
    /** The order's status code, e.g. `processing`, `on-hold` or `pending`. */
    val orderStatus: String?,
    /** The subscription's ID, when the document is a subscription. */
    val subscriptionId: Int?,
    /**
     * Where to send the customer: the off-site step when [needsRedirect], otherwise the page to
     * show once the payment is done (the order-received page, or the gateway's own). Only ever an
     * https or http address.
     */
    val redirectUrl: String?,
    /** For an in-app step (3D Secure), or a payment the customer makes in the app: give it to the gateway's SDK. */
    val clientSecret: String?,
    /** `payment_intent` or `setup_intent`, alongside [clientSecret]. */
    val intentType: String?,
    /** The Stripe payment method to pay with when [isAwaitingPayment]: `cashapp`, `wechat_pay`, `boleto`, `oxxo` or `multibanco`. */
    val paymentMethodType: String?,
    /** A message for the customer, for every code: why a payment failed, or that the order was received. Plain text. */
    val message: String?,
    /** CoCart's code for why the payment failed: `card_declined`, `insufficient_funds`… or `payment_failed`. */
    val errorCode: String?,
    /** The gateway's own error code, for logging. */
    val gatewayErrorCode: String?,
    /** The gateway's own message, for logging. Plain text. */
    val gatewayMessage: String?,
    /** What the customer must do, as the gateway integration names it, e.g. `stripe_confirm_payment` or `stripe_voucher_payment`. */
    val actionType: String?,
    /** What the action needs, e.g. `client_secret` or `redirect`. */
    val actionData: Map<String, Any?>?,
    /** The raw response: the order, or the subscription. */
    val response: CoCartResponse
) {
    /** Paid, nothing to pay, or held for review or offline payment: the order is placed (or the subscription's payment method changed). */
    val isComplete: Boolean
        get() = (code == "order_placed" || code == "payment_method_changed") && actionType == null

    /**
     * The order is placed but not paid yet: the customer pays outside the checkout. With
     * [clientSecret], pay in the app with the gateway's SDK for [paymentMethodType] (Cash App Pay,
     * WeChat Pay, a Boleto, OXXO or Multibanco voucher); otherwise [actionType] says what to show
     * (`stripe_blik_confirmation_pending`: approve in the banking app; bank transfer instructions…).
     * The gateway's webhook marks the order paid, so don't place the order again.
     */
    val isAwaitingPayment: Boolean
        get() = code == "order_placed" && actionType != null

    /** The card needs an in-app step (3D Secure): confirm [clientSecret] with the gateway's SDK, then make the same call again. */
    val needsConfirmation: Boolean
        get() = code == "action_required" && clientSecret != null

    /** The payment needs an off-site step: open [redirectUrl], then call `confirmReturn()` when the customer comes back. */
    val needsRedirect: Boolean
        get() = code == "action_required" && clientSecret == null && webUrl(actionData?.get("redirect")) != null

    /** The gateway hasn't confirmed the payment yet. Confirm again shortly, or check the order. */
    val isPending: Boolean
        get() = code == "payment_pending"

    /** The payment was declined or couldn't be taken. Show [message]; the order can be paid again. */
    val isFailed: Boolean
        get() = code == "payment_failed"

    /** Leaves out [clientSecret], [orderKey], [redirectUrl], [actionData] and the response, so printing or logging a result doesn't expose them. */
    override fun toString(): String =
        "CheckoutResult(code=$code, objectType=$objectType, orderId=$orderId, orderNumber=$orderNumber, " +
            "orderStatus=$orderStatus, subscriptionId=$subscriptionId, actionType=$actionType, errorCode=$errorCode)"

    companion object {
        /** Reads the document a call that takes payment returns, with what the payment did in `result`. */
        fun fromResponse(response: CoCartResponse): CheckoutResult {
            val data = response.toMap()
            val result = map(data["result"]) ?: emptyMap()
            val action = map(result["action"])
            val actionData = map(action?.get("data"))
            val error = map(result["error"])
            val objectType = text(data["object"])
            val isOrder = objectType != "subscription"
            // A missing code is read as a failure: never as "complete".
            val code = text(result["code"]) ?: "payment_failed"
            val status = map(data["status"])
            val number = text(data["number"]) ?: integer(data["number"])?.toString()
            val offSite = webUrl(actionData?.get("redirect"))

            return CheckoutResult(
                code = code,
                objectType = objectType,
                orderId = if (isOrder) integer(data["id"]) else null,
                orderKey = if (isOrder) text(data["key"]) else null,
                orderNumber = if (isOrder) number else null,
                orderStatus = if (isOrder) text(status?.get("code")) else null,
                subscriptionId = if (isOrder) null else integer(data["id"]),
                redirectUrl = if (code == "action_required") {
                    offSite ?: webUrl(result["redirect_url"])
                } else {
                    webUrl(result["redirect_url"]) ?: offSite
                },
                clientSecret = text(actionData?.get("client_secret")),
                intentType = text(actionData?.get("intent_type")),
                paymentMethodType = text(actionData?.get("payment_method_type")),
                message = text(result["message"]),
                errorCode = text(error?.get("code")),
                gatewayErrorCode = text(error?.get("gateway_code")),
                gatewayMessage = text(error?.get("gateway_message")),
                actionType = text(action?.get("type")),
                actionData = actionData,
                response = response
            )
        }

        private fun text(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

        private fun integer(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

        private val WEB_URL = Regex("^https?://[^\\s/?#]", RegexOption.IGNORE_CASE)

        /**
         * A web address the customer can be sent to: https or http only. Anything else
         * (`javascript:`, `data:`, another app's link, a relative path) is left out, so a
         * response can't make the app run script or open another app.
         */
        private fun webUrl(value: Any?): String? = text(value)?.takeIf { WEB_URL.containsMatchIn(it) }

        @Suppress("UNCHECKED_CAST")
        private fun map(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>
    }
}

/**
 * How a checkout ended, from `checkout().getStatus()`. The order fields are `null` unless an
 * order was placed or needs payment.
 */
data class CheckoutStatus(
    val checkoutId: String,
    /**
     * `order_placed` (show the order), `payment_required` (the order was placed but isn't paid:
     * pay it with `payForOrder()`), `in_progress` (ask again in a few seconds) or `not_placed`
     * (place the order again).
     */
    val code: String,
    /** A message for the customer. Plain text. */
    val message: String,
    val orderId: Int?,
    val orderKey: String?,
    val orderNumber: String?,
    /** The order's status code, e.g. `processing` or `pending`. */
    val orderStatus: String?
) {
    /** Leaves out [orderKey], so printing or logging a status doesn't expose it. */
    override fun toString(): String =
        "CheckoutStatus(checkoutId=$checkoutId, code=$code, orderId=$orderId, orderNumber=$orderNumber, orderStatus=$orderStatus)"
}

/** A logged-in customer's saved payment method, from [CheckoutResource.getSavedPaymentMethods]. */
data class SavedPaymentMethod(
    /** Pay with it as `wc-{gatewayId}-payment-token` in `payment_data`, or pass it as `token_id` to change a subscription's card. */
    val id: Int,
    /** The payment method's (gateway's) ID, e.g. `stripe`. */
    val gatewayId: String,
    /** The saved method as the customer sees it, e.g. "Visa ending in 4242". */
    val label: String,
    val type: String,
    /** `MM/YYYY`, or `null` when it has no expiry. */
    val expires: String?,
    val isDefault: Boolean,
    /** The gateway can charge subscription renewals with it. */
    val supportsSubscriptions: Boolean
)

/** Turns a `payment_data` map into the `{key, value}` pairs the API takes. A list passes through as it is. */
fun paymentDataPairs(data: Any?): Any? = when (data) {
    is Map<*, *> -> data.map { (key, value) -> mapOf("key" to key.toString(), "value" to value) }
    else -> data
}

internal fun withPaymentDataPairs(input: Map<String, Any?>): Map<String, Any?> =
    if (input.containsKey("payment_data")) input + ("payment_data" to paymentDataPairs(input["payment_data"])) else input
