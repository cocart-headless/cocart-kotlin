# Products API

The Products API lets you browse and search the WooCommerce product catalogue. No authentication is required for public product data.

To access product methods, call `client.products()`:

```kotlin
val products = client.products()
```

## List All Products

```kotlin
// All products with default pagination
val response = client.products().all()

// With filters
val response = client.products().all(mapOf(
    "per_page" to "12",
    "page" to "1",
    "orderby" to "popularity",
    "order" to "desc",
    "status" to "publish"
))

// The list is under `products`, with the page count alongside
val products = response.get("products") as List<*>
val totalPages = response.get("total_pages")
```

Product responses carry a lot for headless pages (related products, embedded variations with price and stock, upsells, cross-sells, stock, extension meta). For a listing that needs less, ask for just those fields; CoCart then skips building the rest:

```kotlin
val response = client.products().all(mapOf("per_page" to "20", "_fields" to "id,name,slug,prices,images,add_to_cart"))
```

Prices are minor-unit strings under `prices` (`"1800"` for 18.00; see `CurrencyFormatter` in [Utilities](utilities.md)), image `src` is a map of sizes (`thumbnail`, `medium`, `full`…), and `attributes` is keyed by attribute (`attribute_pa_color`).

Common filter parameters:

| Parameter | Description |
|---|---|
| `per_page` | Number of results per page (default: 10, max: 100) |
| `page` | Page number |
| `orderby` | Sort order: `date`, `id`, `menu_order`, `include`, `title`, `slug`, `name`, `alphabetical`, `reverse_alpha`, `popularity`, `sales`, `rating`, `review_count`, `by_stock`, `on_sale_first`, `featured_first`, `price_asc`, `price_desc`; `relevance` and `rand` need CoCart Starter. Sort by price with `price_asc` or `price_desc`: CoCart rejects `price` |
| `order` | `asc` or `desc` |
| `category` | Filter by category slug |
| `tag` | Filter by tag slug |
| `on_sale` | `"true"` to show only sale products |
| `featured` | `"true"` to show only featured products |
| `min_price` | Minimum price filter |
| `max_price` | Maximum price filter |
| `search` | Search by keyword |
| `brand` | Filter by brand slug (CoCart Starter) |
| `stock_status` | `instock`, `outofstock` or `onbackorder` |
| `_fields` | Comma-separated fields to return |

## Filtering Shortcuts

Each shortcut adds its filter to any others you pass:

```kotlin
val response = client.products().search("hoodie")
val response = client.products().byCategory("hoodies", mapOf("per_page" to "20"))
val response = client.products().byTag("sale")
val response = client.products().featured()
val response = client.products().onSale()
val response = client.products().byPriceRange(minPrice = "10", maxPrice = "50")
val response = client.products().byStockStatus(StockStatus.IN_STOCK)
val response = client.products().paginate(page = 2, perPage = 12)

// Cheapest first (CoCart rejects plain "price": use PRICE_ASC or PRICE_DESC)
val response = client.products().sortBy(ProductOrderBy.PRICE_ASC)
val response = client.products().sortBy(ProductOrderBy.POPULARITY, SortOrder.DESC)
```

`ProductOrderBy`, `SortOrder` and `StockStatus` are in `com.cocart.sdk.resources`.

## Find a Product by ID or SKU

`find()` accepts either the numeric product/variation ID or the product's SKU — either one works the same way.

```kotlin
val response = client.products().find(123)

// Or by SKU
val response = client.products().find("PCT-2024")

// With only some fields
val response = client.products().find(123, mapOf("_fields" to "id,name,prices"))

println(response.get("prices.price")) // minor units, e.g. "1800"
```

## Find a Product by Slug

Available with **CoCart Starter** only. Throws `VersionException` in legacy mode.

```kotlin
val response = client.products().findBySlug("blue-hoodie")
```

## Product Variations

