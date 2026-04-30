package com.cocart.sdk

import com.cocart.sdk.storage.CoCartStorage

enum class MainPlugin { BASIC, LEGACY }

data class CoCartOptions(
    val cartKey: String? = null,
    val storageKey: String = "cocart_cart_key",

    val username: String? = null,
    val password: String? = null,

    val jwtToken: String? = null,
    val jwtRefreshToken: String? = null,

    val consumerKey: String? = null,
    val consumerSecret: String? = null,

    val timeout: Long = 30_000L,
    val maxRetries: Int = 2,

    val restPrefix: String = "wp-json",
    val namespace: String = "cocart",

    val mainPlugin: MainPlugin = MainPlugin.BASIC,

    val authHeaderName: String = "Authorization",

    val storage: CoCartStorage? = null,

    val extraHeaders: MutableMap<String, String> = mutableMapOf(),
    val responseTransformer: ((com.cocart.sdk.http.CoCartResponse) -> com.cocart.sdk.http.CoCartResponse)? = null,
    val etag: Boolean = true,
    val debug: Boolean = false,
)
