package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.BatchRequestItem
import com.cocart.sdk.mocks.buildAuthManager
import com.cocart.sdk.mocks.mockResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import com.cocart.sdk.http.HttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CartResourceTest {
    private fun buildCart(
        http: HttpClient = mockk(),
        options: CoCartOptions = CoCartOptions()
    ) = CartResource(http, buildAuthManager(options), options)

    @Test(expected = ValidationException::class)
    fun `addItem throws for invalid product id`() = runTest {
        buildCart().addItem(0, 1.0)
    }

    @Test(expected = ValidationException::class)
    fun `addItem throws for zero quantity`() = runTest {
        buildCart().addItem(1, 0.0)
    }

    @Test(expected = ValidationException::class)
    fun `addItem throws for negative quantity`() = runTest {
        buildCart().addItem(1, -1.0)
    }

    @Test
    fun `addItem accepts a SKU`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post("cart/add-item", mapOf("id" to "BLUE-SHIRT-L", "quantity" to "1"))
        } returns mockResponse(mapOf("item_key" to "abc"))
        val cart = buildCart(http)

        cart.addItem("BLUE-SHIRT-L", 1.0)
    }

    @Test
    fun `addVariation accepts a SKU`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post(
                "cart/add-item",
                mapOf("id" to "VAR-SKU-1", "quantity" to "1", "variation" to mapOf("attribute_pa_color" to "blue"))
            )
        } returns mockResponse(mapOf("item_key" to "abc"))
        val cart = buildCart(http)

        cart.addVariation("VAR-SKU-1", 1.0, mapOf("attribute_pa_color" to "blue"))
    }

    @Test
    fun `add is alias for addItem`() = runTest {
        val http = mockk<HttpClient>()
        val response = mockResponse(mapOf("item_count" to 1))
        coEvery { http.post(any(), any(), any()) } returns response
        val cart = buildCart(http)
        val result = cart.add(10, 1.0)
        assertEquals(1, result.getItemCount())
    }

    @Test(expected = VersionException::class)
    fun `getFees throws in LEGACY mode`() = runTest {
        buildCart(options = CoCartOptions(mainPlugin = MainPlugin.LEGACY)).getFees()
    }

    @Test(expected = VersionException::class)
    fun `addFee throws in LEGACY mode`() = runTest {
        buildCart(options = CoCartOptions(mainPlugin = MainPlugin.LEGACY))
            .addFee("handling", 5.0)
    }

    @Test(expected = VersionException::class)
    fun `removeFees throws in LEGACY mode`() = runTest {
        buildCart(options = CoCartOptions(mainPlugin = MainPlugin.LEGACY)).removeFees()
    }

    @Test
    fun `getFiltered uses _fields param in STARTER mode`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.get("cart", mapOf("_fields" to "items,totals")) } returns mockResponse()
        val cart = buildCart(http, CoCartOptions(mainPlugin = MainPlugin.STARTER))
        cart.getFiltered(listOf("items", "totals"))
    }

    @Test
    fun `getFiltered uses fields param in LEGACY mode`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.get("cart", mapOf("fields" to "items,totals")) } returns mockResponse()
        val cart = buildCart(http, CoCartOptions(mainPlugin = MainPlugin.LEGACY))
        cart.getFiltered(listOf("items", "totals"))
    }

    @Test
    fun `empty is alias for clear`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.post("cart/clear", null, null) } returns mockResponse()
        val cart = buildCart(http)
        cart.empty()
    }

    // --- addItems (grouped-product children) ---

    @Test
    fun `addItems posts grouped product id and child quantities map`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post("cart/add-items", mapOf("id" to "10", "quantity" to mapOf("11" to "2", "12" to "1")))
        } returns mockResponse()
        val cart = buildCart(http)
        cart.addItems(10, mapOf("11" to 2, "12" to 1))
    }

    @Test
    fun `addItems accepts list of id-quantity entries`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post("cart/add-items", mapOf("id" to "10", "quantity" to mapOf("11" to "2")))
        } returns mockResponse()
        val cart = buildCart(http)
        cart.addItems(10, listOf(mapOf("id" to 11, "quantity" to 2)))
    }

    @Test(expected = ValidationException::class)
    fun `addItems throws when no items given`() = runTest {
        buildCart().addItems(10, emptyMap())
    }

    @Test
    fun `addItems accepts a SKU for the grouped product id`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post("cart/add-items", mapOf("id" to "GROUPED-SKU", "quantity" to mapOf("11" to "2")))
        } returns mockResponse()
        val cart = buildCart(http)
        cart.addItems("GROUPED-SKU", mapOf("11" to 2))
    }

    // --- updateItems / removeItems (per-item loop) ---

    @Test
    fun `updateItems loops updateItem per entry and returns last response`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.post("cart/item/aaa", mapOf("quantity" to "2")) } returns mockResponse(mapOf("item_count" to 1))
        coEvery { http.post("cart/item/bbb", mapOf("quantity" to "3")) } returns mockResponse(mapOf("item_count" to 2))
        val cart = buildCart(http)

        val result = cart.updateItems(linkedMapOf("aaa" to 2.0, "bbb" to 3.0))

        assertEquals(2, result.getItemCount())
        coVerify(exactly = 1) { http.post("cart/item/aaa", mapOf("quantity" to "2")) }
        coVerify(exactly = 1) { http.post("cart/item/bbb", mapOf("quantity" to "3")) }
    }

    @Test(expected = ValidationException::class)
    fun `updateItems throws when no items given`() = runTest {
        buildCart().updateItems(emptyMap())
    }

    @Test
    fun `removeItems loops removeItem per key and returns last response`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.delete("cart/item/aaa", null) } returns mockResponse(mapOf("item_count" to 1))
        coEvery { http.delete("cart/item/bbb", null) } returns mockResponse(mapOf("item_count" to 0))
        val cart = buildCart(http)

        val result = cart.removeItems(listOf("aaa", "bbb"))

        assertEquals(0, result.getItemCount())
        coVerify(exactly = 1) { http.delete("cart/item/aaa", null) }
        coVerify(exactly = 1) { http.delete("cart/item/bbb", null) }
    }

    @Test(expected = ValidationException::class)
    fun `removeItems throws when no keys given`() = runTest {
        buildCart().removeItems(emptyList())
    }

    // --- batchUpdateItems / batchRemoveItems ---

    @Test
    fun `batchUpdateItems builds batch requests and calls client batch`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.batch(any()) } returns mockResponse()
        val cart = buildCart(http)

        cart.batchUpdateItems(linkedMapOf("aaa" to 2.0))

        coVerify {
            http.batch(
                listOf(
                    BatchRequestItem(
                        method = "POST",
                        path = "/cocart/v2/cart/item/aaa",
                        body = mapOf("quantity" to "2")
                    )
                )
            )
        }
    }

    @Test
    fun `batchRemoveItems builds batch requests and calls client batch`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.batch(any()) } returns mockResponse()
        val cart = buildCart(http)

        cart.batchRemoveItems(listOf("aaa", "bbb"))

        coVerify {
            http.batch(
                listOf(
                    BatchRequestItem(method = "DELETE", path = "/cocart/v2/cart/item/aaa"),
                    BatchRequestItem(method = "DELETE", path = "/cocart/v2/cart/item/bbb")
                )
            )
        }
    }

    // --- updateCustomer ---

    @Test
    fun `updateCustomer mirrors billing into s_ fields when shipping omitted`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post(
                "cart/update",
                mapOf(
                    "namespace" to "update-customer",
                    "first_name" to "Jane",
                    "s_first_name" to "Jane"
                )
            )
        } returns mockResponse()
        val cart = buildCart(http)

        cart.updateCustomer(mapOf("first_name" to "Jane"))
    }

    @Test
    fun `updateCustomer sends s_ prefixed shipping and ship_to_different_address when distinct`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post(
                "cart/update",
                mapOf(
                    "namespace" to "update-customer",
                    "first_name" to "Jane",
                    "s_first_name" to "John",
                    "ship_to_different_address" to true
                )
            )
        } returns mockResponse()
        val cart = buildCart(http)

        cart.updateCustomer(mapOf("first_name" to "Jane"), mapOf("first_name" to "John"))
    }

    // --- setShippingMethod ---

    @Test
    fun `setShippingMethod posts rate_id to set-shipping-method`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.post("cart/set-shipping-method", mapOf("rate_id" to "flat_rate:2")) } returns mockResponse()
        val cart = buildCart(http)

        cart.setShippingMethod("flat_rate:2")
    }

    @Test
    fun `setShippingMethod includes package_id when given`() = runTest {
        val http = mockk<HttpClient>()
        coEvery {
            http.post("cart/set-shipping-method", mapOf("rate_id" to "flat_rate:2", "package_id" to "0"))
        } returns mockResponse()
        val cart = buildCart(http)

        cart.setShippingMethod("flat_rate:2", "0")
    }

    // --- calculateShipping (deprecated) ---

    @Test
    @Suppress("DEPRECATION")
    fun `calculateShipping ignores address and delegates to calculate`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.post("cart/calculate", null, null) } returns mockResponse()
        val cart = buildCart(http)

        cart.calculateShipping(mapOf("country" to "US"))

        coVerify(exactly = 1) { http.post("cart/calculate", null, null) }
    }
}
