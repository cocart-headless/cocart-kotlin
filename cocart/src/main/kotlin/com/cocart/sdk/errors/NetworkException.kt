package com.cocart.sdk.errors

class NetworkException(message: String, cause: Throwable? = null) : CoCartException(message) {
    init { cause?.let { initCause(it) } }
}
