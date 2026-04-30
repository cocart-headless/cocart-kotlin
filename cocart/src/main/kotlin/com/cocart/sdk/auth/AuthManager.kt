package com.cocart.sdk.auth

import android.util.Base64
import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.storage.CoCartStorage

enum class AuthMode { GUEST, BASIC_AUTH, JWT, CONSUMER_KEYS }

class AuthManager(
    private val options: CoCartOptions,
    private val storage: CoCartStorage
) {
    private var mode: AuthMode = AuthMode.GUEST
    internal var cartKey: String? = options.cartKey
        private set

    private var username: String? = null
    private var password: String? = null
    private var jwtToken: String? = null
    private var refreshToken: String? = null
    private var consumerKey: String? = null
    private var consumerSecret: String? = null

    val isAuthenticated: Boolean get() = mode != AuthMode.GUEST
    val isGuest: Boolean get() = mode == AuthMode.GUEST

    val cartKeyValue: String? get() = if (mode == AuthMode.GUEST) cartKey else null

    init {
        consumerKey = options.consumerKey
        consumerSecret = options.consumerSecret
        when {
            options.jwtToken != null -> {
                jwtToken = options.jwtToken
                refreshToken = options.jwtRefreshToken
                mode = AuthMode.JWT
            }
            options.username != null -> {
                username = options.username
                password = options.password
                mode = AuthMode.BASIC_AUTH
            }
            options.consumerKey != null -> mode = AuthMode.CONSUMER_KEYS
        }
    }

    fun buildAuthHeader(): String? = when (mode) {
        AuthMode.JWT -> "Bearer $jwtToken"
        AuthMode.BASIC_AUTH -> "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(), Base64.NO_WRAP
        )
        AuthMode.CONSUMER_KEYS -> "Basic " + Base64.encodeToString(
            "$consumerKey:$consumerSecret".toByteArray(), Base64.NO_WRAP
        )
        AuthMode.GUEST -> null
    }

    fun setBasicAuth(identifier: String, pwd: String) {
        username = identifier
        password = pwd
        jwtToken = null
        refreshToken = null
        mode = AuthMode.BASIC_AUTH
    }

    fun setJwtToken(token: String) {
        jwtToken = token
        username = null
        password = null
        mode = AuthMode.JWT
    }

    fun setRefreshToken(token: String) {
        refreshToken = token
    }

    fun getJwtToken(): String? = jwtToken

    fun getRefreshToken(): String? = refreshToken

    fun setJwtTokens(token: String, refresh: String?) {
        jwtToken = token
        refreshToken = refresh
        username = null
        password = null
        mode = AuthMode.JWT
    }

    suspend fun captureCartKey(body: Map<String, Any?>, headers: Map<String, String>) {
        if (mode != AuthMode.GUEST) return
        val key = (body["cart_key"] as? String)
            ?: headers["Cart-Key"]
            ?: headers["CoCart-API-Cart-Key"]
        if (key != null && key != cartKey) {
            cartKey = key
            storage.set(options.storageKey, key)
        }
    }

    suspend fun restoreSession() {
        if (cartKey == null) {
            cartKey = storage.get(options.storageKey)
        }
    }

    suspend fun clearSession() {
        cartKey = null
        jwtToken = null
        refreshToken = null
        username = null
        password = null
        mode = AuthMode.GUEST
        storage.delete(options.storageKey)
    }
}
