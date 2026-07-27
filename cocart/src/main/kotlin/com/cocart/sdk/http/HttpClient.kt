package com.cocart.sdk.http

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.NetworkException
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class CoCartEvent { REQUEST, RESPONSE, ERROR }

/** Cached GET response, keyed by the fully-built request URL, used to serve 304 responses. */
private data class ETagCacheEntry(
    val etag: String,
    val body: Map<String, Any?>,
    val headers: Map<String, String>
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
        return appendQueryParams(url, queryParams)
    }

    private fun buildRawUrl(rawPath: String, queryParams: Map<String, String>?): okhttp3.HttpUrl {
        val base = siteURL.trimEnd('/')
        val url = "$base/${options.restPrefix}/$rawPath"
        return appendQueryParams(url, queryParams)
    }

    private fun appendQueryParams(url: String, queryParams: Map<String, String>?): okhttp3.HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        auth.cartKeyValue?.let { builder.addQueryParameter("cart_key", it) }
        queryParams?.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun buildHeaders(url: okhttp3.HttpUrl): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("User-Agent", "CoCart-Kotlin-SDK/1.0.0")

        auth.buildAuthHeader()?.let { builder.add(options.authHeaderName, it) }

        auth.cartKeyValue?.let { cartKey ->
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

    private suspend fun request(
        method: String,
        endpoint: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse {
        val url = buildUrl(endpoint, queryParams)
        return executeWithRetry(method, url, body)
    }

    private suspend fun requestRaw(
        method: String,
        rawPath: String,
        body: Map<String, Any?>? = null,
        queryParams: Map<String, String>? = null
    ): CoCartResponse {
        val url = buildRawUrl(rawPath, queryParams)
        return executeWithRetry(method, url, body)
    }

    private suspend fun executeWithRetry(
        method: String,
        url: okhttp3.HttpUrl,
        body: Map<String, Any?>?
    ): CoCartResponse {
        // De-duplicate identical concurrent GETs so simultaneous callers share one round trip.
        if (method == "GET") {
            val urlKey = url.toString()
            val deferred = inFlightGetsMutex.withLock {
                inFlightGets.getOrPut(urlKey) {
                    ioScope.async { executeWithRetryOnce(method, url, body) }
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
        return executeWithRetryOnce(method, url, body)
    }

    private suspend fun executeWithRetryOnce(
        method: String,
        url: okhttp3.HttpUrl,
        body: Map<String, Any?>?
    ): CoCartResponse {
        var lastException: Exception? = null
        repeat(options.maxRetries + 1) { attempt ->
            try {
                return executeOnce(method, url, body)
            } catch (e: NetworkException) {
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
     * Exponential backoff: min(2^(attempt-1), 30) seconds, honoring a `Retry-After`
     * header (capped at 60s) when present, with ±20% jitter applied on top so many
     * clients retrying at once don't re-collide on the same schedule.
     */
    private fun getRetryDelay(attempt: Int, retryAfterSeconds: Long? = null): Long {
        val baseSeconds = if (retryAfterSeconds != null) {
            minOf(retryAfterSeconds, 60L).toDouble()
        } else {
            minOf(Math.pow(2.0, (attempt - 1).toDouble()), 30.0)
        }
        val base = baseSeconds * 1000.0
        val jitter = 0.8 + Random.nextDouble() * 0.4
        return (base * jitter).toLong()
    }

    private suspend fun executeOnce(
        method: String,
        url: okhttp3.HttpUrl,
        bodyMap: Map<String, Any?>?
    ): CoCartResponse = withContext(Dispatchers.IO) {
        val headers = buildHeaders(url)

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

        if (options.debug) {
            println("[CoCart] $method ${url}")
        }

        emit(CoCartEvent.REQUEST, mapOf("method" to method, "url" to url.toString()))

        val response: Response
        val parsedBody: Map<String, Any?>
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
                responseHeaders = cachedEntry.headers
            } else {
                // Reading the body can itself time out (headers arrive, body streams
                // slowly/never) — that must be treated the same as a connect/read
                // failure on the initial call, not escape as a raw IOException.
                val responseBodyString = response.body?.string() ?: "{}"
                parsedBody = try {
                    jsonToMap(JSONObject(responseBodyString))
                } catch (e: Exception) {
                    emptyMap()
                }
                responseHeaders = response.headers.toMap()
            }
        } catch (e: IOException) {
            val ex = NetworkException("Network error: ${e.message}", e)
            emit(CoCartEvent.ERROR, mapOf("error" to ex.message))
            throw ex
        }

        // Capture cart key from response
        auth.captureCartKey(parsedBody, responseHeaders)

        // Cache the ETag + body from a fresh (non-304) response for future 304s
        if (options.etag && !isNotModified) {
            response.header("ETag")?.let { etagCache[urlKey] = ETagCacheEntry(it, parsedBody, responseHeaders) }
        }

        if (responseCode == 304) {
            emit(CoCartEvent.RESPONSE, mapOf("status" to 304, "cached" to true))
            return@withContext CoCartResponse(parsedBody, responseHeaders, 304)
        }

        if (options.debug) {
            println("[CoCart] Response $responseCode")
        }

        if (responseCode == 429) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val msg = parsedBody["message"] as? String ?: "Rate limit exceeded"
            val ex = RateLimitException(msg, retryAfter)
            emit(CoCartEvent.ERROR, mapOf("error" to ex.message, "status" to 429))
            throw ex
        }

        if (responseCode >= 400) {
            val code = parsedBody["code"] as? String ?: "unknown_error"
            val msg = parsedBody["message"] as? String ?: "Request failed with status $responseCode"
            val ex = CoCartException(msg, responseCode, parsedBody)
            emit(CoCartEvent.ERROR, mapOf("error" to ex.message, "code" to code, "status" to responseCode))
            throw ex
        }

        val coCartResponse = CoCartResponse(parsedBody, responseHeaders, responseCode)
        val finalResponse = options.responseTransformer?.invoke(coCartResponse) ?: coCartResponse

        emit(CoCartEvent.RESPONSE, mapOf("status" to responseCode, "data" to parsedBody))
        finalResponse
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
