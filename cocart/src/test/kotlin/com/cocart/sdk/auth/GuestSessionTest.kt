package com.cocart.sdk.auth

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuestSessionTest {
    @Test
    fun `pre-seeded cart key is available immediately`() {
        val auth = AuthManager(CoCartOptions(cartKey = "preset_key"), MemoryStorage())
        assertEquals("preset_key", auth.cartKey)
        assertEquals("preset_key", auth.cartKeyValue)
    }

    @Test
    fun `cart key captured from response body`() = runTest {
        val auth = AuthManager(CoCartOptions(), MemoryStorage())
        auth.captureCartKey(mapOf("cart_key" to "body_key"), emptyMap())
        assertEquals("body_key", auth.cartKey)
    }

    @Test
    fun `cart key captured from Cart-Key header`() = runTest {
        val auth = AuthManager(CoCartOptions(), MemoryStorage())
        auth.captureCartKey(emptyMap(), mapOf("Cart-Key" to "header_key"))
        assertEquals("header_key", auth.cartKey)
    }

    @Test
    fun `body cart key takes priority over headers`() = runTest {
        val auth = AuthManager(CoCartOptions(), MemoryStorage())
        auth.captureCartKey(
            mapOf("cart_key" to "body_key"),
            mapOf("Cart-Key" to "header_key")
        )
        assertEquals("body_key", auth.cartKey)
    }

    @Test
    fun `restoring session when no stored key leaves cartKey null`() = runTest {
        val auth = AuthManager(CoCartOptions(), MemoryStorage())
        auth.restoreSession()
        assertNull(auth.cartKey)
    }

    @Test
    fun `restoring session does not overwrite pre-seeded key`() = runTest {
        val storage = MemoryStorage()
        storage.set("cocart_cart_key", "stored_key")
        val auth = AuthManager(CoCartOptions(cartKey = "preset_key"), storage)
        auth.restoreSession()
        assertEquals("preset_key", auth.cartKey)
    }
}
