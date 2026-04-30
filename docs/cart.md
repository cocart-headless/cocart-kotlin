# Cart API

The Cart API handles all shopping cart operations — adding items, updating quantities, applying coupons, managing shipping, and more. This is the core of any headless WooCommerce storefront.

**How cart sessions work:**

- **Guest customers** — The first request creates a new guest session. The server returns a `Cart-Key` which the SDK extracts and stores automatically. All subsequent requests use this key.
- **Authenticated customers** — The server identifies the cart by the WordPress user account. No cart key is needed.

To access cart methods, call `client.cart()`:

```kotlin
val cart = client.cart()
```

## Create Cart

Create a new guest cart session without adding items. Only available for non-authenticated (guest) users.

```kotlin
val response = client.cart().create()
println(response.get("cart_key")) // "guest_abc123..."
```

## Get Cart

```kotlin
val response = client.cart().get()

// With parameters
val response = client.cart().get(mapOf(
    "thumb" to "true",    // Include product thumbnails
    "default" to "true"   // Return default cart data
))
```

### Field Filtering

If you only need a subset of the cart data, `getFiltered()` tells the server to return only the fields you list — less data transferred, faster responses:

```kotlin
val response = client.cart().getFiltered(listOf("items", "totals"))
```

## Client-Side Validation

The SDK checks your inputs before sending anything to the server. If you pass an invalid product ID or a quantity of `0`, it throws `ValidationException` immediately — no network call is made:

```kotlin
import com.cocart.sdk.errors.ValidationException

try {
    client.cart().addItem(-1, 0.0)
} catch (e: ValidationException) {
    // e.message => "Product ID must be a positive integer"
    // No network request was made
}
```

Validation rules:

- **Product ID** — Must be a positive integer (`addItem`, `addVariation`)
- **Quantity** — Must be a positive number (`addItem`, `addVariation`, `updateItem`)

## Adding Items

### Add a Simple Product

```kotlin
val response = client.cart().addItem(123, 2.0)

// Shorthand
val response = client.cart().add(123, 2.0)
```

### Add with Options

```kotlin
val response = client.cart().addItem(123, 1.0, mapOf(
    "item_data" to mapOf(
        "gift_message" to "Happy Birthday!",
        "engraving" to "John"
    ),
    "return_item" to true
))
```

### Add a Variable Product

A **variable product** is a product with options like size or color. Specify which variation the customer chose using the `attributes` parameter:

```kotlin
val response = client.cart().addVariation(456, 1.0, mapOf(
    "attribute_pa_color" to "blue",
    "attribute_pa_size" to "large"
))
```

### Add Multiple Items at Once

```kotlin
val response = client.cart().addItems(listOf(
    mapOf("id" to 123, "quantity" to 2.0),
    mapOf("id" to 456, "quantity" to 1.0, "variation" to mapOf(
        "attribute_pa_color" to "red"
    )),
    mapOf("id" to 789, "quantity" to 3.0)
))
```

## Updating Items

Every item in the cart has a unique **item key** — a string like `abc123def456...` that identifies that specific item. You receive item keys in cart responses in the `item_key` field of each item.

### Update Item Quantity

```kotlin
client.cart().updateItem("abc123def456", 3.0)
```

### Update Multiple Items

```kotlin
client.cart().updateItems(listOf(
    mapOf("item_key" to "abc123", "quantity" to 2.0),
    mapOf("item_key" to "def456", "quantity" to 5.0)
))
```

## Removing Items

### Remove a Single Item

```kotlin
client.cart().removeItem("abc123def456")
```

### Remove Multiple Items

```kotlin
client.cart().removeItems(listOf("abc123", "def456"))
```

### Restore a Removed Item

```kotlin
client.cart().restoreItem("abc123def456")
```

### Get Removed Items

```kotlin
val removed = client.cart().getRemovedItems()
```

## Clear Cart

```kotlin
// Both are equivalent
client.cart().clear()
client.cart().empty()
```

## Cart Totals & Info

```kotlin
// Get all totals
val totals = client.cart().getTotals()
val totals = client.cart().getTotals(formatted = true) // HTML-formatted strings

// Item count
val count = client.cart().getItemCount()

// All items
val items = client.cart().getItems()

// Single item
val item = client.cart().getItem("abc123def456")

// Cross-sells
val crossSells = client.cart().getCrossSells()
```

## Calculate & Update

```kotlin
// Recalculate cart totals
client.cart().calculate()

// Update cart-level data
client.cart().update(mapOf(
    "billing" to mapOf("first_name" to "John")
))
```

## Coupons (CoCart Plus)

```kotlin
// Apply a coupon
client.cart().applyCoupon("SAVE10")

// Remove a coupon
client.cart().removeCoupon("SAVE10")

// Get applied coupons
val coupons = client.cart().getCoupons()

// Validate applied coupons
val check = client.cart().checkCoupons()
```

## Customer

```kotlin
// Get customer data attached to the cart
val customer = client.cart().getCustomer()

// Update billing and/or shipping address
client.cart().updateCustomer(
    billing = mapOf(
        "first_name" to "John",
        "last_name" to "Doe",
        "email" to "john@example.com",
        "address_1" to "123 Main St",
        "city" to "New York",
        "state" to "NY",
        "postcode" to "10001",
        "country" to "US"
    ),
    shipping = mapOf(
        "first_name" to "John",
        "address_1" to "123 Main St"
    )
)
```

## Shipping

```kotlin
// Get available shipping methods
val methods = client.cart().getShippingMethods()

// Calculate shipping for an address
client.cart().calculateShipping(mapOf(
    "country" to "US",
    "state" to "NY",
    "postcode" to "10001",
    "city" to "New York"
))

// Set the selected shipping method (CoCart Plus)
client.cart().setShippingMethod(mapOf(
    "key" to "flat_rate:1",
    "value" to "Flat Rate"
))
```

## Fees (CoCart Plus)

```kotlin
// Get all fees
val fees = client.cart().getFees()

// Add a fee
client.cart().addFee("handling", 5.0, taxable = false)

// Remove all fees
client.cart().removeFees()
```

## ETag / Conditional Requests

The SDK automatically sends `If-None-Match` headers on repeat `GET` requests. When cart data hasn't changed, the server responds with `304 Not Modified` and the SDK returns a response with `isNotModified() = true` — saving bandwidth:

```kotlin
val response = client.cart().get()

if (response.isNotModified()) {
    // Cart hasn't changed since last request — use cached data
} else {
    val items = response.getItems()
}
```

Disable ETags if you always need fresh data:

```kotlin
client.setETag(false)

// Clear the ETag cache manually
client.clearETagCache()
```

## Response Helpers

Every cart response provides convenience helpers:

```kotlin
val response = client.cart().get()

response.getItems()       // List<Any?> — items in the cart
response.getTotals()      // Map<String, Any?> — all totals
response.getItemCount()   // Int — number of items
response.getCartKey()     // String? — active cart key
response.getCartHash()    // String? — hash for cache busting
response.getNotices()     // List<Any?> — WooCommerce notices
response.getCurrency()    // Map<String, Any?>? — currency data
response.getCacheStatus() // "HIT" | "MISS" | "SKIP" | null
response.isNotModified()  // true if 304 Not Modified

// Dot-notation access
response.get("totals.total")
response.get("items.0.name")
response.has("coupons")

// Raw data
response.toMap()
response.toJson()
```
