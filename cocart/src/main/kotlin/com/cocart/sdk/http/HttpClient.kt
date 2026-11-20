package com.cocart.sdk.http

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.AuthException
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.NetworkException
import com.cocart.sdk.errors.TwoFactorAuthRequiredException
import com.cocart.sdk.errors.RateLimitException
import com.cocart.sdk.errors.ValidationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class CoCartEvent { REQUEST, RESPONSE, ERROR }

/** Cached GET response, keyed by the fully-built request URL, used to serve 304 responses. */
private data class ETagCacheEntry(
    val etag: String,
    val body: Map<String, Any?>,
    val headers: Map<String, String>,
    val list: List<Any?>? = null
)

class HttpClient(
    private val siteURL: String,
    private val options: CoCartOptions,
    private val auth: AuthManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(options.timeout, TimeUnit.MILLISECONDS)
        .readTimeout(options.timeout, TimeUnit.MILLISECONDS)
        .build()

    private val etagCache = mutableMapOf<String, ETagCacheEntry>()
    private val listeners = mutableMapOf<CoCartEvent, MutableList<(Map<String, Any?>) -> Unit>>()

    // In-flight GET de-duplication: concurrent identical GETs share one network round trip.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightGetsMutex = Mutex()
    private val inFlightGets = mutableMapOf<String, Deferred<CoCartResponse>>()

    fun on(event: CoCartEvent, handler: (Map<String, Any?>) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(handler)
    }

    private fun emit(event: CoCartEvent, payload: Map<String, Any?>) {
        listeners[event]?.forEach { it(payload) }
    }

    suspend fun get(endpoint: String, queryParams: Map<String, String>? = null): CoCartResponse =
        request("GET", endpoint, queryParams = queryParams)

    suspend fun post(
        endpoint: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse = request("POST", endpoint, body = body, queryParams = queryParams)

    suspend fun put(
        endpoint: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse = request("PUT", endpoint, body = body, queryParams = queryParams)

    suspend fun delete(endpoint: String, queryParams: Map<String, String>? = null): CoCartResponse =
        request("DELETE", endpoint, queryParams = queryParams)

    suspend fun getRaw(rawPath: String, queryParams: Map<String, String>? = null): CoCartResponse =
        requestRaw("GET", rawPath, queryParams = queryParams)

    suspend fun postRaw(
        rawPath: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse = requestRaw("POST", rawPath, body = body, queryParams = queryParams)

    suspend fun putRaw(
        rawPath: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse = requestRaw("PUT", rawPath, body = body, queryParams = queryParams)

    suspend fun deleteRaw(rawPath: String, queryParams: Map<String, String>? = null): CoCartResponse =
        requestRaw("DELETE", rawPath, queryParams = queryParams)

    /**
     * GET a full REST route without the cart key, for routes that refuse one
     * (`products/{id}/shipping-rates` prices a simulated cart and answers 400).
     */
    suspend fun getRawWithoutCartKey(rawPath: String, queryParams: Map<String, String>? = null): CoCartResponse =
        requestRaw("GET", rawPath, queryParams = queryParams, includeCartKey = false)

    fun clearETagCache() = etagCache.clear()

    /**
     * Dispatch multiple sub-requests in a single call via `{namespace}/batch`
     * (requires CoCart Plus).
     *
     * Returns one merged, up-to-date cart response with per-operation notices,
     * instead of one response per request.
     */
    suspend fun batch(requests: List<BatchRequestItem>): CoCartResponse {
        if (requests.isEmpty()) {
            throw ValidationException("batch() requires at least one request.")
        }

        return try {
            postRaw("${options.namespace}/batch", mapOf("requests" to requests.map { it.toMap() }))
        } catch (e: CoCartException) {
            if (e.data?.get("code") == "rest_no_route") {
                throw CoCartException(
                    "CoCart Plus is required to use the batch endpoint",
                    e.statusCode,
                    mapOf("code" to "cocart_plugin_required")
                )
            }
            throw e
        }
    }

    private fun buildUrl(endpoint: String, queryParams: Map<String, String>?): okhttp3.HttpUrl {
        val base = siteURL.trimEnd('/')
        val url = "$base/${options.restPrefix}/${options.namespace}/v2/$endpoint"
        return appendQueryParams(url, queryParams, includeCartKey = true)
    }

    private fun buildRawUrl(
        rawPath: String,
        queryParams: Map<String, String>?,
        includeCartKey: Boolean
    ): okhttp3.HttpUrl {
        val base = siteURL.trimEnd('/')
        val url = "$base/${options.restPrefix}/$rawPath"
        return appendQueryParams(url, queryParams, includeCartKey)
    }

    private fun appendQueryParams(
        url: String,
        queryParams: Map<String, String>?,
        includeCartKey: Boolean
    ): okhttp3.HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        if (includeCartKey) {
            auth.cartKeyValue?.let { builder.addQueryParameter("cart_key", it) }
        }
        queryParams?.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun buildHeaders(url: okhttp3.HttpUrl, includeCartKey: Boolean): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("User-Agent", "CoCart-Kotlin-SDK/1.0.0")

        auth.buildAuthHeader()?.let { builder.add(options.authHeaderName, it) }

        auth.cartKeyValue?.takeIf { includeCartKey }?.let { cartKey ->
            // Send only the cart-key header name the configured plugin actually allows.
            val cartKeyHeader = if (options.mainPlugin == MainPlugin.LEGACY) "CoCart-API-Cart-Key" else "Cart-Key"
            builder.add(cartKeyHeader, cartKey)
        }

        if (options.etag) {
            etagCache[url.toString()]?.let { builder.add("If-None-Match", it.etag) }
        }

        options.extraHeaders.forEach { (k, v) -> builder.add(k, v) }
        return builder.build()
    }

    /**
     * If a guest's cart no longer exists (CoCart Starter 5 refuses the cart key
     * with `cocart_invalid_cart`, for example after the cart expired), the
     * stored cart key is forgotten and the request retried once as a new guest.
     */
    private suspend fun request(
        method: String,
        endpoint: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse {
        return try {
            executeWithRetry(method, buildUrl(endpoint, queryParams), body)
        } catch (e: CoCartException) {
            // The stored guest cart no longer exists (e.g. it expired). CoCart refuses
            // the request before running it, so forget the key and retry once as a new guest.
            if (e.errorCode() != "cocart_invalid_cart" || auth.cartKeyValue == null) throw e
            auth.forgetCartKey()
            executeWithRetry(method, buildUrl(endpoint, queryParams), body)
        }
    }

    /**
     * A full REST route (CoCart Plus, JWT and account routes). It emits the same events as the
     * other requests, and its errors name the route, but it's never sent again: a raw request
     * can take payment.
     */
    private suspend fun requestRaw(
        method: String,
        rawPath: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null,
        includeCartKey: Boolean = true
    ): CoCartResponse {
        val url = buildRawUrl(rawPath, queryParams, includeCartKey)
        return executeWithRetry(method, url, body, includeCartKey, retry = false)
    }

    private suspend fun executeWithRetry(
        method: String,
        url: okhttp3.HttpUrl,
        body: Map<String, Any?>?,
        includeCartKey: Boolean = true,
        retry: Boolean = true
    ): CoCartResponse {
        // De-duplicate identical concurrent GETs so simultaneous callers share one round trip.
        if (method == "GET") {
            val urlKey = url.toString()
            val deferred = inFlightGetsMutex.withLock {
                inFlightGets.getOrPut(urlKey) {
                    ioScope.async { executeWithRetryOnce(method, url, body, includeCartKey, retry) }
                }
            }
            try {
                return deferred.await()
            } finally {
                inFlightGetsMutex.withLock {
                    if (inFlightGets[urlKey] === deferred) inFlightGets.remove(urlKey)
                }
            }
        }
        return executeWithRetryOnce(method, url, body, includeCartKey, retry)
    }

    private suspend fun executeWithRetryOnce(
        method: String,
        url: okhttp3.HttpUrl,
        body: Map<String, Any?>?,
        includeCartKey: Boolean,
        retry: Boolean
    ): CoCartResponse {
        if (!retry) return executeOnce(method, url, body, includeCartKey)
        var lastException: Exception? = null
        repeat(options.maxRetries + 1) { attempt ->
            try {
                return executeOnce(method, url, body, includeCartKey)
            } catch (e: NetworkException) {
                // Only a GET is sent again: a request that changes something may have run
                // before the connection dropped, and running it twice could, for example,
                // charge a card twice.
                if (method != "GET") throw e
                lastException = e
                if (attempt < options.maxRetries) {
                    kotlinx.coroutines.delay(getRetryDelay(attempt + 1))
                }
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt < options.maxRetries) {
                    kotlinx.coroutines.delay(getRetryDelay(attempt + 1, e.retryAfter))
                }
            }
        }
        throw lastException ?: NetworkException("Request failed after ${options.maxRetries} retries")
    }

    /**
     * The wait before another attempt, in milliseconds: what the store asked for
     * (`RateLimit-Retry-After`, which CoCart Plus sends, or `Retry-After`), capped at 60s;
     * otherwise exponential backoff, min(2^(attempt-1), 30) seconds, with ±20% jitter so many
     * clients retrying at once don't re-collide on the same schedule.
     */
    private fun getRetryDelay(attempt: Int, retryAfterSeconds: Long? = null): Long {
        if (retryAfterSeconds != null) {
            return retryAfterSeconds.coerceIn(0L, 60L) * 1000L
        }
        val base = minOf(Math.pow(2.0, (attempt - 1).toDouble()), 30.0) * 1000.0
        val jitter = 0.8 + Random.nextDouble() * 0.4
        return (base * jitter).toLong()
    }

    private suspend fun executeOnce(
        method: String,
        url: okhttp3.HttpUrl,
        bodyMap: Map<String, Any?>?,
        includeCartKey: Boolean
    ): CoCartResponse = withContext(Dispatchers.IO) {
        val headers = buildHeaders(url, includeCartKey)

        val requestBody = when (method) {
            "POST", "PUT" -> {
                val json = if (bodyMap != null) JSONObject(bodyMap).toString() else "{}"
                json.toRequestBody("application/json".toMediaType())
            }
            else -> null
        }

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .method(method, requestBody)
            .build()

        // Debug output hides every query value (cart key, order key, email…); events keep the URL.
        if (options.debug) {
            println("[CoCart] $method ${redactQuery(url.toString())}")
        }

        emit(CoCartEvent.REQUEST, mapOf("method" to method, "url" to url.toString()))

        val response: Response
        val parsedBody: Map<String, Any?>
        // Some routes answer with a list (categories, tags, attributes, reviews, variations).
        val parsedList: List<Any?>?
        val responseHeaders: Map<String, String>
        val responseCode: Int
        val cachedEntry: ETagCacheEntry?
        val urlKey = url.toString()
        val isNotModified: Boolean
        try {
            response = client.newCall(request).execute()
            responseCode = response.code

            // A 304 has no body — reuse the body/headers cached alongside the ETag that
            // produced the match, so callers still get the actual data instead of an
            // empty object. Falls back to an empty response if we have no cache entry.
            isNotModified = responseCode == 304 && options.etag
            cachedEntry = if (isNotModified) etagCache[urlKey] else null

            if (cachedEntry != null) {
                parsedBody = cachedEntry.body
                parsedList = cachedEntry.list
                responseHeaders = cachedEntry.headers
            } else {
                // Reading the body can itself time out (headers arrive, body streams
                // slowly/never) — that must be treated the same as a connect/read
                // failure on the initial call, not escape as a raw IOException.
                val responseBodyString = response.body?.string() ?: "{}"
                val root = try {
                    JSONTokener(responseBodyString).nextValue()
                } catch (e: Exception) {
                    null
                }
                parsedBody = if (root is JSONObject) jsonToMap(root) else emptyMap()
                parsedList = if (root is org.json.JSONArray) jsonArrayToList(root) else null
                // Header names are case-insensitive (HTTP/2 sends them lowercase).
                responseHeaders = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                    .apply { putAll(response.headers.toMap()) }
            }
        } catch (e: IOException) {
            // Any URL's query string is removed from the message: it can carry the cart key.
            val ex = NetworkException("Network error: ${safeErrorMessage(e.message.orEmpty())}", e)
            emit(CoCartEvent.ERROR, mapOf("method" to method, "url" to urlKey, "error" to ex.message))
            throw ex
        }

        // Capture cart key from response
        auth.captureCartKey(parsedBody, responseHeaders)

        // Cache the ETag + body from a fresh (non-304) response for future 304s, unless the
        // store says not to keep it (`no-store` or `private`: checkout, order and account responses).
        if (options.etag && !isNotModified && mayStore(response.header("Cache-Control"))) {
            response.header("ETag")?.let { etagCache[urlKey] = ETagCacheEntry(it, parsedBody, responseHeaders, parsedList) }
        }

        val coCartResponse = CoCartResponse(parsedBody, responseHeaders, responseCode, parsedList)
        // Listeners get the raw URL, which can carry the cart key, an order key or an email:
        // they must not forward it as it is.
        emit(
            CoCartEvent.RESPONSE,
            mapOf(
                "method" to method,
                "url" to urlKey,
                "status" to responseCode,
                "data" to parsedBody,
                "response" to coCartResponse
            ) + (if (responseCode == 304) mapOf("cached" to true) else emptyMap())
        )

        if (responseCode == 304) {
            return@withContext coCartResponse
        }

        if (options.debug) {
            println("[CoCart] Response $responseCode")
        }

        if (responseCode >= 400) {
            val code = parsedBody["code"] as? String ?: "unknown_error"
            val apiMessage = parsedBody["message"] as? String
                ?: if (responseCode == 429) "Rate limit exceeded" else "Request failed with status $responseCode"
            // "POST /cocart/v2/checkout: Message [code]": the route, never the query string.
            val codeLabel = if (code != "unknown_error") " [$code]" else ""
            val msg = "$method ${routePath(url)}: $apiMessage$codeLabel"
            val ex = when {
                responseCode == 429 -> {
                    // CoCart Plus sends `RateLimit-Retry-After`; a proxy may send `Retry-After`.
                    val retryAfter = (response.header("RateLimit-Retry-After") ?: response.header("Retry-After"))
                        ?.trim()?.toLongOrNull()
                    RateLimitException(msg, retryAfter, parsedBody)
                }
                code == "cocart_2fa_required" -> {
                    val details = parsedBody["data"] as? Map<*, *> ?: parsedBody
                    TwoFactorAuthRequiredException(
                        msg,
                        (details["available_providers"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        details["default_provider"] as? String,
                        details["email_sent"] as? Boolean ?: false,
                        parsedBody
                    )
                }
                responseCode == 401 || responseCode == 403 -> AuthException(msg, responseCode, code, parsedBody)
                // A refused state (409) or the server's fault (5xx) is never a validation error,
                // whatever its code says (e.g. `cocart_order_invalid_status` is a 409).
                responseCode == 409 || responseCode >= 500 -> CoCartException(msg, responseCode, parsedBody)
                responseCode == 400 -> ValidationException(msg, responseCode, parsedBody)
                else -> CoCartException(msg, responseCode, parsedBody)
            }
            emit(
                CoCartEvent.ERROR,
                mapOf("method" to method, "url" to urlKey, "error" to ex.message, "code" to code, "status" to responseCode)
            )
            throw ex
        }

        options.responseTransformer?.invoke(coCartResponse) ?: coCartResponse
    }

    /** The REST route of a request URL for error messages: the path after `wp-json`, with no host and no query string. */
    private fun routePath(url: okhttp3.HttpUrl): String {
        val path = url.encodedPath
        val prefix = "/${options.restPrefix.trim('/')}"
        val i = path.indexOf("$prefix/")
        return if (i >= 0) path.substring(i + prefix.length) else path
    }

    /** Removes the query string from any URL or path in a transport error message. */
    private fun safeErrorMessage(message: String): String =
        URL_QUERY.replace(message, "$1")

    internal companion object {
        private val URL_QUERY = Regex("""((?:https?://|/)[^\s?#"'<>()]*)[?#][^\s"'<>()]*""", RegexOption.IGNORE_CASE)
        private val NO_STORE = Regex("""(^|[\s,])(no-store|private)([\s,;=]|$)""", RegexOption.IGNORE_CASE)

        /**
         * A URL for debug output, with every query value replaced by `***`: the query can hold the
         * cart key, an order key, an email address or a verification key, which mustn't end up in logs.
         */
        fun redactQuery(url: String): String {
            val i = url.indexOf('?')
            if (i < 0) return url
            val query = url.substring(i + 1).split('&').joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair else "${pair.substring(0, eq)}=***"
            }
            return "${url.substring(0, i)}?$query"
        }

        /** Whether a response may be kept in the ETag cache: not when `Cache-Control` says `no-store` or `private`. */
        fun mayStore(cacheControl: String?): Boolean =
            cacheControl.isNullOrEmpty() || !NO_STORE.containsMatchIn(cacheControl)
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (val value = obj.get(key)) {
                is JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any?> {
        return (0 until arr.length()).map { i ->
            when (val value = arr.get(i)) {
                is JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
    }
}
