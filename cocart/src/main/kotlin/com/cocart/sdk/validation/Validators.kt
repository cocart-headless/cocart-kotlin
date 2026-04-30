package com.cocart.sdk.validation

import android.util.Patterns
import com.cocart.sdk.errors.ValidationException

fun validateProductId(id: Int) {
    if (id <= 0) throw ValidationException("Product ID must be a positive integer")
}

fun validateQuantity(qty: Double) {
    if (qty <= 0) throw ValidationException("Quantity must be greater than zero")
}

fun validateEmail(email: String) {
    if (!Patterns.EMAIL_ADDRESS.matcher(email).matches())
        throw ValidationException("Invalid email format")
}
