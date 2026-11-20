package com.cocart.sdk

import com.cocart.sdk.errors.AuthException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.NetworkException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.RateLimitInfo
import com.cocart.sdk.storage.CoCartStorage
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
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

/** Reading responses in the shapes CoCart Starter 5 sends (recorded in fixtures/cart.json). */
@RunWith(RobolectricTestRunner::class)
class ResponseShapesTest {

    private fun toMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { toValue(obj.get(it)) }

    private fun toValue(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { toValue(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun response(json: String, headers: Map<String, String> = emptyMap()) =
        CoCartResponse(toMap(JSONObject(json)), headers, 200)

    private fun recordedCart(): CoCartResponse {
        val json = javaClass.classLoader!!.getResource("fixtures/cart.json")!!.readText()
        return response(json)
    }

    @Test
    fun `getShippingMethods lists the packages from shipping packages`() {
        val packages = recordedCart().getShippingMethods()
        assertEquals(1, packages.size)
        assertEquals("Shipment", packages[0]["package_name"])
        assertTrue((packages[0]["rates"] as Map<*, *>).containsKey("flat_rate:1"))
    }

    @Test
    fun `getShipping returns the whole field`() {
        val shipping = recordedCart().getShipping()!!
        assertEquals(true, shipping["has_calculated_shipping"])
        assertTrue((shipping["packages"] as Map<*, *>).containsKey("default"))
    }

    @Test
    fun `getShippingMethods accepts a list and is empty without shipping`() {
        assertEquals(1, response("""{"shipping":[{"package_name":"A"}]}""").getShippingMethods().size)
        assertEquals(emptyList<Any>(), response("""{"items":[]}""").getShippingMethods())
        assertNull(response("""{"items":[]}""").getShipping())
    }

    @Test
    fun `getShippingMethods keeps the package order`() {
        val r = response("""{"shipping":{"packages":{"vendor_9":{"package_name":"B"},"vendor_2":{"package_name":"A"}}}}""")
        assertEquals(listOf("B", "A"), r.getShippingMethods().map { it["package_name"] })
    }

    @Test
    fun `getFees lists fees keyed by fee ID`() {
        val r = response("""{"fees":{"handling":{"name":"Handling","fee":"150"},"gift-wrap":{"name":"Gift wrap","fee":"500"}}}""")
        assertEquals(
            listOf(mapOf("name" to "Handling", "fee" to "150"), mapOf("name" to "Gift wrap", "fee" to "500")),
            r.getFees()
        )
        assertEquals(emptyList<Any>(), response("""{"fees":[]}""").getFees())
        assertEquals(emptyList<Any>(), recordedCart().getFees())
    }

    @Test
    fun `getNotices returns notices keyed by type`() {
        val r = response("""{"notices":{"success":["Item added"],"notice":["Quantity reduced"]}}""")
        assertEquals(mapOf("success" to listOf("Item added"), "notice" to listOf("Quantity reduced")), r.getNotices())
        // CoCart Starter 5 sends {}, CoCart 4.x [].
        assertEquals(emptyMap<String, List<String>>(), recordedCart().getNotices())
        assertEquals(emptyMap<String, List<String>>(), response("""{"notices":[]}""").getNotices())
    }

    @Test
    fun `getExtensions`() {
        val r = response("""{"extensions":{"recurring_totals":{"abc":{"totals":{"period":"/ month"}}}}}""")
        assertTrue(r.getExtensions().containsKey("recurring_totals"))
        assertEquals(emptyMap<String, Any?>(), response("""{"extensions":[]}""").getExtensions())
        assertEquals(emptyMap<String, Any?>(), recordedCart().getExtensions())
    }

    @Test
    fun `getItemCount reads item_count from the recorded cart`() {
        assertEquals(2, recordedCart().getItemCount())
    }

    @Test
    fun `getRateLimit reads the RateLimit headers ignoring their case`() {
        val r = response("{}", mapOf("ratelimit-limit" to "25", "RateLimit-Remaining" to "24", "RATELIMIT-RESET" to "1791108883"))
        assertEquals(RateLimitInfo(25, 24, 1791108883, null), r.getRateLimit())
        assertEquals(RateLimitInfo(null, null, null, null), response("{}").getRateLimit())
        assertEquals(30L, response("{}", mapOf("RateLimit-Retry-After" to "30")).getRateLimit().retryAfter)
    }

    @Test
    fun `headers are read ignoring their case`() {
        // HTTP/2 sends header names lowercase.
        assertEquals("t_abc", response("{}", mapOf("cart-key" to "t_abc")).getCartKey())
        assertEquals("HIT", response("{}", mapOf("cocart-cache" to "HIT")).getCacheStatus())
    }
}

@RunWith(RobolectricTestRunner::class)
class InvalidCartAndErrorsTest {
    private lateinit var server: RecordingServer
    private val invalidCart = """{"code":"cocart_invalid_cart","message":"Cart is not valid.","data":{"status":404}}"""

    @Before
    fun setup() {
        server = RecordingServer()
    }

    @After
    fun teardown() = server.shutdown()

    private suspend fun storageWith(key: String) = MemoryStorage().apply { set("cocart_cart_key", key) }

    private suspend fun client(storage: CoCartStorage, options: CoCartOptions = CoCartOptions()): CoCart =
        CoCart(null, server.url, options.copy(storage = storage)).apply { restoreSession() }

    // CoCart Starter 5 refuses a cart key with no cart session (e.g. an expired cart).

    @Test
    fun `forgets the stored key and retries once as a new guest`() = runTest {
        server.enqueue(404, invalidCart)
        server.enqueue(200, """{"cart_key":"t_fresh","items":[]}""", "Cart-Key" to "t_fresh")
        val storage = storageWith("t_expired")
        val c = client(storage)

        c.cart().get()

        val reqs = server.requests.toList()
        assertEquals(2, reqs.size)
        assertTrue(reqs[0].query!!.contains("cart_key=t_expired"))
        assertNull(reqs[1].query)
        assertNull(reqs[1].header("Cart-Key"))
        assertEquals("t_fresh", c.getCartKey())
        assertEquals("t_fresh", storage.get("cocart_cart_key"))
    }

    @Test
    fun `retries a write with the same body`() = runTest {
        server.enqueue(404, invalidCart)
        client(storageWith("t_expired")).cart().addItem(42, 1.0)
        val reqs = server.requests.toList()
        assertEquals(2, reqs.size)
        assertEquals(reqs[0].body, reqs[1].body)
    }

    @Test
    fun `retries only once`() = runTest {
        server.enqueue(404, invalidCart)
        server.enqueue(404, invalidCart)
        try {
            client(storageWith("t_expired")).cart().get()
            fail("Expected CoCartException")
        } catch (e: CoCartException) {
            assertEquals("cocart_invalid_cart", e.errorCode())
        }
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `does not retry without a cart key or when logged in`() = runTest {
        server.enqueue(404, invalidCart)
        try {
            client(MemoryStorage()).cart().get()
            fail("Expected CoCartException")
        } catch (e: CoCartException) {
        }
        assertEquals(1, server.requests.size)

        server.enqueue(404, invalidCart)
        val storage = storageWith("t_expired")
        try {
            client(storage, CoCartOptions(username = "user", password = "pass")).cart().get()
            fail("Expected CoCartException")
        } catch (e: CoCartException) {
        }
        assertEquals(2, server.requests.size)
        assertEquals("t_expired", storage.get("cocart_cart_key"))
    }

    @Test
    fun `leaves other errors alone`() = runTest {
        server.enqueue(404, """{"code":"cocart_item_not_in_cart","message":"Item not in cart."}""")
        val storage = storageWith("t_expired")
        val c = client(storage)
        try {
            c.cart().getItem("abc")
            fail("Expected CoCartException")
        } catch (e: CoCartException) {
        }
        assertEquals(1, server.requests.size)
        assertEquals("t_expired", c.getCartKey())
    }

    @Test
    fun `a list body (categories) is kept`() = runTest {
        server.enqueue(200, javaClass.classLoader!!.getResource("fixtures/categories.json")!!.readText())
        val response = client(MemoryStorage()).products().categories()
        assertTrue(response.toList().isNotEmpty())
        assertEquals("Accessories", response.get("0.name"))
        assertTrue(response.toMap().isEmpty())
    }

    // --- Errors ---

    @Test
    fun `getCart returns the cart sent with cocart_item_not_in_cart`() = runTest {
        server.enqueue(
            404,
            """{"code":"cocart_item_not_in_cart","message":"Item specified does not exist in cart.","data":{"status":404,"cart":{"items":[{"item_key":"def"}],"item_count":1}}}"""
        )
        try {
            client(MemoryStorage()).cart().updateItem("abc", 2.0)
            fail("Expected CoCartException")
        } catch (e: CoCartException) {
            assertEquals("cocart_item_not_in_cart", e.errorCode())
            assertEquals(1, e.getCart()!!["item_count"])
        }
        assertNull(CoCartException("Nope", 404, mapOf("data" to mapOf("status" to 404))).getCart())
        assertNull(CoCartException("Nope").getCart())
    }

    @Test
    fun `401 and 403 throw AuthException with the response data`() = runTest {
        server.enqueue(403, """{"code":"cocart_authentication_error","message":"Forbidden."}""")
        try {
            client(MemoryStorage(), CoCartOptions(jwtToken = "eyJ.a.b")).cart().get()
            fail("Expected AuthException")
        } catch (e: AuthException) {
            assertEquals(403, e.statusCode)
            assertEquals("cocart_authentication_error", e.errorCode)
            assertEquals("cocart_authentication_error", e.errorCode())
        }
    }

    @Test
    fun `400 throws ValidationException with the response data`() = runTest {
        server.enqueue(400, """{"code":"rest_invalid_param","message":"Invalid parameter(s): orderby"}""")
        try {
            client(MemoryStorage()).products().all(mapOf("orderby" to "price"))
            fail("Expected ValidationException")
        } catch (e: com.cocart.sdk.errors.ValidationException) {
            assertEquals(400, e.statusCode)
            assertEquals("rest_invalid_param", e.errorCode())
        }
    }

    @Test
    fun `connection errors keep the cart key out`() = runTest {
        // Nothing listens on port 1.
        val c = CoCart(null, "http://127.0.0.1:1", CoCartOptions(cartKey = "t_secret_cart_key", maxRetries = 0, timeout = 5_000))
        try {
            c.cart().get()
            fail("Expected NetworkException")
        } catch (e: NetworkException) {
            assertFalse(e.message!!.contains("t_secret_cart_key"))
            assertFalse(e.cause?.message.orEmpty().contains("t_secret_cart_key"))
        }
    }
}
