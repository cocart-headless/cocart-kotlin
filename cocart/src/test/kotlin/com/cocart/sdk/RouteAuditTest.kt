package com.cocart.sdk

import com.cocart.sdk.errors.TwoFactorAuthRequiredException
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.resources.ProductOrderBy
import com.cocart.sdk.resources.SortOrder
import com.cocart.sdk.resources.StockStatus
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every method's route, checked against the CoCart Starter 5 and CoCart Plus 2.0 routes. */
@RunWith(RobolectricTestRunner::class)
class RouteAuditTest {
    private lateinit var server: RecordingServer

    @Before
    fun setup() {
        server = RecordingServer()
    }

    @After
    fun teardown() = server.shutdown()

    private fun client(options: CoCartOptions = CoCartOptions()) = CoCart(null, server.url, options)

    private fun calls(): List<String> =
        server.requests.map { r -> "${r.method} ${r.path}" + (r.query?.let { "?$it" } ?: "") }

    // --- Cart ---

    @Test
    fun `cart routes`() = runTest {
        val cart = client().cart()
        cart.restoreItem("abc")
        cart.getRemovedItems()
        cart.getCustomer()
        cart.getShippingMethods()
        cart.getCrossSells()
        cart.getCoupons()
        cart.getFees()
        cart.checkCoupons()
        cart.checkCoupons("jane@example.com")
        cart.removeCoupons()
        cart.removeFees()
        cart.getMini()
        cart.removeAllItems()

        assertEquals(
            listOf(
                "PUT /wp-json/cocart/v2/cart/item/abc",
                "GET /wp-json/cocart/v2/cart?_fields=removed_items",
                "GET /wp-json/cocart/v2/cart?_fields=customer",
                "GET /wp-json/cocart/v2/cart?_fields=shipping",
                "GET /wp-json/cocart/v2/cart?_fields=cross_sells",
                "GET /wp-json/cocart/v2/cart?_fields=coupons",
                "GET /wp-json/cocart/v2/cart?_fields=fees",
                "GET /wp-json/cocart/v2/cart/coupons/validate",
                "GET /wp-json/cocart/v2/cart/coupons/validate?email=jane%40example.com",
                "DELETE /wp-json/cocart/v2/cart/coupons",
                "POST /wp-json/cocart/v2/cart/remove-fees",
                "GET /wp-json/cocart/v2/cart/mini",
                "DELETE /wp-json/cocart/v2/cart/items"
            ),
            calls()
        )
    }

    @Test
    fun `addItem sends the id and quantity as strings`() = runTest {
        // CoCart rejects a number: "id is not of type string" (rest_invalid_param).
        client().cart().addItem(154, 2.0, mapOf("convert_to_sub" to "1_month"))
        val body = server.last().json()
        assertEquals("154", body["id"])
        assertEquals("2", body["quantity"])
        assertEquals("1_month", body["convert_to_sub"])

        client().cart().addItem(155, 1.5)
        assertEquals("1.5", server.last().json()["quantity"])
    }

    @Test
    fun `applyCoupon sends code to apply-coupon`() = runTest {
        val cart = client().cart()
        cart.applyCoupon("SAVE10")
        assertEquals("POST /wp-json/cocart/v2/cart/apply-coupon", calls().last())
        assertEquals(mapOf("code" to "SAVE10"), server.last().json())

        cart.applyCoupon("VIP", "jane@example.com")
        assertEquals(mapOf("code" to "VIP", "email" to "jane@example.com"), server.last().json())
    }

    @Test
    fun `removeCoupon encodes the code`() = runTest {
        val cart = client().cart()
        listOf("summer sale", "10%off", "a#b?c", "café").forEach { cart.removeCoupon(it) }
        assertEquals(
            listOf(
                "DELETE /wp-json/cocart/v2/cart/coupons/summer%20sale",
                "DELETE /wp-json/cocart/v2/cart/coupons/10%25off",
                "DELETE /wp-json/cocart/v2/cart/coupons/a%23b%3Fc",
                "DELETE /wp-json/cocart/v2/cart/coupons/caf%C3%A9"
            ),
            calls()
        )
    }

