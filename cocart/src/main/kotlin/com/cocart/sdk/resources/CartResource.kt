package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.auth.AuthManager
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.validation.validateProductId
import com.cocart.sdk.validation.validateQuantity

class CartResource(
    private val http: HttpClient,
    private val auth: AuthManager,
    private val options: CoCartOptions
) {
    suspend fun create(): CoCartResponse = http.post("cart")

    suspend fun get(params: Map<String, String>? = null): CoCartResponse =
        http.get("cart", params)

    suspend fun getFiltered(fields: List<String>): CoCartResponse {
        val fieldParam = if (options.mainPlugin == MainPlugin.LEGACY) "fields" else "_fields"
        return http.get("cart", mapOf(fieldParam to fields.joinToString(",")))
    }

    suspend fun addItem(
        id: Int,
        qty: Double = 1.0,
        options: Map<String, Any?>? = null
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        val body = mutableMapOf<String, Any?>("id" to id, "quantity" to qty)
        options?.forEach { (k, v) -> body[k] = v }
        return http.post("cart/add-item", body)
    }

    suspend fun add(id: Int, qty: Double = 1.0): CoCartResponse = addItem(id, qty)

    suspend fun addVariation(
        id: Int,
        qty: Double = 1.0,
        attributes: Map<String, String>
    ): CoCartResponse {
        validateProductId(id)
        validateQuantity(qty)
        return http.post("cart/add-item", mapOf(
            "id" to id,
            "quantity" to qty,
            "variation" to attributes
        ))
    }

    suspend fun addItems(items: List<Map<String, Any?>>): CoCartResponse =
        http.post("cart/add-items", mapOf("items" to items))

    suspend fun updateItem(
        itemKey: String,
        qty: Double,
        options: Map<String, Any?>? = null
    ): CoCartResponse {
        validateQuantity(qty)
        val body = mutableMapOf<String, Any?>("quantity" to qty)
        options?.forEach { (k, v) -> body[k] = v }
        return http.post("cart/item/$itemKey", body)
    }

    suspend fun updateItems(items: List<Map<String, Any?>>): CoCartResponse =
        http.post("cart/items", mapOf("items" to items))

    suspend fun removeItem(itemKey: String): CoCartResponse =
        http.delete("cart/item/$itemKey")

    suspend fun removeItems(itemKeys: List<String>): CoCartResponse =
        http.post("cart/items/remove", mapOf("keys" to itemKeys))

    suspend fun restoreItem(itemKey: String): CoCartResponse =
        http.post("cart/item/$itemKey/restore")

    suspend fun getRemovedItems(): CoCartResponse = http.get("cart/items/removed")

    suspend fun clear(): CoCartResponse = http.post("cart/clear")

    suspend fun empty(): CoCartResponse = clear()

    suspend fun calculate(): CoCartResponse = http.post("cart/calculate")

    suspend fun update(data: Map<String, Any?>): CoCartResponse = http.post("cart/update", data)

    suspend fun getTotals(formatted: Boolean = false): CoCartResponse =
        http.get("cart/totals", if (formatted) mapOf("html" to "true") else null)

    suspend fun getItemCount(): CoCartResponse = http.get("cart/items/count")

    suspend fun getItems(params: Map<String, String>? = null): CoCartResponse =
        http.get("cart/items", params)

    suspend fun getItem(itemKey: String): CoCartResponse = http.get("cart/item/$itemKey")

    // CoCart Plus methods

    suspend fun applyCoupon(code: String): CoCartResponse =
        http.post("cart/coupon", mapOf("coupon" to code))

    suspend fun removeCoupon(code: String): CoCartResponse =
        http.delete("cart/coupon/$code")

    suspend fun getCoupons(): CoCartResponse = http.get("cart/coupons")

    suspend fun checkCoupons(): CoCartResponse = http.get("cart/coupons/check")

    suspend fun updateCustomer(
        billing: Map<String, Any?>,
        shipping: Map<String, Any?>? = null
    ): CoCartResponse {
        val body = mutableMapOf<String, Any?>("billing" to billing)
        shipping?.let { body["shipping"] = it }
        return http.post("cart/customer", body)
    }

    suspend fun getCustomer(): CoCartResponse = http.get("cart/customer")

    suspend fun getShippingMethods(): CoCartResponse = http.get("cart/shipping-methods")

    suspend fun setShippingMethod(method: Map<String, Any?>): CoCartResponse =
        http.post("cart/shipping-methods", method)

    suspend fun calculateShipping(address: Map<String, Any?>): CoCartResponse =
        http.post("cart/shipping-methods/calculate", address)

    suspend fun getFees(): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("getFees() requires CoCart Plus")
        }
        return http.get("cart/fees")
    }

    suspend fun addFee(
        name: String,
        amount: Double,
        taxable: Boolean = false
    ): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("addFee() requires CoCart Plus")
        }
        return http.post("cart/fees", mapOf("name" to name, "amount" to amount, "taxable" to taxable))
    }

    suspend fun removeFees(): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("removeFees() requires CoCart Plus")
        }
        return http.delete("cart/fees")
    }

    suspend fun getCrossSells(): CoCartResponse = http.get("cart/cross-sells")
}
