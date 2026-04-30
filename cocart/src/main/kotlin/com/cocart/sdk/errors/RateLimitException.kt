package com.cocart.sdk.errors

class RateLimitException(
    message: String,
    val retryAfter: Long? = null
) : CoCartException(message, 429)
