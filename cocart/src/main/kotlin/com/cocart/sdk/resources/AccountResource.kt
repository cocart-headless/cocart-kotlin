package com.cocart.sdk.resources

import com.cocart.sdk.errors.CoCartException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient

class AccountResource(
    private val http: HttpClient
) {
    // All account routes bypass the namespace builder — use raw path directly
    private val base = "cocart/v2/my-account"

    suspend fun getProfile(): CoCartResponse = wrapPluginError {
        http.getRaw(base)
    }

    suspend fun updateProfile(data: Map<String, Any?>): CoCartResponse = wrapPluginError {
        http.postRaw(base, data)
    }

    suspend fun changePassword(
        current: String,
        password: String,
        confirm: String
    ): CoCartResponse = wrapPluginError {
        http.postRaw(
            "$base/change-password",
            mapOf(
                "password_current" to current,
                "password_1" to password,
                "password_2" to confirm
            )
        )
    }

    suspend fun getOrders(params: Map<String, String>? = null): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders", params)
    }

    suspend fun getOrder(id: Int): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id")
    }

    suspend fun getGuestOrder(id: Int, email: String): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id", mapOf("email" to email))
    }

    suspend fun getOrderDownloads(id: Int): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id/downloads")
    }

    suspend fun getGuestOrderDownloads(id: Int, email: String): CoCartResponse = wrapPluginError {
        http.getRaw("$base/orders/$id/downloads", mapOf("email" to email))
    }

    suspend fun getDownloads(): CoCartResponse = wrapPluginError {
        http.getRaw("$base/downloads")
    }

    suspend fun getReviews(): CoCartResponse = wrapPluginError {
        http.getRaw("$base/reviews")
    }

    private suspend fun wrapPluginError(block: suspend () -> CoCartResponse): CoCartResponse {
        return try {
            block()
        } catch (e: CoCartException) {
            if (e.data?.get("code") == "rest_no_route") {
                throw CoCartException(
                    "CoCart plugin required for account endpoints",
                    e.statusCode,
                    mapOf("code" to "cocart_plugin_required")
                )
            }
            throw e
        }
    }
}
