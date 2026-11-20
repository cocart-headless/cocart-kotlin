package com.cocart.sdk.resources

import com.cocart.sdk.CoCartOptions
import com.cocart.sdk.MainPlugin
import com.cocart.sdk.errors.VersionException
import com.cocart.sdk.http.CoCartResponse
import com.cocart.sdk.http.HttpClient
import com.cocart.sdk.validation.validateProductId
import java.net.URLEncoder

class ProductsResource(
    private val http: HttpClient,
    private val options: CoCartOptions
) {
    // --- Listing ---

    /**
     * List products. The list is under `products`, with `page`, `total_pages` and
     * `total_products` alongside. Common [filters]: `per_page`, `page`, `search`,
     * `category`, `tag`, `brand`, `featured`, `on_sale`, `min_price`, `max_price`,
     * `stock_status`, `orderby`, `order` and `_fields`.
     */
    suspend fun all(filters: Map<String, String>? = null): CoCartResponse =
        http.get("products", filters)

    /** Search products by keyword. */
    suspend fun search(term: String, filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("search" to term))

    /** Products in the category with this slug. */
    suspend fun byCategory(slug: String, filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("category" to slug))

    /** Products with the tag with this slug. */
    suspend fun byTag(slug: String, filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("tag" to slug))

    /** Featured products. */
    suspend fun featured(filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("featured" to "true"))

    /** Products on sale. */
    suspend fun onSale(filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("on_sale" to "true"))

    /** Products priced between [minPrice] and [maxPrice] (either may be left out). */
    suspend fun byPriceRange(
        minPrice: String? = null,
        maxPrice: String? = null,
        filters: Map<String, String> = emptyMap()
    ): CoCartResponse {
        val merged = filters.toMutableMap()
        minPrice?.let { merged["min_price"] = it }
        maxPrice?.let { merged["max_price"] = it }
        return all(merged)
    }

    /** Products sorted by [field]. Sort by price with [ProductOrderBy.PRICE_ASC] or [ProductOrderBy.PRICE_DESC]. */
    suspend fun sortBy(
        field: ProductOrderBy,
        order: SortOrder = SortOrder.ASC,
        filters: Map<String, String> = emptyMap()
    ): CoCartResponse = all(filters + mapOf("orderby" to field.value, "order" to order.value))

    /** Products with this stock status. */
    suspend fun byStockStatus(status: StockStatus, filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + ("stock_status" to status.value))

    /** A page of products. */
    suspend fun paginate(page: Int, perPage: Int = 10, filters: Map<String, String> = emptyMap()): CoCartResponse =
        all(filters + mapOf("page" to page.toString(), "per_page" to perPage.toString()))

    // --- Single product ---

    suspend fun find(id: Int, params: Map<String, String>? = null): CoCartResponse {
        validateProductId(id)
        return http.get("products/$id", params)
    }

    /**
     * Get a single product by SKU. `GET products/{id}` accepts either the
     * numeric product/variation ID or the product's SKU in the same path
     * segment.
     */
    suspend fun find(sku: String, params: Map<String, String>? = null): CoCartResponse {
        validateProductId(sku)
        return http.get("products/$sku", params)
    }

    /** Get a single product by slug (`GET products/{slug}`). Requires CoCart Starter. */
    suspend fun findBySlug(slug: String, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("findBySlug")
        return http.get("products/${encode(slug)}", params)
    }

    // --- Variations ---

    /** A variable product's variations. */
    suspend fun variations(id: Int, params: Map<String, String>? = null): CoCartResponse {
        validateProductId(id)
        return http.get("products/$id/variations", params)
    }

    /** A single variation. Requires CoCart Starter. */
    suspend fun variation(id: Int, variationId: Int, params: Map<String, String>? = null): CoCartResponse {
        validateProductId(id)
        validateProductId(variationId)
        requiresStarter("variation")
        return http.get("products/$id/variations/$variationId", params)
    }

    // --- Categories and tags ---

    /** Product categories. */
    suspend fun categories(params: Map<String, String>? = null): CoCartResponse =
        http.get("products/categories", params)

    /** A single category by ID. Requires CoCart Starter. */
    suspend fun category(id: Int, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("category")
        return http.get("products/categories/$id", params)
    }

    /** Product tags. */
    suspend fun tags(params: Map<String, String>? = null): CoCartResponse =
        http.get("products/tags", params)

    /** A single tag by ID. Requires CoCart Starter. */
    suspend fun tag(id: Int, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("tag")
        return http.get("products/tags/$id", params)
    }

    // --- Attributes ---

    /** Product attributes. */
    suspend fun attributes(params: Map<String, String>? = null): CoCartResponse =
        http.get("products/attributes", params)

    /** A single attribute by ID. */
    suspend fun attribute(id: Int, params: Map<String, String>? = null): CoCartResponse =
        http.get("products/attributes/$id", params)

    /** An attribute's terms, e.g. every color. */
    suspend fun attributeTerms(id: Int, params: Map<String, String>? = null): CoCartResponse =
        http.get("products/attributes/$id/terms", params)

    /** A single term of an attribute. */
    suspend fun attributeTerm(id: Int, termId: Int, params: Map<String, String>? = null): CoCartResponse =
        http.get("products/attributes/$id/terms/$termId", params)

    /** A single attribute by slug, e.g. `color`. Requires CoCart Starter. */
    suspend fun attributeBySlug(slug: String, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("attributeBySlug")
        return http.get("products/attributes/${encode(slug)}", params)
    }

    /** An attribute's terms, by the attribute's slug. Requires CoCart Starter. */
    suspend fun attributeTermsBySlug(slug: String, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("attributeTermsBySlug")
        return http.get("products/attributes/${encode(slug)}/terms", params)
    }

    /** A single term by slug, of an attribute by slug. Requires CoCart Starter. */
    suspend fun attributeTermBySlug(
        attributeSlug: String,
        termSlug: String,
        params: Map<String, String>? = null
    ): CoCartResponse {
        requiresStarter("attributeTermBySlug")
        return http.get("products/attributes/${encode(attributeSlug)}/terms/${encode(termSlug)}", params)
    }

    // --- Brands ---

    /** Product brands. Requires CoCart Starter. */
    suspend fun brands(params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("brands")
        return http.get("products/brands", params)
    }

    /** A single brand by ID. Requires CoCart Starter. */
    suspend fun brand(id: Int, params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("brand")
        return http.get("products/brands/$id", params)
    }

    /** Products of the brand with this slug. Requires CoCart Starter. */
    suspend fun byBrand(slug: String, filters: Map<String, String> = emptyMap()): CoCartResponse {
        requiresStarter("byBrand")
        return all(filters + ("brand" to slug))
    }

    // --- Reviews ---

    /** Product reviews. */
    suspend fun reviews(params: Map<String, String>? = null): CoCartResponse =
        http.get("products/reviews", params)

    /** The reviews of one product. */
    suspend fun productReviews(id: Int, params: Map<String, String> = emptyMap()): CoCartResponse =
        reviews(params + ("product" to id.toString()))

    /** The authenticated customer's product reviews (`GET products/reviews/mine`). Requires CoCart Starter. */
    suspend fun myReviews(params: Map<String, String>? = null): CoCartResponse {
        requiresStarter("myReviews")
        return http.get("products/reviews/mine", params)
    }

    /**
     * Post a product review: `product_id`, `review`, `rating` (1–5), and for a guest
     * `reviewer` and `reviewer_email`.
     */
    suspend fun createReview(review: Map<String, Any?>): CoCartResponse =
        http.post("products/reviews", review)

    // --- Shipping rates (CoCart Plus) ---

    /**
     * Shipping rates for a product before it's added to the cart (CoCart Plus). [id] may
     * be a variation ID or a SKU.
     *
     * Sent without the cart key: the route prices a simulated cart and refuses one
     * (`400 cocart_calculate_product_shipping_rates_cart_access_denied`). External and grouped
     * products can't be priced (`400`).
     *
     * @param quantity  Quantity to price shipping for. Default 1.
     * @param variation A variable product's attributes, e.g. `attribute_pa_color` to `blue`.
     * @param itemData  Custom item data, as sent when adding the item.
     */
    suspend fun shippingRates(
        id: String,
        quantity: Int? = null,
        variation: Map<String, String> = emptyMap(),
        itemData: Map<String, String> = emptyMap()
    ): CoCartResponse {
        val query = mutableMapOf<String, String>()
        quantity?.let { query["quantity"] = it.toString() }
        variation.forEach { (k, v) -> query["variation[$k]"] = v }
        itemData.forEach { (k, v) -> query["item_data[$k]"] = v }
        return http.getRawWithoutCartKey("${options.namespace}/v2/products/${encode(id)}/shipping-rates", query)
    }

    /** Shipping rates for a product by ID. See [shippingRates]. */
    suspend fun shippingRates(
        id: Int,
        quantity: Int? = null,
        variation: Map<String, String> = emptyMap(),
        itemData: Map<String, String> = emptyMap()
    ): CoCartResponse = shippingRates(id.toString(), quantity, variation, itemData)

    // --- SEO (CoCart SEO Pack) ---

    /** SEO metadata, Open Graph, Twitter card and Schema.org data for a product (CoCart SEO Pack). */
    suspend fun seo(id: Int): CoCartResponse = http.get("products/$id/seo")

    /** SEO data for a product by slug (CoCart SEO Pack). */
    suspend fun seoBySlug(slug: String): CoCartResponse = http.get("products/${encode(slug)}/seo")

    private fun requiresStarter(method: String) {
        if (options.mainPlugin == MainPlugin.LEGACY) {
            throw VersionException("$method() is only available with CoCart Starter")
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
