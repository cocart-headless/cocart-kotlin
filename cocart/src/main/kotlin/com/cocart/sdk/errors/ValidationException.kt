package com.cocart.sdk.errors

class ValidationException(
    message: String,
    statusCode: Int? = null,
    data: Map<String, Any?>? = null
) : CoCartException(message, statusCode, data)
