package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.mocks.buildAuthManager
import com.cocart.sdk.mocks.mockResponse
import io.mockk.coEvery
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
    fun `add is alias for addItem`() = runTest {
        val http = mockk<HttpClient>()
        val response = mockResponse(mapOf("items_count" to 1))
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
    fun `getFiltered uses _fields param in BASIC mode`() = runTest {
        val http = mockk<HttpClient>()
        coEvery { http.get("cart", mapOf("_fields" to "items,totals")) } returns mockResponse()
        val cart = buildCart(http, CoCartOptions(mainPlugin = MainPlugin.BASIC))
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
}
