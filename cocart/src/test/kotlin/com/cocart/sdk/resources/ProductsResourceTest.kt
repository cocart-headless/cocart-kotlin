package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.errors.ValidationException
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.mocks.mockResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProductsResourceTest {
    private fun buildProducts(
        http: HttpClient = mockk(),
        options: CoCartOptions = CoCartOptions()
    ) = ProductsResource(http, options)

    @Test(expected = ValidationException::class)
    fun `find throws for invalid product id`() = runTest {
        buildProducts().find(0)
    }

    @Test(expected = ValidationException::class)
    fun `variation throws for invalid product id`() = runTest {
        buildProducts().variation(0, 10)
    }

    @Test(expected = ValidationException::class)
    fun `variation throws for invalid variation id`() = runTest {
        buildProducts().variation(10, 0)
    }

    @Test(expected = VersionException::class)
    fun `findBySlug throws in LEGACY mode`() = runTest {
        buildProducts(options = CoCartOptions(mainPlugin = MainPlugin.LEGACY))
            .findBySlug("my-product")
    }

    @Test
    fun `all passes filters as query params`() = runTest {
        val http = mockk<HttpClient>()
        val filters = mapOf("category" to "shoes")
        coEvery { http.get("products", filters) } returns mockResponse(mapOf("products" to emptyList<Any>()))
        val result = buildProducts(http).all(filters)
        assertNotNull(result)
    }
}