    @Test
    fun `addFee sends the amount as a string to add-fee`() = runTest {
        val cart = client().cart()
        cart.addFee("Rush", 5.99, taxable = true)
        assertEquals("POST /wp-json/cocart/v2/cart/add-fee", calls().last())
        assertEquals(mapOf("name" to "Rush", "amount" to "5.99", "taxable" to true), server.last().json())

        cart.addFee("Gift wrap", 2.0, taxable = true, taxClass = "reduced-rate")
        val body = server.last().json()
        assertEquals("2", body["amount"])
        assertEquals("reduced-rate", body["tax_class"])
    }

    @Test
    fun `changePlan posts update-item-schema`() = runTest {
        client().cart().changePlan("abc123", "0")
        assertEquals("POST /wp-json/cocart/v2/cart/update", calls().last())
        assertEquals(
            mapOf("namespace" to "update-item-schema", "item_key" to "abc123", "convert_to_sub" to "0"),
            server.last().json()
        )
    }

    @Test(expected = ValidationException::class)
    fun `changePlan requires an item key`() = runTest {
        client().cart().changePlan("", "0")
    }

    // --- Products ---

    @Test
    fun `product routes`() = runTest {
        val products = client().products()
        products.findBySlug("blue-hoodie")
        products.byCategory("hoodies")
        products.byTag("sale")
        products.brands()
        products.brand(5)
        products.byBrand("nike")
        products.myReviews()
        products.createReview(mapOf("product_id" to 42, "review" to "Great", "rating" to 5))

        assertEquals(
            listOf(
                "GET /wp-json/cocart/v2/products/blue-hoodie",
                "GET /wp-json/cocart/v2/products?category=hoodies",
                "GET /wp-json/cocart/v2/products?tag=sale",
                "GET /wp-json/cocart/v2/products/brands",
                "GET /wp-json/cocart/v2/products/brands/5",
                "GET /wp-json/cocart/v2/products?brand=nike",
                "GET /wp-json/cocart/v2/products/reviews/mine",
                "POST /wp-json/cocart/v2/products/reviews"
            ),
            calls()
        )
        assertEquals(42, server.last().json()["product_id"])
    }

    @Test
    fun `catalogue routes`() = runTest {
        val products = client().products()
        products.find(154, mapOf("_fields" to "id,name"))
        products.variations(134)
        products.variation(134, 157)
        products.categories(mapOf("per_page" to "50"))
        products.category(20)
        products.tags()
        products.tag(8)
        products.attributes()
        products.attribute(1)
        products.attributeTerms(1)
        products.attributeTerm(1, 5)
        products.attributeBySlug("color")
        products.attributeTermsBySlug("color")
        products.attributeTermBySlug("color", "blue")
        products.reviews()
        products.productReviews(154, mapOf("per_page" to "5"))
        products.seo(154)
        products.seoBySlug("beanie-with-logo")

        val base = "/wp-json/cocart/v2/products"
        assertEquals(
            listOf(
                "GET $base/154?_fields=id%2Cname",
                "GET $base/134/variations",
                "GET $base/134/variations/157",
                "GET $base/categories?per_page=50",
                "GET $base/categories/20",
                "GET $base/tags",
                "GET $base/tags/8",
                "GET $base/attributes",
                "GET $base/attributes/1",
                "GET $base/attributes/1/terms",
                "GET $base/attributes/1/terms/5",
                "GET $base/attributes/color",
                "GET $base/attributes/color/terms",
                "GET $base/attributes/color/terms/blue",
                "GET $base/reviews",
                "GET $base/reviews?per_page=5&product=154",
                "GET $base/154/seo",
                "GET $base/beanie-with-logo/seo"
            ),
            calls()
        )
    }

    @Test
    fun `listing shortcuts add their filter`() = runTest {
        val products = client().products()
        products.search("hoodie", mapOf("per_page" to "4"))
        products.featured()
        products.onSale()
        products.byPriceRange(minPrice = "10", maxPrice = "50")
        products.sortBy(ProductOrderBy.PRICE_DESC, SortOrder.DESC)
        products.byStockStatus(StockStatus.IN_STOCK)
        products.paginate(2, 12)

        val base = "/wp-json/cocart/v2/products"
        assertEquals(
            listOf(
                "GET $base?per_page=4&search=hoodie",
                "GET $base?featured=true",
                "GET $base?on_sale=true",
                "GET $base?min_price=10&max_price=50",
                "GET $base?orderby=price_desc&order=desc",
                "GET $base?stock_status=instock",
                "GET $base?page=2&per_page=12"
            ),
            calls()
        )
    }

