package com.cocart.sdk.resources

import com.cocart.sdk.http.CoCartResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading `result` (CoCart Plus 2.0) beyond what the shared fixtures cover. */
class CheckoutResultTest {

    private fun result(body: Map<String, Any?>) = CheckoutResult.fromResponse(CoCartResponse(body, emptyMap(), 200))

    private val actionRequired = mapOf(
        "object" to "order",
        "id" to 217,
        "number" to "217",
        "key" to "wc_order_secret",
        "status" to mapOf("code" to "pending", "label" to "Pending payment"),
        "result" to mapOf(
            "code" to "action_required",
            "message" to "Confirm the payment.",
            "action" to mapOf(
                "type" to "stripe_confirm_payment",
                "data" to mapOf("client_secret" to "pi_123_secret_456", "intent_type" to "payment_intent")
            ),
            "redirect_url" to "https://shop.example.com/checkout/order-received/217/?key=wc_order_secret",
            "error" to null
        )
    )

    @Test
    fun `a missing code reads as a failure, never as complete`() {
        val r = result(mapOf("object" to "order", "id" to 217))
        assertEquals("payment_failed", r.code)
        assertTrue(r.isFailed)
        assertFalse(r.isComplete)
    }

    @Test
    fun `printing a result never shows the client secret or the order key`() {
        val r = result(actionRequired)
        assertTrue(r.needsConfirmation)
        assertEquals("pi_123_secret_456", r.clientSecret)
        assertEquals("wc_order_secret", r.orderKey)

        val printed = r.toString()
        assertFalse(printed, printed.contains("pi_123_secret_456"))
        assertFalse(printed, printed.contains("wc_order_secret"))
        assertTrue(printed, printed.contains("action_required"))
        assertTrue(printed, printed.contains("217"))
    }

    @Test
    fun `a redirect that isn't a web address is left out`() {
        val r = result(
            mapOf(
                "object" to "order",
                "id" to 1,
                "result" to mapOf(
                    "code" to "action_required",
                    "action" to mapOf("type" to "redirect", "data" to mapOf("redirect" to "javascript:alert(1)")),
                    "redirect_url" to "intent://evil"
                )
            )
        )
        assertNull(r.redirectUrl)
        assertFalse(r.needsRedirect)
    }

    @Test
    fun `printing a checkout status never shows the order key`() {
        val status = CheckoutStatus("chk_fixture_0000000001", "order_placed", "Thanks.", 217, "wc_order_secret", "217", "processing")
        assertFalse(status.toString().contains("wc_order_secret"))
    }
}
