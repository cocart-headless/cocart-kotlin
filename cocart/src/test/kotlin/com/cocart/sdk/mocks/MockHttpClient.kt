package com.cocart.sdk.mocks

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.http.CoCartEvent
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.storage.MemoryStorage

fun buildAuthManager(options: CoCartOptions = CoCartOptions()): AuthManager =
    AuthManager(options, MemoryStorage())

fun mockResponse(
    data: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    statusCode: Int = 200
): CoCartResponse = CoCartResponse(data, headers, statusCode)
