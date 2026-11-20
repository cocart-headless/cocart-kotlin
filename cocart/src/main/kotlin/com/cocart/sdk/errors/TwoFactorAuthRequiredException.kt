package com.cocart.sdk.errors

class TwoFactorAuthRequiredException(
    message: String,
    val availableProviders: List<String> = emptyList(),
    val defaultProvider: String? = null,
    val emailSent: Boolean = false,
    data: Map<String, Any?>? = null,
) : AuthException(message, 401, "cocart_2fa_required", data)
