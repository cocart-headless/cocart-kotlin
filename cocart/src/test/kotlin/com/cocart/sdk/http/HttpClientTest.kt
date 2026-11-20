package com.cocart.sdk.http

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HttpClientTest {
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

    private fun buildClient(options: CoCartOptions): HttpClient {
        val auth = AuthManager(options, MemoryStorage())
        return HttpClient(server.url("/").toString(), options, auth)
    }

    @Test
    fun `sends only Cart-Key header in STARTER mode`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val client = buildClient(CoCartOptions(cartKey = "guest123", mainPlugin = MainPlugin.STARTER))

        client.get("cart")

        val recorded = server.takeRequest()
        assertEquals("guest123", recorded.getHeader("Cart-Key"))
        assertNull(recorded.getHeader("CoCart-API-Cart-Key"))
    }

    @Test
    fun `sends only CoCart-API-Cart-Key header in LEGACY mode`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val client = buildClient(CoCartOptions(cartKey = "guest123", mainPlugin = MainPlugin.LEGACY))

        client.get("cart")

        val recorded = server.takeRequest()
        assertEquals("guest123", recorded.getHeader("CoCart-API-Cart-Key"))
        assertNull(recorded.getHeader("Cart-Key"))
    }

    @Test
    fun `304 response returns cached body and headers from the prior 2xx GET`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"item_count":3}""")
                .addHeader("ETag", "\"abc123\"")
                .setResponseCode(200)
        )
        server.enqueue(MockResponse().setResponseCode(304))

        val client = buildClient(CoCartOptions())

        val first = client.get("cart")
        assertEquals(3, first.getItemCount())
        assertFalse(first.isNotModified())

        val second = client.get("cart")
        assertTrue(second.isNotModified())
        assertEquals(3, second.getItemCount())

        // Second request should have carried If-None-Match with the cached ETag
        server.takeRequest() // first request
        val secondRecorded = server.takeRequest()
        assertEquals("\"abc123\"", secondRecorded.getHeader("If-None-Match"))
    }

    @Test
    fun `GET retries after a network-level failure and eventually succeeds`() = runTest {
        // First response never finishes within the client's read timeout, causing an
        // IOException (wrapped as NetworkException) that should trigger a retry.
        server.enqueue(MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS).setBody("{}").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val client = buildClient(CoCartOptions(maxRetries = 1, timeout = 100))

        val response = client.get("cart")
        assertEquals(true, response.get("ok"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `POST is not sent again after a network-level failure, as it may have run`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS).setBody("{}").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val client = buildClient(CoCartOptions(maxRetries = 2, timeout = 100))

        try {
            client.post("checkout", mapOf("payment_method" to "stripe"))
            fail("The network failure should reach the caller")
        } catch (e: com.cocart.sdk.errors.NetworkException) {
            // Expected.
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 retry honors Retry-After header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val client = buildClient(CoCartOptions(maxRetries = 1))

        val response = client.post("cart/add-item", mapOf("id" to 1))
        assertEquals(true, response.get("ok"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `concurrent identical GETs are de-duplicated into one round trip`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"item_count":1}""")
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
        )

        val client = buildClient(CoCartOptions())

        coroutineScope {
            val a = async { client.get("cart") }
            val b = async { client.get("cart") }
            val (first, second) = a.await() to b.await()
            assertEquals(1, first.getItemCount())
            assertEquals(1, second.getItemCount())
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `batch throws plugin required error when route is missing`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"code":"rest_no_route","message":"No route was found"}""")
                .setResponseCode(404)
        )
        val client = buildClient(CoCartOptions())

        try {
            client.batch(listOf(BatchRequestItem(method = "DELETE", path = "/cocart/v2/cart/item/abc")))
            fail("Expected CoCartException")
        } catch (e: com.cocart.sdk.errors.CoCartException) {
            assertEquals("cocart_plugin_required", e.data?.get("code"))
        }
    }

    @Test
    fun `batch posts requests array to namespace batch route`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        val client = buildClient(CoCartOptions())

        client.batch(
            listOf(
                BatchRequestItem(method = "DELETE", path = "/cocart/v2/cart/item/abc"),
                BatchRequestItem(method = "POST", path = "/cocart/v2/cart/item/def", body = mapOf("quantity" to 2))
            )
        )

        val recorded = server.takeRequest()
        assertEquals("/wp-json/cocart/batch", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"requests\""))
    }
}
