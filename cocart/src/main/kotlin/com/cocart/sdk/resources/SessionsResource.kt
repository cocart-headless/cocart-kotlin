package com.cocart.sdk.resources

import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient

class SessionsResource(
    private val http: HttpClient,
    private val auth: AuthManager
) {
    suspend fun all(): CoCartResponse = http.get("sessions")

    suspend fun get(sessionKey: String): CoCartResponse = http.get("sessions/$sessionKey")

    suspend fun delete(sessionKey: String): CoCartResponse = http.delete("sessions/$sessionKey")
}