    @Test
    fun `starter-only methods throw in legacy mode before any request`() = runTest {
        val products = client(CoCartOptions(mainPlugin = MainPlugin.LEGACY)).products()
        val starterOnly: List<suspend () -> Any> = listOf(
            { products.findBySlug("x") },
            { products.variation(1, 2) },
            { products.category(1) },
            { products.tag(1) },
            { products.attributeBySlug("color") },
            { products.attributeTermsBySlug("color") },
            { products.attributeTermBySlug("color", "blue") },
            { products.brands() },
            { products.brand(1) },
            { products.byBrand("nike") },
            { products.myReviews() }
        )
        starterOnly.forEachIndexed { i, call ->
            try {
                call()
                fail("Call $i should throw VersionException")
            } catch (e: com.cocart.sdk.errors.VersionException) {
            }
        }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `shippingRates is sent without the cart key`() = runTest {
        // The route prices a simulated cart and refuses a cart key (403).
        val c = client(CoCartOptions(cartKey = "t_guest_key"))
        c.products().shippingRates(154, quantity = 2, variation = mapOf("attribute_pa_color" to "blue"))
        val req = server.last()
        assertEquals("/wp-json/cocart/v2/products/154/shipping-rates", req.path)
        assertEquals("quantity=2&variation%5Battribute_pa_color%5D=blue", req.query)
        assertNull(req.header("Cart-Key"))

        // Other requests keep the cart key.
        c.products().find(154)
        assertTrue(server.last().query!!.contains("cart_key=t_guest_key"))
        assertEquals("t_guest_key", server.last().header("Cart-Key"))
    }

    @Test
    fun `shippingRates encodes a SKU and follows the namespace`() = runTest {
        client(CoCartOptions(namespace = "mystore")).products().shippingRates("HOODIE/BLUE")
        assertEquals("/wp-json/mystore/v2/products/HOODIE%2FBLUE/shipping-rates", server.last().path)
    }

    // --- Sessions ---

    @Test
    fun `session routes`() = runTest {
        val sessions = client(CoCartOptions(consumerKey = "ck_x", consumerSecret = "cs_x")).sessions()
        sessions.all()
        sessions.get("t_abc")
        sessions.getItems("t_abc")
        sessions.delete("t_abc")
        assertEquals(
            listOf(
                "GET /wp-json/cocart/v2/sessions",
                "GET /wp-json/cocart/v2/session/t_abc",
                "GET /wp-json/cocart/v2/session/t_abc/items",
                "DELETE /wp-json/cocart/v2/session/t_abc"
            ),
            calls()
        )
    }

    // --- Account ---

    @Test
    fun `account routes`() = runTest {
        val account = client(CoCartOptions(username = "user", password = "pass")).account()
        account.updateAddress(mapOf("billing_address" to mapOf("city" to "Paris")))
        account.cancelOrder(42)
        account.verifyEmail("k3y", 7)
        account.sendOrderEmailVerification()
        account.verifyOrderEmail("k3y", 7)
        account.getReviews()
        account.getSubscriptions(mapOf("page" to 2, "status" to listOf("active", "on-hold")))
        account.getSubscription(9)
        account.getSubscription(9, "guest@example.com")
        account.suspendSubscription(9)
        account.reactivateSubscription(9)
        account.cancelSubscription(9)
        account.setSubscriptionAutoRenew(9, false)
        account.updateSubscriptionPaymentMethod(9, mapOf("payment_method" to "stripe"))
        account.resubscribe(9)
        account.cancelResubscribe(9)

        val base = "/wp-json/cocart/v2/my-account"
        assertEquals(
            listOf(
                "POST $base/address",
                "POST $base/orders/42/cancel",
                "GET $base/verify-email?key=k3y&user_id=7",
                "POST $base/verify-order-email",
                "GET $base/verify-order-email?key=k3y&user_id=7",
                "GET /wp-json/cocart/v2/products/reviews/mine",
                "GET $base/subscriptions?page=2&status=active%2Con-hold",
                "GET $base/subscriptions/9",
                "GET $base/subscriptions/9?email=guest%40example.com",
                "POST $base/subscriptions/9/suspend",
                "POST $base/subscriptions/9/reactivate",
                "POST $base/subscriptions/9/cancel",
                "POST $base/subscriptions/9/auto-renew",
                "POST $base/subscriptions/9/payment-method",
                "POST $base/subscriptions/9/resubscribe",
                "DELETE $base/subscriptions/9/resubscribe"
            ),
            calls()
        )
        val autoRenew = server.requests.first { it.path.endsWith("/auto-renew") }
        assertEquals(false, autoRenew.json()["enabled"])
    }

    @Test
    fun `changePassword posts only the password fields to my-account, in one request`() = runTest {
        client(CoCartOptions(username = "user", password = "pass")).account().changePassword("old", "new", "new")

        assertEquals(listOf("POST /wp-json/cocart/v2/my-account"), calls())
        assertEquals(
            mapOf("password_current" to "old", "password_1" to "new", "password_2" to "new"),
            server.last().json()
        )
    }

    @Test
    fun `order downloads come from the order's downloads route, as sent`() = runTest {
        server.enqueue(200, """{"downloads":[{"download_id":"abc","download_name":"Guide"}]}""")
        val downloads = client(CoCartOptions(username = "user", password = "pass")).account().getOrderDownloads(42)
        assertEquals("GET /wp-json/cocart/v2/my-account/orders/42/downloads", calls().last())
        assertEquals(mapOf("downloads" to listOf(mapOf("download_id" to "abc", "download_name" to "Guide"))), downloads.toMap())

        server.enqueue(200, """{"downloads":[]}""")
        val guest = client().account().getGuestOrderDownloads(42, "guest@example.com")
        assertEquals("GET /wp-json/cocart/v2/my-account/orders/42/downloads?email=guest%40example.com", calls().last())
        assertEquals(mapOf("downloads" to emptyList<Any>()), guest.toMap())
    }

    @Test
    fun `getOrders sends no query without params`() = runTest {
        client(CoCartOptions(username = "user", password = "pass")).account().getOrders()
        assertEquals("GET /wp-json/cocart/v2/my-account/orders", calls().last())
    }

    @Test
    fun `getOrders sends the filters, joining a list of statuses and meta keys with commas`() = runTest {
        client(CoCartOptions(username = "user", password = "pass")).account().getOrders(
            mapOf(
                "status" to listOf("processing", "on-hold"),
                "after" to "2026-01-01T00:00:00",
                "before" to "2026-12-31T23:59:59",
                "subscription_order_type" to "renewal",
                "formatted" to true,
                "meta_keys" to listOf("gift_message", "_configuration"),
                "page" to null
            )
        )
        val query = server.last().query.orEmpty().split('&').associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals(
            mapOf(
                "status" to "processing,on-hold",
                "after" to "2026-01-01T00:00:00",
                "before" to "2026-12-31T23:59:59",
                "subscription_order_type" to "renewal",
                "formatted" to "true",
                "meta_keys" to "gift_message,_configuration"
            ),
            query
        )
    }

    @Test
    fun `formatted and meta_keys reach the profile, order and subscription reads`() = runTest {
        val account = client(CoCartOptions(username = "user", password = "pass")).account()
        val params = mapOf("formatted" to false, "meta_keys" to listOf("a", "b"))
        account.getProfile(params)
        account.getOrder(42, params)
        account.getGuestOrder(42, "guest@example.com", params)
        account.getSubscription(9, null, params)
        account.getSubscription(9, "guest@example.com", params)

        val base = "/wp-json/cocart/v2/my-account"
        assertEquals(
            listOf(
                "GET $base?formatted=false&meta_keys=a%2Cb",
                "GET $base/orders/42?formatted=false&meta_keys=a%2Cb",
                "GET $base/orders/42?formatted=false&meta_keys=a%2Cb&email=guest%40example.com",
                "GET $base/subscriptions/9?formatted=false&meta_keys=a%2Cb",
                "GET $base/subscriptions/9?formatted=false&meta_keys=a%2Cb&email=guest%40example.com"
            ),
            calls()
        )
    }

    @Test
    fun `updateSubscriptionPaymentMethod reads the subscription's result`() = runTest {
        server.enqueue(
            200,
            """{"object":"subscription","id":9,"result":{"code":"payment_method_changed","message":"Updated.","action":null,"redirect_url":null,"error":null}}"""
        )
        val result = client(CoCartOptions(username = "user", password = "pass")).account()
            .updateSubscriptionPaymentMethod(9, mapOf("payment_method" to "stripe", "token_id" to 3))

        assertEquals(mapOf("payment_method" to "stripe", "token_id" to 3), server.last().json())
        assertTrue(result.isComplete)
        assertEquals("subscription", result.objectType)
        assertEquals(9, result.subscriptionId)
        assertNull(result.orderId)
    }

    @Test
    fun `register sends requested_username and requested_password`() = runTest {
        // Not username/password: CoCart would treat those as a Basic Auth attempt.
        client().account().register("jane@example.com", "jane", "a-strong-password")
        val req = server.last()
        assertEquals("POST /wp-json/cocart/v2/register", calls().last())
        assertEquals(
            mapOf("email" to "jane@example.com", "requested_username" to "jane", "requested_password" to "a-strong-password"),
            req.json()
        )
        assertNull(req.header("Authorization"))
    }

    @Test
    fun `requestOrderWithdrawal posts to order-withdrawal`() = runTest {
        client().account().requestOrderWithdrawal(mapOf("order_number" to "1042"))
        assertEquals("POST /wp-json/cocart/v2/order-withdrawal", calls().last())
    }

    // --- JWT ---

    @Test
    fun `login posts to login and keeps the tokens from extras`() = runTest {
        server.enqueue(200, """{"user_id":"1","extras":{"jwt_token":"eyJ.a.b","jwt_refresh":"refresh123"}}""")
        val c = client()
        c.login("jane", "secret")

        assertEquals("POST /wp-json/cocart/v2/login", calls().last())
        assertEquals(mapOf("username" to "jane", "password" to "secret"), server.last().json())
        assertTrue(c.isAuthenticated())

        c.cart().get()
        assertEquals("Bearer eyJ.a.b", server.last().header("Authorization"))
    }

    @Test
    fun `login throws TwoFactorAuthRequiredException for a 2FA challenge`() = runTest {
        server.enqueue(
            401,
            """{"code":"cocart_2fa_required","message":"Enter your code.","data":{"status":401,"available_providers":["totp","email"],"default_provider":"totp","email_sent":false}}"""
        )
        try {
            client().login("jane", "secret")
            fail("Expected TwoFactorAuthRequiredException")
        } catch (e: TwoFactorAuthRequiredException) {
            assertEquals(listOf("totp", "email"), e.availableProviders)
            assertEquals("totp", e.defaultProvider)
        }
    }

    @Test
    fun `loginWith2fa posts the code to login`() = runTest {
        server.enqueue(200, """{"extras":{"jwt_token":"eyJ.a.b"}}""")
        client().jwt().loginWith2fa("jane", "secret", "123456", "totp")
        assertEquals("POST /wp-json/cocart/v2/login", calls().last())
        assertEquals(
            mapOf("username" to "jane", "password" to "secret", "2fa_code" to "123456", "2fa_provider" to "totp"),
            server.last().json()
        )
    }

    @Test
    fun `refresh and validate use the JWT plugin routes`() = runTest {
        server.enqueue(200, """{"token":"eyJ.new.token","refresh_token":"refresh456"}""")
        val c = client(CoCartOptions(jwtToken = "eyJ.old.token", jwtRefreshToken = "refresh123"))
        c.jwt().refresh()
        assertEquals("POST /wp-json/cocart/jwt/refresh-token", calls().last())
        assertEquals(mapOf("refresh_token" to "refresh123"), server.last().json())

        c.jwt().validate()
        assertEquals("POST /wp-json/cocart/jwt/validate-token", calls().last())
        assertEquals("Bearer eyJ.new.token", server.last().header("Authorization"))
    }

    @Test
    fun `logout tells the server`() = runTest {
        client(CoCartOptions(jwtToken = "eyJ.a.b")).logout()
        assertEquals("POST /wp-json/cocart/v2/logout", calls().last())
    }

    @Suppress("unused")
    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { get(it) }
}
