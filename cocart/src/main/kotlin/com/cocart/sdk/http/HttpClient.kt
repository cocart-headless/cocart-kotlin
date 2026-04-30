package com.cocart.sdk.http

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.errors.NetworkException
import com.cocart.sdk.errors.RateLimitException
import kotlinx.coroutines.Dispatchers
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

enum class CoCartEvent { REQUEST, RESPONSE, ERROR }

class HttpClient(
    private val siteURL: String,
    private val options: CoCartOptions,
    private val auth: AuthManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(options.timeout, TimeUnit.MILLISECONDS)
        .readTimeout(options.timeout, TimeUnit.MILLISECONDS)
        .build()

    private val etagCache = mutableMapOf<String, String>()
    private val listeners = mutableMapOf<CoCartEvent, MutableList<(Map<String, Any?>) -> Unit>>()

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
            builder.add("Cart-Key", cartKey)
            builder.add("CoCart-API-Cart-Key", cartKey)
        }

        if (options.etag) {
            etagCache[url.toString()]?.let { builder.add("If-None-Match", it) }
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
        var lastException: Exception? = null
        repeat(options.maxRetries + 1) { attempt ->
            try {
                return executeOnce(method, url, body)
            } catch (e: NetworkException) {
                lastException = e
                if (attempt < options.maxRetries) {
                    // Exponential backoff: 500ms, 1000ms, ...
                    val delay = 500L * (attempt + 1)
                    kotlinx.coroutines.delay(delay)
                }
            }
        }
        throw lastException ?: NetworkException("Request failed after ${options.maxRetries} retries")
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
        try {
            response = client.newCall(request).execute()
        } catch (e: IOException) {
            val ex = NetworkException("Network error: ${e.message}", e)
            emit(CoCartEvent.ERROR, mapOf("error" to ex.message))
            throw ex
        }

        val responseCode = response.code

        // Store ETag for future conditional requests
        if (options.etag) {
            response.header("ETag")?.let { etagCache[url.toString()] = it }
        }

        // 304 Not Modified — return empty response with status
        if (responseCode == 304) {
            emit(CoCartEvent.RESPONSE, mapOf("status" to 304, "cached" to true))
            return@withContext CoCartResponse(emptyMap(), response.headers.toMap(), 304)
        }

        val responseBodyString = response.body?.string() ?: "{}"

        val parsedBody: Map<String, Any?> = try {
            jsonToMap(JSONObject(responseBodyString))
        } catch (e: Exception) {
            emptyMap()
        }

        val responseHeaders = response.headers.toMap()

        // Capture cart key from response
        auth.captureCartKey(parsedBody, responseHeaders)

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
