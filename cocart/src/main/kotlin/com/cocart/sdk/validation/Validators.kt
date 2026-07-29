package com.cocart.sdk.validation

import android.util.Patterns
import com.cocart.sdk.errors.ValidationException

private val NUMERIC_STRING = Regex("""^\s*-?\d+(\.\d+)?\s*$""")

fun validateProductId(id: Int) {
    if (id <= 0) throw ValidationException("Product ID must be a positive integer")
}

/**
 * Validates a product ID given as a string, mirroring the server's own
 * resolution rules.
 *
 * A string containing only a number must represent a positive integer. A
 * non-numeric string is treated as a potential SKU and passed through
 * untouched — the server resolves a non-numeric ID before falling back to a 404.
 * This SDK can't verify a SKU exists without a network request, so it only rejects
 * input that's certain to be invalid: empty, or numeric but not a positive integer.
 */
fun validateProductId(id: String) {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) {
        throw ValidationException("Product ID must be a positive integer")
    }
    if (!NUMERIC_STRING.matches(id)) {
        return // Non-numeric string — treat as a SKU; the server resolves it.
    }
    val value = trimmed.toDoubleOrNull()
    if (value == null || value < 1 || value != Math.floor(value)) {
        throw ValidationException("Product ID must be a positive integer")
    }
}

fun validateQuantity(qty: Double) {
    if (qty <= 0) throw ValidationException("Quantity must be greater than zero")
}

fun validateEmail(email: String) {
    if (!Patterns.EMAIL_ADDRESS.matcher(email).matches())
        throw ValidationException("Invalid email format")
}
