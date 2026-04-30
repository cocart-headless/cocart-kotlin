package com.cocart.sdk.errors

open class AuthException(
    message: String,
    statusCode: Int? = null,
    val errorCode: String? = null
) : CoCartException(message, statusCode)
