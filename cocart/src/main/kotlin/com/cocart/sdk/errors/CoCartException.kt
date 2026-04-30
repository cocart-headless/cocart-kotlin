package com.cocart.sdk.errors

open class CoCartException(
    message: String,
    val statusCode: Int? = null,
    val data: Map<String, Any?>? = null
) : Exception(message)
