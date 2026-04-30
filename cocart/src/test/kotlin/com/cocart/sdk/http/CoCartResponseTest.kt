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
}