```kotlin
// All variations of a variable product
val response = client.products().variations(456)

// A specific variation (CoCart Starter)
val response = client.products().variation(id = 456, variationId = 789)
```

## Categories and Tags

```kotlin
val categories = client.products().categories(mapOf("per_page" to "50"))
val category = client.products().category(20)  // by ID (CoCart Starter)

val tags = client.products().tags()
val tag = client.products().tag(8)              // by ID (CoCart Starter)
```

To list the products in a category or with a tag, use `byCategory()` or `byTag()` with its slug (see [Filtering Shortcuts](#filtering-shortcuts)).

Categories, tags, attributes, attribute terms, reviews and variations come back as a list: read it with `toList()`, or with an index in `get()`:

```kotlin
val categories = client.products().categories()
categories.toList().size
categories.get("0.name") // "Accessories"
```

## Attributes

```kotlin
// All attributes, and one by ID
val attributes = client.products().attributes()
val attribute = client.products().attribute(1)

// An attribute's terms (e.g. every color), and one term
val terms = client.products().attributeTerms(1)
val term = client.products().attributeTerm(1, 5)

// The same by slug (CoCart Starter)
val color = client.products().attributeBySlug("color")
val colors = client.products().attributeTermsBySlug("color")
val blue = client.products().attributeTermBySlug("color", "blue")
```

## Brands

Available with **CoCart Starter** only.

```kotlin
// All brands
val response = client.products().brands()

// Single brand by ID
val response = client.products().brand(5)

// Products by brand slug
val response = client.products().byBrand("nike")
```

## Reviews

```kotlin
// All reviews
val response = client.products().reviews()

// One product's reviews
val response = client.products().productReviews(123)
```

## My Reviews

Returns reviews written by the currently authenticated customer. Requires Basic Auth or JWT.

Available with **CoCart Starter** only.

```kotlin
val response = client.products().myReviews()
```

## Post a Review

```kotlin
client.products().createReview(mapOf(
    "product_id" to 123,
    "review" to "Warm and well made.",
    "rating" to 5,
    "reviewer" to "Jane",                  // guests
    "reviewer_email" to "jane@example.com"
))
```

## Shipping Rates for a Product

Available with **CoCart Plus**. Shipping rates for a product before it's added to the cart, priced for the store's (or the customer's) location. Pass a variation ID, or the parent with the variation's attributes:

```kotlin
val response = client.products().shippingRates(154, quantity = 2)
val response = client.products().shippingRates(134, variation = mapOf(
    "attribute_pa_color" to "blue",
    "attribute_logo" to "Yes"
))

(response.get("packages") as Map<*, *>).values.forEach { pkg ->
    ((pkg as Map<*, *>)["rates"] as Map<*, *>).values.forEach { rate ->
        rate as Map<*, *>
        println("${rate["label"]}: ${rate["cost"]}") // cost in minor units
    }
}
```

The request is sent without the cart key (the route prices a simulated cart and refuses one with `400 cocart_calculate_product_shipping_rates_cart_access_denied`). External and grouped products can't be priced (`400`).

## SEO Data

Requires the CoCart SEO Pack plugin. SEO metadata, Open Graph, Twitter card and Schema.org data for a product:

```kotlin
val response = client.products().seo(123)
val response = client.products().seoBySlug("blue-hoodie")

response.get("meta_data.meta_title")
response.get("schema")
```

## Client-Side Validation

Product ID validation runs before any network request:

```kotlin
import com.cocart.sdk.errors.ValidationException

try {
    client.products().find(0)
} catch (e: ValidationException) {
    // e.message => "Product ID must be a positive integer"
}
```

## Response Helpers

```kotlin
val response = client.products().all()

// Dot-notation access
response.get("products.0.name")
response.get("products.0.prices.price") // minor units, e.g. "1800"

// Check existence
response.has("products")

// Raw data
response.toMap()
response.toJson()
```
