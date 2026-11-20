package com.cocart.sdk

import android.content.Context
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.http.BatchRequestItem
import com.cocart.sdk.http.CoCartEvent
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.resources.AccountResource
import com.cocart.sdk.resources.CartResource
import com.cocart.sdk.resources.CheckoutResource
import com.cocart.sdk.resources.JwtResource
import com.cocart.sdk.resources.ProductsResource
import com.cocart.sdk.resources.SessionsResource
import com.cocart.sdk.storage.EncryptedStorage
import com.cocart.sdk.storage.MemoryStorage

class CoCart(
    context: Context?,
    val siteURL: String,
    private var options: CoCartOptions = CoCartOptions()
) {
    private val storage = options.storage
        ?: if (context != null) EncryptedStorage(context) else MemoryStorage()

    private val auth = AuthManager(options, storage)
    private val http = HttpClient(siteURL, options, auth)

    // Lazy resource instances (mirrors TS SDK pattern)
    private var cartInstance: CartResource? = null
    private var productsInstance: ProductsResource? = null
    private var sessionsInstance: SessionsResource? = null
    private var jwtInstance: JwtResource? = null
    private var accountInstance: AccountResource? = null
    private var checkoutInstance: CheckoutResource? = null

    companion object {
        fun create(
            context: Context?,
            siteURL: String,
            options: CoCartOptions = CoCartOptions()
        ) = CoCart(context, siteURL, options)
    }

    // --- Resources ---

    fun cart(): CartResource =
        cartInstance ?: CartResource(http, auth, options).also { cartInstance = it }

    fun products(): ProductsResource =
        productsInstance ?: ProductsResource(http, options).also { productsInstance = it }

    fun sessions(): SessionsResource =
        sessionsInstance ?: SessionsResource(http, auth).also { sessionsInstance = it }

    fun jwt(): JwtResource =
        jwtInstance ?: JwtResource(http, auth, options).also { jwtInstance = it }

    fun account(): AccountResource =
        accountInstance ?: AccountResource(http).also { accountInstance = it }

    /** Checkout (CoCart Plus 2.0): place orders and take payment. */
    fun checkout(): CheckoutResource =
        checkoutInstance ?: CheckoutResource(http).also { checkoutInstance = it }

    // --- Guest Session ---

    fun getCartKey(): String? = auth.cartKey

    /**
     * Restore the cart key kept by the storage adapter from an earlier launch. The
     * storage is read asynchronously, so call this once after creating the client,
     * before the first cart request; without it, the app starts a new cart.
     */
    suspend fun restoreSession() = auth.restoreSession()

    suspend fun clearSession() = auth.clearSession()

    // --- Auth ---

    fun setAuth(identifier: String, password: String): CoCart {
        auth.setBasicAuth(identifier, password)
        return this
    }

    fun setJwtToken(token: String): CoCart {
        auth.setJwtToken(token)
        return this
    }

    fun setRefreshToken(token: String): CoCart {
        auth.setRefreshToken(token)
        return this
    }

    fun isAuthenticated(): Boolean = auth.isAuthenticated

    fun isGuest(): Boolean = auth.isGuest

    // --- Fluent Setters ---

    fun setTimeout(ms: Long): CoCart {
        options = options.copy(timeout = ms)
        return this
    }

    fun setMaxRetries(n: Int): CoCart {
        options = options.copy(maxRetries = n)
        return this
    }

    fun setRestPrefix(prefix: String): CoCart {
        options = options.copy(restPrefix = prefix)
        return this
    }

    fun setNamespace(namespace: String): CoCart {
        options = options.copy(namespace = namespace)
        return this
    }

    fun addHeader(key: String, value: String): CoCart {
        options.extraHeaders[key] = value
        return this
    }

    fun setAuthHeaderName(name: String): CoCart {
        options = options.copy(authHeaderName = name)
        return this
    }

    fun setETag(enabled: Boolean): CoCart {
        options = options.copy(etag = enabled)
        return this
    }

    fun clearETagCache() = http.clearETagCache()

    fun setMainPlugin(plugin: MainPlugin): CoCart {
        options = options.copy(mainPlugin = plugin)
        return this
    }

    fun setDebug(enabled: Boolean): CoCart {
        options = options.copy(debug = enabled)
        return this
    }

    // --- Events ---

    fun on(event: CoCartEvent, handler: (Map<String, Any?>) -> Unit) = http.on(event, handler)

    // --- Batch ---

    /**
     * Dispatch multiple sub-requests in a single call via `{namespace}/batch`
     * (requires CoCart Plus). Throws if the endpoint isn't available (i.e. the
     * plugin isn't active).
     */
    suspend fun batch(requests: List<BatchRequestItem>): CoCartResponse = http.batch(requests)

    // --- Shorthand login/logout ---

    suspend fun login(identifier: String, password: String): CoCartResponse =
        jwt().login(identifier, password)

    suspend fun logout() = jwt().logout()
}
