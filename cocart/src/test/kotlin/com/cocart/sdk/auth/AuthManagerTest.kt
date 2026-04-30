package com.cocart.sdk.auth

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.storage.MemoryStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthManagerTest {
    private lateinit var storage: MemoryStorage

    @Before
    fun setup() {
        storage = MemoryStorage()
    }

    @Test
    fun `default mode is GUEST`() {
        val auth = AuthManager(CoCartOptions(), storage)
        assertTrue(auth.isGuest)
        assertFalse(auth.isAuthenticated)
        assertNull(auth.buildAuthHeader())
    }

    @Test
    fun `JWT token in options sets JWT mode`() {
        val auth = AuthManager(CoCartOptions(jwtToken = "test.jwt.token"), storage)
        assertFalse(auth.isGuest)
        assertTrue(auth.isAuthenticated)
        assertEquals("Bearer test.jwt.token", auth.buildAuthHeader())
    }

    @Test
    fun `username and password in options sets BASIC_AUTH mode`() {
        val auth = AuthManager(
            CoCartOptions(username = "user@example.com", password = "secret"),
            storage
        )
        assertFalse(auth.isGuest)
        assertTrue(auth.isAuthenticated)
        val header = auth.buildAuthHeader()
        assertNotNull(header)
        assertTrue(header!!.startsWith("Basic "))
    }

    @Test
    fun `setBasicAuth switches to BASIC_AUTH`() {
        val auth = AuthManager(CoCartOptions(), storage)
        assertTrue(auth.isGuest)
        auth.setBasicAuth("user@example.com", "pass")
        assertFalse(auth.isGuest)
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun `setJwtToken switches to JWT mode and clears basic auth`() {
        val auth = AuthManager(
            CoCartOptions(username = "user@example.com", password = "pass"),
            storage
        )
        auth.setJwtToken("new.jwt.token")
        assertEquals("Bearer new.jwt.token", auth.buildAuthHeader())
    }

    @Test
    fun `captureCartKey stores key for GUEST mode`() = runTest {
        val auth = AuthManager(CoCartOptions(), storage)
        auth.captureCartKey(
            mapOf("cart_key" to "abc123"),
            emptyMap()
        )
        assertEquals("abc123", storage.get("cocart_cart_key"))
    }

    @Test
    fun `captureCartKey ignores key when not in GUEST mode`() = runTest {
        val auth = AuthManager(CoCartOptions(jwtToken = "tok"), storage)
        auth.captureCartKey(mapOf("cart_key" to "abc123"), emptyMap())
        assertNull(storage.get("cocart_cart_key"))
    }

    @Test
    fun `restoreSession loads cart key from storage`() = runTest {
        storage.set("cocart_cart_key", "restored_key")
        val auth = AuthManager(CoCartOptions(), storage)
        auth.restoreSession()
        assertEquals("restored_key", auth.cartKey)
    }

    @Test
    fun `clearSession resets everything`() = runTest {
        storage.set("cocart_cart_key", "some_key")
        val auth = AuthManager(CoCartOptions(jwtToken = "tok"), storage)
        auth.clearSession()
        assertTrue(auth.isGuest)
        assertNull(auth.cartKey)
        assertNull(storage.get("cocart_cart_key"))
    }

    @Test
    fun `cartKeyValue only returns key in GUEST mode`() {
        val auth = AuthManager(CoCartOptions(cartKey = "mykey"), storage)
        assertEquals("mykey", auth.cartKeyValue)
        auth.setBasicAuth("u", "p")
        assertNull(auth.cartKeyValue)
    }

    @Test
    fun `cart key header fallback from CoCart-API-Cart-Key`() = runTest {
        val auth = AuthManager(CoCartOptions(), storage)
        auth.captureCartKey(
            emptyMap(),
            mapOf("CoCart-API-Cart-Key" to "fallback_key")
        )
        assertEquals("fallback_key", storage.get("cocart_cart_key"))
    }
}
