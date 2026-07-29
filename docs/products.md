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
```

Common filter parameters:

| Parameter | Description |
|---|---|
| `per_page` | Number of results per page (default: 10, max: 100) |
| `page` | Page number |
| `orderby` | Sort by: `date`, `price`, `popularity`, `rating`, `title` |
| `order` | `asc` or `desc` |
| `category` | Filter by category slug |
| `tag` | Filter by tag slug |
| `on_sale` | `"true"` to show only sale products |
| `featured` | `"true"` to show only featured products |
| `min_price` | Minimum price filter |
| `max_price` | Maximum price filter |
| `search` | Search by keyword |

## Find a Product by ID or SKU

`find()` accepts either the numeric product/variation ID or the product's SKU — either one works the same way.

```kotlin
val response = client.products().find(123)

// Or by SKU
val response = client.products().find("PCT-2024")
```

## Find a Product by Slug

Available with **CoCart Starter** only. Throws `VersionException` in legacy mode.

```kotlin
val response = client.products().findBySlug("blue-hoodie")
```

## Product Variations

Fetch a specific variation of a variable product:

```kotlin
val response = client.products().variation(productId = 456, variationId = 789)
```

## Browse by Category

```kotlin
val response = client.products().category("hoodies")
```

## Browse by Tag

```kotlin
val response = client.products().tag("sale")
```

## Brands

Available with **CoCart Starter** only.

```kotlin
// All brands
val response = client.products().brands()

// Single brand by slug
val response = client.products().brand("nike")

// Products by brand slug
val response = client.products().byBrand("nike")
```

## My Reviews

Returns reviews written by the currently authenticated customer. Requires Basic Auth or JWT.

Available with **CoCart Starter** only.

```kotlin
val response = client.products().myReviews()
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
response.get("products.0.price")

// Check existence
response.has("products")

// Raw data
response.toMap()
response.toJson()
```
