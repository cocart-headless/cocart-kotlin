package com.cocart.sdk.errors

/**
 * `placeOrder()` sent the order but how it ended isn't known: no answer came back (the
 * connection dropped, the request timed out, or a proxy gave up waiting), or another request is
 * still taking payment for the cart (`409 cocart_payment_in_progress`). The order may or may not
 * have been placed. Don't place it again blindly: ask `checkout().getStatus(checkoutId)` how it
 * ended.
 *
 * Keeps the original error's message, status code and data, and the original error as its cause.
 */
class CheckoutOutcomeUnknownException(
    cause: CoCartException,
    /** The `checkout_id` the order was sent with. */
    val checkoutId: String
) : CoCartException(cause.message.orEmpty(), cause.statusCode, cause.data) {
    init { initCause(cause) }
}
