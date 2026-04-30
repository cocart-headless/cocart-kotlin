package com.cocart.sdk.resources

import android.util.Base64
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.AuthException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.TwoFactorAuthRequiredException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class JwtResource(
    private val http: HttpClient,
    private val auth: AuthManager
) {
    private var autoRefreshEnabled = false
    private val refreshMutex = Mutex()

    suspend fun login(username: String, password: String): CoCartResponse {
        val response = http.post(
            "jwt/token",
            mapOf("username" to username, "password" to password)
        )

        val code = response.get("code") as? String
        if (code == "cocart_2fa_required") {
            val data = response.get("data") as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val providers = (data?.get("available_providers") as? List<String>) ?: emptyList()
            val defaultProvider = data?.get("default_provider") as? String
            val emailSent = data?.get("email_sent") as? Boolean ?: false
            throw TwoFactorAuthRequiredException(
                response.get("message") as? String ?: "Two-factor authentication required",
                providers,
                defaultProvider,
                emailSent
            )
        }

        val token = response.get("token") as? String
        val refreshToken = response.get("refresh_token") as? String
        if (token != null) {
            auth.setJwtTokens(token, refreshToken)
        }
        return response
    }

    suspend fun loginWith2fa(
        username: String,
        password: String,
        code: String,
        provider: String? = null
    ): CoCartResponse {
        val body = mutableMapOf<String, Any?>(
            "username" to username,
            "password" to password,
            "2fa_code" to code
        )
        provider?.let { body["2fa_provider"] = it }

        val response = http.post("jwt/token/2fa", body)

        val token = response.get("token") as? String
        val refreshToken = response.get("refresh_token") as? String
        if (token != null) {
            auth.setJwtTokens(token, refreshToken)
        }
        return response
    }

    suspend fun refresh(): CoCartResponse = refreshMutex.withLock {
        val refreshToken = auth.getRefreshToken()
            ?: throw AuthException("No refresh token available")

        val response = http.post("jwt/token/refresh", mapOf("refresh_token" to refreshToken))

        val token = response.get("token") as? String
        if (token != null) {
            auth.setJwtToken(token)
        }
        response
    }

    suspend fun validate(): CoCartResponse {
        return http.post("jwt/token/validate")
    }

    fun isTokenExpired(leeway: Long = 0): Boolean {
        val expiry = getTokenExpiry() ?: return true
        val nowSeconds = System.currentTimeMillis() / 1000
        return nowSeconds >= (expiry - leeway)
    }

    fun getTokenExpiry(): Long? {
        val token = auth.getJwtToken() ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
            val json = JSONObject(payload)
            json.optLong("exp").takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    fun setAutoRefresh(enabled: Boolean) {
        autoRefreshEnabled = enabled
    }

    fun isAutoRefreshEnabled(): Boolean = autoRefreshEnabled

    fun hasTokens(): Boolean = auth.getJwtToken() != null

    suspend fun restoreTokensFromStorage() {
        auth.restoreSession()
    }

    suspend fun <T> withAutoRefresh(block: suspend () -> T): T {
        if (!autoRefreshEnabled || !hasTokens()) return block()

        return try {
            if (isTokenExpired(leeway = 300)) {
                try {
                    refresh()
                } catch (e: TwoFactorAuthRequiredException) {
                    throw e
                } catch (e: CoCartException) {
                    // Refresh failed — proceed anyway and let the block fail
                }
            }
            block()
        } catch (e: AuthException) {
            if (e !is TwoFactorAuthRequiredException) {
                try {
                    refresh()
                } catch (refreshEx: TwoFactorAuthRequiredException) {
                    throw refreshEx
                } catch (refreshEx: CoCartException) {
                    throw e
                }
                block()
            } else {
                throw e
            }
        }
    }

    suspend fun logout() {
        auth.clearSession()
    }
}
