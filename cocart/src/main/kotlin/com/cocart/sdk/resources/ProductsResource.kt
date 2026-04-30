package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.validation.validateProductId

class ProductsResource(
    private val http: HttpClient,
    private val options: CoCartOptions
) {
    suspend fun all(filters: Map<String, String>? = null): CoCartResponse =
        http.get("products", filters)

    suspend fun find(id: Int): CoCartResponse {
        validateProductId(id)
        return http.get("products/$id")
    }

    suspend fun findBySlug(slug: String): CoCartResponse {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("findBySlug() is only available with CoCart Basic")
        }
        return http.get("products/search", mapOf("slug" to slug))
    }

    suspend fun variation(id: Int, variationId: Int): CoCartResponse {
        validateProductId(id)
        validateProductId(variationId)
        return http.get("products/$id/variations/$variationId")
    }

    suspend fun category(slug: String): CoCartResponse =
        http.get("products/categories/$slug")

    suspend fun tag(slug: String): CoCartResponse =
        http.get("products/tags/$slug")

    suspend fun brands(): CoCartResponse = http.get("products/brands")

    suspend fun brand(slug: String): CoCartResponse =
        http.get("products/brands/$slug")

    suspend fun byBrand(slug: String): CoCartResponse =
        http.get("products", mapOf("brand" to slug))

    suspend fun myReviews(): CoCartResponse = http.get("products/reviews/my-reviews")
}
