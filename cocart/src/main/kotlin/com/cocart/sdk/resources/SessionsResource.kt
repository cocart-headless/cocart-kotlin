package com.cocart.sdk.resources

import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient

/**
 * Cart sessions, for administrators (WooCommerce consumer keys). The list is
 * `sessions`; a single session is `session/{session_key}`.
 */
class SessionsResource(
    private val http: HttpClient,
    private val auth: AuthManager
) {
    suspend fun all(params: Map<String, String>? = null): CoCartResponse = http.get("sessions", params)

    suspend fun get(sessionKey: String): CoCartResponse = http.get("session/$sessionKey")

    /** The items in a cart session. */
    suspend fun getItems(sessionKey: String): CoCartResponse = http.get("session/$sessionKey/items")

    suspend fun delete(sessionKey: String): CoCartResponse = http.delete("session/$sessionKey")
}
