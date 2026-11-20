package com.cocart.sdk

import com.cocart.sdk.errors.CheckoutOutcomeUnknownException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.resources.CheckoutResult
import com.cocart.sdk.resources.CheckoutStatus
import com.cocart.sdk.resources.SavedPaymentMethod
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URLDecoder

/**
 * Replays the shared checkout fixtures (resources/fixtures/checkout, copied from cocart-js's
 * packages/checkout/tests/fixtures): the same request/response data every CoCart SDK runs,
 * so all of them are tested the same way.
 */
@RunWith(RobolectricTestRunner::class)
class CheckoutFixturesTest {

    @Test
    fun `every shared checkout fixture`() = runTest {
        val dir = File(javaClass.classLoader!!.getResource("fixtures/checkout")!!.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertEquals(53, files.size)

        val failures = mutableListOf<String>()
        for (file in files) {
            try {
                run(toKotlin(JSONObject(file.readText())) as Map<*, *>)
            } catch (e: Throwable) {
                failures += "${file.nameWithoutExtension}: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private suspend fun run(fixture: Map<*, *>) {
        val server = RecordingServer()
        try {
            val exchanges = fixture["exchanges"] as List<*>
            for (exchange in exchanges) {
                val response = (exchange as Map<*, *>)["response"] as Map<*, *>
                server.enqueue((response["status"] as Number).toInt(), JSONObject.wrap(response["body"]).toString())
            }

            val client = CoCart(null, server.url)
            var outcome: Any? = null
            var error: CoCartException? = null
            try {
                outcome = call(client, fixture["call"] as Map<*, *>)
            } catch (e: CoCartException) {
                error = e
            }

            val seen = server.requests.map { r ->
                mapOf(
                    "method" to r.method,
                    "path" to r.path.removePrefix("/wp-json/"),
                    "query" to query(r.query),
                    "body" to if (r.body.isEmpty()) null else toKotlin(JSONObject(r.body))
                )
            }
            assertEquals("requests", exchanges.map { (it as Map<*, *>)["request"] }, seen)

            val expect = fixture["expect"] as Map<*, *>
            (expect["error"] as? Map<*, *>)?.let {
                assertEquals("error", it["code"], error?.errorCode())
                // The checkout-outcome-unknown error carries the checkout_id to ask getStatus() about.
                it["checkoutId"]?.let { checkoutId ->
                    assertTrue("error is ${error?.javaClass?.simpleName}", error is CheckoutOutcomeUnknownException)
                    assertEquals("error.checkoutId", checkoutId, (error as CheckoutOutcomeUnknownException).checkoutId)
                }
                return
            }
            if (error != null) throw error

            (expect["result"] as? Map<*, *>)?.let { wanted ->
                val result = outcome as CheckoutResult
                for ((key, value) in wanted) {
                    assertEquals(key.toString(), value, field(result, key.toString()))
                }
            }
            if (expect.containsKey("value") && outcome is CheckoutStatus) {
                val status = mapOf(
                    "checkoutId" to outcome.checkoutId, "code" to outcome.code, "message" to outcome.message,
                    "orderId" to outcome.orderId, "orderKey" to outcome.orderKey, "orderNumber" to outcome.orderNumber,
                    "orderStatus" to outcome.orderStatus
                )
                assertEquals("value", expect["value"], status)
            } else if (expect.containsKey("value")) {
                @Suppress("UNCHECKED_CAST")
                val cards = (outcome as List<SavedPaymentMethod>).map {
                    mapOf(
                        "id" to it.id, "gatewayId" to it.gatewayId, "label" to it.label, "type" to it.type,
                        "expires" to it.expires, "isDefault" to it.isDefault, "supportsSubscriptions" to it.supportsSubscriptions
                    )
                }
                assertEquals("value", expect["value"], cards)
            }
            if (expect.containsKey("responseBody")) {
                val response = (outcome as? CheckoutResult)?.response ?: outcome as CoCartResponse
                assertEquals("responseBody", expect["responseBody"], response.toMap())
            }
        } finally {
            server.shutdown()
        }
    }

    private suspend fun call(client: CoCart, call: Map<*, *>): Any {
        val args = call["args"] as List<*>
        @Suppress("UNCHECKED_CAST")
        fun obj(i: Int) = (args.getOrNull(i) as? Map<String, Any?>) ?: emptyMap()
        fun num(i: Int) = (args[i] as Number).toInt()
        fun str(i: Int) = args[i] as String

        if (call["target"] == "account") {
            return client.account().updateSubscriptionPaymentMethod(num(0), obj(1))
        }

        val checkout = client.checkout()
        return when (call["method"]) {
            // The getCheckout parameters object maps onto get()'s params.
            "getCheckout" -> checkout.get(obj(0).ifEmpty { null })
            "updateCheckout" -> checkout.update(obj(0))
            "placeOrder" -> checkout.placeOrder(obj(0))
            "getCheckoutConfig" -> checkout.getConfig()
            "getCheckoutStatus" -> checkout.getStatus(args[0] as String)
            "getPaymentMethods" -> checkout.getPaymentMethods()
            "getSavedPaymentMethods" -> checkout.getSavedPaymentMethods(args.getOrNull(0) as? String)
            "searchAddresses" -> obj(0).let {
                checkout.searchAddresses(it["query"] as String, it["country"] as? String, it["type"] as? String, it["provider"] as? String)
            }
            "getAddressDetails" -> obj(0).let { checkout.getAddressDetails(it["address_id"] as String, it["provider"] as String) }
            "getOrderReceived" -> checkout.getOrderReceived(num(0), str(1), obj(2).ifEmpty { null })
            "payForOrder" -> checkout.payForOrder(num(0), str(1), obj(2))
            "confirmReturn" -> obj(1).let {
                checkout.confirmReturn(str(0), (it["orderId"] as? Number)?.toInt(), it["orderKey"] as? String)
            }
            else -> error("unknown method ${call["method"]}")
        }
    }

    private fun field(result: CheckoutResult, key: String): Any? = when (key) {
        "code" -> result.code
        "object" -> result.objectType
        "isComplete" -> result.isComplete
        "needsConfirmation" -> result.needsConfirmation
        "needsRedirect" -> result.needsRedirect
        "isAwaitingPayment" -> result.isAwaitingPayment
        "isPending" -> result.isPending
        "isFailed" -> result.isFailed
        "orderId" -> result.orderId
        "orderKey" -> result.orderKey
        "orderNumber" -> result.orderNumber
        "orderStatus" -> result.orderStatus
        "subscriptionId" -> result.subscriptionId
        "redirectUrl" -> result.redirectUrl
        "clientSecret" -> result.clientSecret
        "intentType" -> result.intentType
        "paymentMethodType" -> result.paymentMethodType
        "message" -> result.message
        "errorCode" -> result.errorCode
        "gatewayErrorCode" -> result.gatewayErrorCode
        "gatewayMessage" -> result.gatewayMessage
        "actionType" -> result.actionType
        "actionData" -> result.actionData
        else -> error("unknown result key $key")
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }

    private fun toKotlin(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { toKotlin(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { toKotlin(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
