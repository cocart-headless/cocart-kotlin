package com.cocart.sdk.validation

import com.cocart.sdk.errors.ValidationException
import org.junit.Assert.*
import org.junit.Test

class ValidatorsTest {
    @Test
    fun `validateProductId passes for positive id`() {
        validateProductId(1)
        validateProductId(999)
    }

    @Test(expected = ValidationException::class)
    fun `validateProductId throws for zero`() {
        validateProductId(0)
    }

    @Test(expected = ValidationException::class)
    fun `validateProductId throws for negative`() {
        validateProductId(-5)
    }

    @Test
    fun `validateQuantity passes for positive quantity`() {
        validateQuantity(1.0)
        validateQuantity(0.5)
        validateQuantity(100.0)
    }

    @Test(expected = ValidationException::class)
    fun `validateQuantity throws for zero`() {
        validateQuantity(0.0)
    }

    @Test(expected = ValidationException::class)
    fun `validateQuantity throws for negative`() {
        validateQuantity(-1.0)
    }
}
