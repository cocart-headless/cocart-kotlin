package com.cocart.sdk.http

import org.junit.Assert.*
import org.junit.Test

class CoCartResponseTest {
    private fun response(
        data: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        status: Int = 200
    ) = CoCartResponse(data, headers, status)

    @Test
    fun `get returns top-level value`() {
        val r = response(mapOf("total" to "100.00"))
        assertEquals("100.00", r.get("total"))
    }

    @Test
    fun `get traverses dot-notation path`() {
        val r = response(mapOf("totals" to mapOf("total" to "99.99")))
        assertEquals("99.99", r.get("totals.total"))
    }

    @Test
    fun `get returns null for missing path`() {
        val r = response(mapOf("totals" to mapOf("total" to "99.99")))
        assertNull(r.get("totals.missing"))
    }

    @Test
    fun `has returns true for existing path`() {
        val r = response(mapOf("totals" to mapOf("discount" to "5.00")))
        assertTrue(r.has("totals.discount"))
    }

    @Test
    fun `has returns false for missing path`() {
        val r = response(mapOf("totals" to mapOf("total" to "5.00")))
        assertFalse(r.has("totals.discount"))
    }

    @Test
    fun `getItems returns items list`() {
        val items = listOf(mapOf("key" to "abc", "name" to "Widget"))
        val r = response(mapOf("items" to items))
        assertEquals(items, r.getItems())
    }

    @Test
    fun `getItems returns empty list when absent`() {
        assertEquals(emptyList<Any>(), response().getItems())
    }

    @Test
    fun `getItemCount reads items_count`() {
        val r = response(mapOf("items_count" to 3))
        assertEquals(3, r.getItemCount())
    }

    @Test
    fun `getCartKey reads Cart-Key header`() {
        val r = response(headers = mapOf("Cart-Key" to "cart123"))
        assertEquals("cart123", r.getCartKey())
    }

    @Test
    fun `getCartKey falls back to CoCart-API-Cart-Key header`() {
        val r = response(headers = mapOf("CoCart-API-Cart-Key" to "legacy_key"))
        assertEquals("legacy_key", r.getCartKey())
    }

    @Test
    fun `getCartKey falls back to cart_key in body`() {
        val r = response(data = mapOf("cart_key" to "body_key"))
        assertEquals("body_key", r.getCartKey())
    }

    @Test
    fun `getCacheStatus reads CoCart-Cache header`() {
        val r = response(headers = mapOf("CoCart-Cache" to "HIT"))
        assertEquals("HIT", r.getCacheStatus())
    }

    @Test
    fun `isNotModified true for 304`() {
        assertTrue(response(status = 304).isNotModified())
    }

    @Test
    fun `isNotModified false for 200`() {
        assertFalse(response(status = 200).isNotModified())
    }

    @Test
    fun `toMap returns data copy`() {
        val data = mapOf("key" to "value")
        assertEquals(data, response(data).toMap())
    }

    @Test
    fun `get traverses list by index`() {
        val r = response(mapOf("items" to listOf(mapOf("name" to "first"), mapOf("name" to "second"))))
        assertEquals("second", r.get("items.1.name"))
    }

    @Test
    fun `getTaxes returns array taxes as-is`() {
        val taxes = listOf(mapOf("key" to "US-US-1", "name" to "State Tax", "price" to "5.00"))
        val r = response(mapOf("taxes" to taxes))
        assertEquals(taxes, r.getTaxes())
    }

    @Test
    fun `getTaxes normalizes legacy object keyed by tax rate code`() {
        val r = response(
            mapOf(
                "taxes" to mapOf(
                    "US-US-1" to mapOf("name" to "State Tax", "price" to "5.00")
                )
            )
        )
        val taxes = r.getTaxes()
        assertEquals(1, taxes.size)
        assertEquals("US-US-1", taxes[0]["key"])
        assertEquals("State Tax", taxes[0]["name"])
        assertEquals("5.00", taxes[0]["price"])
    }

    @Test
    fun `getTaxes returns empty list when taxes absent`() {
        assertEquals(emptyList<Any>(), response().getTaxes())
    }

    @Test
    fun `hasTaxes is true when taxes present`() {
        val r = response(mapOf("taxes" to listOf(mapOf("key" to "US-US-1"))))
        assertTrue(r.hasTaxes())
    }

    @Test
    fun `hasTaxes is false when taxes empty or absent`() {
        assertFalse(response().hasTaxes())
        assertFalse(response(mapOf("taxes" to emptyList<Any>())).hasTaxes())
        assertFalse(response(mapOf("taxes" to emptyMap<String, Any>())).hasTaxes())
    }
}
