package com.cocart.sdk.errors

class RateLimitException(
    message: String,
    /** Seconds to wait, from `RateLimit-Retry-After` (what CoCart sends) or `Retry-After`. */
    val retryAfter: Long? = null,
    data: Map<String, Any?>? = null
) : CoCartException(message, 429, data)
