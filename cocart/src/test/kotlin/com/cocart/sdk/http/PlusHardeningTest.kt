package com.cocart.sdk.http

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.AuthException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.RateLimitException
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Client hardening for CoCart Plus 2.0. */
@RunWith(RobolectricTestRunner::class)
class PlusHardeningTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun client(options: CoCartOptions = CoCartOptions()): HttpClient =
        HttpClient(server.url("/").toString(), options, AuthManager(options, MemoryStorage()))

    private fun reply(status: Int, body: String = "{}") = MockResponse().setResponseCode(status).setBody(body)

    private suspend fun caught(block: suspend () -> Unit): CoCartException {
        try {
            block()
        } catch (e: CoCartException) {
            return e
        }
        fail("The request should have thrown")
        throw IllegalStateException()
    }

    @Test
    fun `waits as long as RateLimit-Retry-After says before retrying a 429`() = runTest {
        server.enqueue(reply(429, """{"code":"cocart_rate_limited","message":"Slow down"}""").addHeader("RateLimit-Retry-After", "7"))
        server.enqueue(reply(200, """{"items":[]}"""))

        client(CoCartOptions(maxRetries = 1)).post("cart/add-item", mapOf("id" to "1"))

        assertEquals(7000L, testScheduler.currentTime)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `still reads Retry-After when that is all a proxy sends`() = runTest {
        server.enqueue(reply(429).addHeader("Retry-After", "3"))
        server.enqueue(reply(200))

        client(CoCartOptions(maxRetries = 1)).post("cart/add-item", mapOf("id" to "1"))

        assertEquals(3000L, testScheduler.currentTime)
    }

    @Test
    fun `a rate limit carries the wait and the response data`() = runTest {
        server.enqueue(
            reply(429, """{"code":"cocart_guest_lookup_rate_limited","message":"Too many lookups.","data":{"status":429}}""")
                .addHeader("RateLimit-Retry-After", "30")
        )

        val e = caught { client(CoCartOptions(maxRetries = 0)).getRaw("cocart/v2/my-account/orders/12", mapOf("email" to "jane@example.com")) }

        assertTrue(e is RateLimitException)
        assertEquals(30L, (e as RateLimitException).retryAfter)
        assertEquals("cocart_guest_lookup_rate_limited", e.errorCode())
    }

    @Test
    fun `debug output hides every query value - cart keys, order keys and emails stay out of logs`() = runTest {
        server.enqueue(reply(200))
        server.enqueue(reply(200))
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, "UTF-8"))
        try {
            val http = client(CoCartOptions(debug = true))
            http.get("cart", mapOf("order_key" to "wc_order_secret", "email" to "jane@example.com"))
            http.getRaw("cocart/v2/order-received/12", mapOf("order_key" to "wc_order_secret", "email" to "jane@example.com"))
        } finally {
            System.setOut(original)
        }

        val output = captured.toString("UTF-8")
        assertTrue(output, output.contains("order_key=***"))
        assertTrue(output, output.contains("email=***"))
        assertFalse(output, output.contains("wc_order_secret"))
        assertFalse(output, output.contains("jane"))
    }

    @Test
    fun `redactQuery replaces every query value`() {
        assertEquals("https://store.com/wp-json/cocart/v2/cart", HttpClient.redactQuery("https://store.com/wp-json/cocart/v2/cart"))
        assertEquals(
            "https://store.com/x?cart_key=***&flag&email=***",
            HttpClient.redactQuery("https://store.com/x?cart_key=t_abc&flag&email=jane%40example.com")
        )
    }

    @Test
    fun `does not keep a no-store response in the ETag cache`() = runTest {
        repeat(2) {
            server.enqueue(
                reply(200, """{"object":"order"}""")
                    .addHeader("ETag", "\"abc\"")
                    .addHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0, private")
            )
        }
        val http = client()

        http.get("cart")
        http.get("cart")

        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `does not keep a private response in the ETag cache`() {
        assertFalse(HttpClient.mayStore("private, max-age=0"))
        assertFalse(HttpClient.mayStore("no-store"))
        assertTrue(HttpClient.mayStore(null))
        assertTrue(HttpClient.mayStore("public, max-age=60"))
        assertTrue(HttpClient.mayStore("no-cache"))
    }

    @Test
    fun `still uses the ETag cache for a response that may be stored`() = runTest {
        repeat(2) { server.enqueue(reply(200, """{"items":[]}""").addHeader("ETag", "\"abc\"")) }
        val http = client()

        http.get("products")
        http.get("products")

        server.takeRequest()
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a CoCart Plus request emits request and response events, with the response`() = runTest {
        server.enqueue(reply(200, """{"object":"order","result":{"code":"order_placed"}}"""))
        val http = client()
        val seen = mutableListOf<String>()
        var code: Any? = null
        http.on(CoCartEvent.REQUEST) { seen += "request ${it["method"]}" }
        http.on(CoCartEvent.RESPONSE) {
            seen += "response ${it["status"]}"
            code = (it["response"] as CoCartResponse).get("result.code")
        }

        http.postRaw("cocart/v2/checkout", mapOf("payment_method" to "cod"))

        assertEquals(listOf("request POST", "response 200"), seen)
        assertEquals("order_placed", code)
    }

    @Test
    fun `a CoCart Plus error names its route and emits an error event`() = runTest {
        server.enqueue(reply(404, """{"code":"cocart_order_not_found","message":"Not found.","data":{"status":404}}"""))
        val http = client()
        val errors = mutableListOf<Any?>()
        http.on(CoCartEvent.ERROR) { errors += it["error"] }

        val e = caught { http.getRaw("cocart/v2/order-received/12", mapOf("order_key" to "wc_order_secret")) }

        assertEquals("GET /cocart/v2/order-received/12: Not found. [cocart_order_not_found]", e.message)
        assertEquals(listOf<Any?>("GET /cocart/v2/order-received/12: Not found. [cocart_order_not_found]"), errors)
    }

    @Test
    fun `a CoCart Plus request is never sent again`() = runTest {
        server.enqueue(reply(429, """{"code":"cocart_rate_limited"}""").addHeader("RateLimit-Retry-After", "1"))
        server.enqueue(reply(200))

        val e = caught { client(CoCartOptions(maxRetries = 2)).postRaw("cocart/v2/checkout", mapOf("payment_method" to "cod")) }

        assertTrue(e is RateLimitException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 409 is never a validation error, even when its code says invalid`() = runTest {
        server.enqueue(reply(409, """{"code":"cocart_order_invalid_status","message":"Paid already.","data":{"status":409}}"""))

        val e = caught { client().postRaw("cocart/v2/order-received/12/pay") }

        assertFalse(e is ValidationException)
        assertEquals(CoCartException::class.java, e.javaClass)
        assertEquals(409, e.statusCode)
    }

    @Test
    fun `a 5xx is never a validation error`() = runTest {
        server.enqueue(reply(500, """{"code":"cocart_invalid_state","message":"Oops.","data":{"status":500}}"""))

        val e = caught { client().postRaw("cocart/v2/checkout") }

        assertEquals(CoCartException::class.java, e.javaClass)
        assertEquals(500, e.statusCode)
    }

    @Test
    fun `400 is still a validation error and 401 an authentication error`() = runTest {
        server.enqueue(reply(400, """{"code":"cocart_field_required","message":"Missing.","data":{"status":400}}"""))
        server.enqueue(reply(401, """{"code":"cocart_rest_authentication_required","message":"Log in.","data":{"status":401}}"""))
        val http = client()

        assertTrue(caught { http.postRaw("cocart/v2/checkout") } is ValidationException)
        assertTrue(caught { http.getRaw("cocart/v2/my-account") } is AuthException)
    }

    @Test
    fun `reads every field at fault, the first one and the reason from an error`() = runTest {
        server.enqueue(
            reply(
                400,
                """{"code":"cocart_invalid_address_fields","message":"Check the address.","data":{"status":400,"field":"billing.postcode","fields":{"billing.postcode":"Enter a valid postcode.","billing.phone":"Enter a phone number."},"reason":"postcode_format"}}"""
            )
        )

        val e = caught { client().putRaw("cocart/v2/checkout") }

        assertEquals(
            mapOf("billing.postcode" to "Enter a valid postcode.", "billing.phone" to "Enter a phone number."),
            e.getFields()
        )
        assertEquals("billing.postcode", e.getField())
        assertEquals("postcode_format", e.getReason())
    }

    @Test
    fun `an error without field details gives an empty map and nulls`() {
        val e = CoCartException("Nope", 404, mapOf("code" to "cocart_order_not_found", "data" to mapOf("status" to 404)))

        assertEquals(emptyMap<String, String>(), e.getFields())
        assertNull(e.getField())
        assertNull(e.getReason())
        assertEquals(emptyMap<String, String>(), CoCartException("Nope").getFields())
    }
}
