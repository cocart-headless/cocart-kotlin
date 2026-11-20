package com.cocart.sdk.resources

import android.util.Base64
import com.cocart.sdk.CoCartOptions
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
    private val auth: AuthManager,
    private val options: CoCartOptions = CoCartOptions()
) {
    private var autoRefreshEnabled = false
    private val refreshMutex = Mutex()

    /**
     * Log in with a username (or email) and password to get JWT tokens (`POST login`,
     * with the CoCart JWT Authentication plugin).
     *
     * Throws [TwoFactorAuthRequiredException] when the customer has two-factor
     * authentication on: call [loginWith2fa] with their code.
     */
    suspend fun login(username: String, password: String): CoCartResponse {
        val response = http.post("login", mapOf("username" to username, "password" to password))
        return storeTokens(response)
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

        val response = http.post("login", body)
        return storeTokens(response)
    }

    /** Keep the tokens a login sends under `extras` (`jwt_token`, `jwt_refresh`). */
    private fun storeTokens(response: CoCartResponse): CoCartResponse {
        val token = response.get("extras.jwt_token") as? String
            ?: throw AuthException("The login response has no JWT token. Is the CoCart JWT Authentication plugin active?", 0, "cocart_jwt_missing")
        auth.setJwtTokens(token, response.get("extras.jwt_refresh") as? String)
        return response
    }

    suspend fun refresh(): CoCartResponse = refreshMutex.withLock {
        val refreshToken = auth.getRefreshToken()
            ?: throw AuthException("No refresh token available")

        val response = http.postRaw("${options.namespace}/jwt/refresh-token", mapOf("refresh_token" to refreshToken))

        val token = response.get("token") as? String
        if (token != null) {
            auth.setJwtTokens(token, response.get("refresh_token") as? String ?: refreshToken)
        }
        response
    }

    /** Check the JWT token with the server (`POST {namespace}/jwt/validate-token`). */
    suspend fun validate(): CoCartResponse {
        return http.postRaw("${options.namespace}/jwt/validate-token")
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

    /** Log out on the server (`POST logout`), then clear the tokens and cart key. */
    suspend fun logout() {
        try {
            http.post("logout")
        } catch (e: CoCartException) {
            // Continue even if the server call fails.
        }
        auth.clearSession()
    }
}
