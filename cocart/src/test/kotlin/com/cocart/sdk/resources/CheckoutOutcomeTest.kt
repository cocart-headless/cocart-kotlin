package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.CheckoutOutcomeUnknownException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * placeOrder() sends a checkout_id, and when no answer comes back it says so with that ID,
 * for getStatus() to tell how the checkout ended.
 */
@RunWith(RobolectricTestRunner::class)
class CheckoutOutcomeTest {
    private lateinit var server: MockWebServer
    private val order = mapOf("payment_method" to "cod", "billing_address" to mapOf("email" to "jane@example.com"))
    private val paid = """{"object":"order","id":217,"result":{"code":"order_placed","message":"Thank you.","action":null,"redirect_url":null,"error":null}}"""

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun checkout(options: CoCartOptions = CoCartOptions()): CheckoutResource =
        CheckoutResource(HttpClient(server.url("/").toString(), options, AuthManager(options, MemoryStorage())))

    private fun sentCheckoutId(): String = JSONObject(server.takeRequest().body.readUtf8()).getString("checkout_id")

    @Test
    fun `each order is sent with a new random checkout_id`() = runTest {
        server.enqueue(MockResponse().setBody(paid))
        server.enqueue(MockResponse().setBody(paid))

        checkout().placeOrder(order)
        checkout().placeOrder(order)

        val first = sentCheckoutId()
        val second = sentCheckoutId()
        assertTrue(first, Regex("^chk_[0-9a-f]{32}$").matches(first))
        assertTrue(second, Regex("^chk_[0-9a-f]{32}$").matches(second))
        assertNotEquals(first, second)
    }

    @Test
    fun `the checkout_id given is sent`() = runTest {
        server.enqueue(MockResponse().setBody(paid))

        checkout().placeOrder(order + ("checkout_id" to "chk_mine_00000000001"))

        assertEquals("chk_mine_00000000001", sentCheckoutId())
    }

    @Test
    fun `a timeout says the outcome is unknown, with the checkout_id`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS).setBody(paid))

        try {
            checkout(CoCartOptions(timeout = 100)).placeOrder(order)
            fail("The timeout should reach the caller")
        } catch (e: CheckoutOutcomeUnknownException) {
            assertEquals(sentCheckoutId(), e.checkoutId)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a proxy that gave up says the outcome is unknown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(504).setBody("<html>Gateway Time-out</html>"))

        try {
            checkout().placeOrder(order)
            fail("The 504 should reach the caller")
        } catch (e: CheckoutOutcomeUnknownException) {
            assertEquals(504, e.statusCode)
            assertEquals(sentCheckoutId(), e.checkoutId)
        }
    }

    @Test
    fun `another payment still running for the cart says the outcome is unknown`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"code":"cocart_payment_in_progress","message":"A payment is already being processed.","data":{"status":409}}""")
        )

        try {
            checkout().placeOrder(order + ("checkout_id" to "chk_mine_00000000001"))
            fail("The 409 should reach the caller")
        } catch (e: CheckoutOutcomeUnknownException) {
            assertEquals("chk_mine_00000000001", e.checkoutId)
            assertEquals(409, e.statusCode)
            assertEquals("cocart_payment_in_progress", e.errorCode())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a malformed checkout_id is refused before anything is sent`() = runTest {
        for (id in listOf("too short", "chk_${"a".repeat(61)}", "chk_has spaces_0000000", 12345678901234567L)) {
            try {
                checkout().placeOrder(order + ("checkout_id" to id))
                fail("$id should be refused")
            } catch (e: CoCartException) {
                assertFalse(e is CheckoutOutcomeUnknownException)
                assertEquals("checkout_invalid_checkout_id", e.errorCode())
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an error the store answered with is kept`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"code":"cocart_cant_checkout_empty_cart","message":"Cannot checkout with empty cart.","data":{"status":400}}""")
        )

        try {
            checkout().placeOrder(order)
            fail("The error should reach the caller")
        } catch (e: CoCartException) {
            assertFalse(e is CheckoutOutcomeUnknownException)
            assertEquals("cocart_cant_checkout_empty_cart", e.errorCode())
        }
    }
}
