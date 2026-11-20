# Error Handling

When something goes wrong — a product doesn't exist, the customer isn't logged in, or the server has a problem — the SDK throws an **exception**. You catch them using `try/catch` blocks.

## Exception Hierarchy

The SDK uses a hierarchy of exception classes. A **hierarchy** means that `AuthException` and `ValidationException` are special types of `CoCartException` — so if you catch `CoCartException`, you catch all of them.

```
CoCartException (base)                    — any API error
├── AuthException                         — login / permission problems (401, 403)
│   └── TwoFactorAuthRequiredException    — 2FA code required to complete login (401)
├── ValidationException                   — bad input (400)
├── VersionException                      — method requires CoCart Starter (legacy mode)
├── NetworkException                      — connection / timeout failures
├── RateLimitException                    — 429 Too Many Requests
└── CheckoutOutcomeUnknownException       — placeOrder() got no answer, or cocart_payment_in_progress
```

All exceptions extend `CoCartException`, which extends `Exception`. Use `is` checks to handle different types:

## Catching Exceptions

```kotlin
import com.cocart.sdk.errors.*

try {
    client.cart().addItem(999, 1.0)
} catch (e: VersionException) {
    // Method requires CoCart Starter but SDK is in legacy mode
    println("Upgrade required: ${e.message}")
} catch (e: ValidationException) {
    // 400 — product not found, out of stock, invalid quantity, etc.
    println("Validation error: ${e.message}")
    println("Status code: ${e.statusCode}") // 400
} catch (e: TwoFactorAuthRequiredException) {
    // Handle 2FA before any other AuthException check
    println("2FA required — providers: ${e.availableProviders}")
} catch (e: AuthException) {
    // 401 or 403 — invalid credentials, expired token, forbidden
    println("Auth error: ${e.message}")
    println("Error code: ${e.errorCode}")
    println("Status code: ${e.statusCode}") // 401 or 403
} catch (e: RateLimitException) {
    // 429 Too Many Requests
    val retryAfter = e.retryAfter
    println("Rate limited. Retry after ${retryAfter}s")
} catch (e: NetworkException) {
    // Connection failure, timeout, DNS error
    println("Network error: ${e.message}")
} catch (e: CoCartException) {
    // Any other API error (404, 500, etc.)
    println("API error: ${e.message}")
    println("Status code: ${e.statusCode}")
}
```

## Exception Properties

All exceptions provide these properties:

| Property | Type | Description |
|---|---|---|
| `message` | `String` | The request's method and route, the API's message and its code, e.g. `POST /cocart/v2/checkout: Please enter a valid postcode. [cocart_invalid_address_fields]`. The route never includes the query string |
| `statusCode` | `Int?` | HTTP status code (400, 401, 403, 429, 500, etc.) |
| `data` | `Map<String, Any?>?` | Full API response body for debugging |

And these methods:

| Method | Returns | Description |
|---|---|---|
| `errorCode()` | `String?` | The API error code, e.g. `cocart_item_not_in_cart` |
| `getCart()` | `Map<String, Any?>?` | The cart sent with the error (see [Item No Longer in the Cart](#item-no-longer-in-the-cart)), or `null` |
| `getFields()` | `Map<String, String>` | Every field at fault, as path to message (`data.fields`), e.g. `billing.postcode` to "Enter a valid postcode.". Empty when the error has none |
| `getField()` | `String?` | The first field at fault (`data.field`), or `null` |
| `getReason()` | `String?` | The underlying plugin's own reason, when there is one (`data.reason`), or `null` |

`AuthException` also provides:

| Property | Type | Description |
|---|---|---|
| `errorCode` | `String?` | API error code (e.g. `cocart_authentication_error`) |

`RateLimitException` also provides:

| Property | Type | Description |
|---|---|---|
| `retryAfter` | `Long?` | Seconds to wait before retrying, from the `RateLimit-Retry-After` header CoCart Plus sends, or `Retry-After` |

`TwoFactorAuthRequiredException` also provides:

| Property | Type | Description |
|---|---|---|
| `availableProviders` | `List<String>` | Providers the user can choose from (e.g. `["totp", "email"]`) |
| `defaultProvider` | `String?` | The provider the server will use by default |
| `emailSent` | `Boolean` | Whether the server has already sent a code via email |

## Inspecting the Full API Response

Every `CoCartException` carries the full API response data for debugging:

```kotlin
try {
    client.cart().addItem(999, 1.0)
} catch (e: CoCartException) {
    println(e.data)
    // e.g. { "code" -> "cocart_product_not_found", "message" -> "...", "data" -> {...} }
}
```

## JWT Token Expiry

Check if a token is expired before making requests, or handle the error after:

```kotlin
val jwt = client.jwt()

// Proactive check
if (jwt.isTokenExpired()) {
    jwt.refresh()
}

// Or handle the error
try {
    client.cart().get()
} catch (e: AuthException) {
    if (jwt.hasTokens()) {
        jwt.refresh()
        client.cart().get() // Retry
    } else {
        throw e
    }
}
```

Or let the SDK handle it automatically:

```kotlin
val result = client.jwt().withAutoRefresh {
    client.cart().get()
}
```

See [Authentication](authentication.md#auto-refresh) for details.

## HTTP Status Code Mapping

| HTTP Status | Exception Thrown | Typical Causes |
|---|---|---|
| 400 | `ValidationException` | Invalid product ID, out of stock, invalid quantity, missing required fields |
| 401 | `AuthException` | Missing or invalid credentials |
| 401 | `TwoFactorAuthRequiredException` | Login succeeded but a 2FA code is required |
| 403 | `AuthException` | Expired JWT token, insufficient permissions |
| 404 | `CoCartException` | Endpoint not found, item key not found |
| 409 | `CoCartException` | A refused state: an order already paid (`cocart_order_invalid_status`), a payment still running (`cocart_payment_in_progress`), a changed total (`cocart_checkout_total_mismatch`) |
| 429 | `RateLimitException` | Too many requests |
| 5xx | `CoCartException` | Server error |
| Network failure | `NetworkException` | No connection, DNS failure, timeout |

A `409`, a `429` or a `5xx` is never a `ValidationException`, whatever its code says.

## Fields at Fault

CoCart Plus reports every field at fault at once (checkout, account details, addresses):

```kotlin
try {
    client.checkout().update(mapOf("billing_address" to address))
} catch (e: CoCartException) {
    e.getFields().forEach { (field, message) -> showFieldError(field, message) } // "billing.postcode" to "Enter a valid postcode."
    e.getField()  // the first one, or null
    e.getReason() // the plugin's own reason, or null
}
```

## Client-Side Validation Exceptions

The SDK validates certain inputs before making a network request. These throw `ValidationException` immediately — no HTTP call is made:

```kotlin
try {
    client.cart().addItem(-1, 0.0)
} catch (e: ValidationException) {
    // e.message  => "Product ID must be a positive integer"
    // e.statusCode is null — no HTTP request was made
}
```

Client-side validation checks:

| Method | Validation | Error Message |
|---|---|---|
| `addItem(id, qty)` | `id` must be a positive integer | "Product ID must be a positive integer" |
| `addItem(id, qty)` | `qty` must be greater than zero | "Quantity must be greater than zero" |
| `addVariation(id, qty, ...)` | Same as above | Same as above |
| `updateItem(key, qty)` | `qty` must be greater than zero | "Quantity must be greater than zero" |
| `find(id)` | `id` must be a positive integer | "Product ID must be a positive integer" |
| `variation(id, varId)` | Both IDs must be positive integers | "Product ID must be a positive integer" |

Standalone validation functions are also available for use in your own code:

```kotlin
import com.cocart.sdk.validation.*
import com.cocart.sdk.errors.ValidationException

try {
    validateProductId(-1)         // throws ValidationException
    validateQuantity(0.0)         // throws ValidationException
    validateEmail("not-an-email") // throws ValidationException
} catch (e: ValidationException) {
    println(e.message)
}
```

## Common Error Scenarios

### Product Not Found

```kotlin
try {
    client.cart().addItem(999999, 1.0)
} catch (e: ValidationException) {
    // e.message => "POST /cocart/v2/cart/add-item: Product not found. [cocart_product_not_found]"
}
```

### Out of Stock

```kotlin
try {
    client.cart().addItem(123, 100.0)
} catch (e: CoCartException) {
    println(e.data?.get("code")) // "cocart_not_enough_in_stock"
}
```

### Two-Factor Authentication Required

```kotlin
import com.cocart.sdk.errors.TwoFactorAuthRequiredException
import com.cocart.sdk.errors.AuthException

try {
    client.login("customer@email.com", "password")
} catch (e: TwoFactorAuthRequiredException) {
    // e.availableProviders => ["totp", "email"]
    // e.defaultProvider    => "totp"
    // e.emailSent          => false
    val code = promptUserForCode()
    client.jwt().loginWith2fa("customer@email.com", "password", code)
} catch (e: AuthException) {
    // Wrong password, missing plugin, etc.
    println("Auth failed: ${e.message}")
}
```

### CoCart Plugin Required

When calling account methods without the CoCart plugin installed:

```kotlin
try {
    client.account().getProfile()
} catch (e: CoCartException) {
    if (e.data?.get("code") == "cocart_plugin_required") {
        // CoCart plugin is not active on the store
    }
}
```

### Legacy Plugin Version Guard

When using `MainPlugin.LEGACY`, Basic-only methods throw immediately:

```kotlin
import com.cocart.sdk.errors.VersionException

val client = CoCart(context, "https://your-store.com", CoCartOptions(
    mainPlugin = MainPlugin.LEGACY
))

try {
    client.products().findBySlug("blue-hoodie")
} catch (e: VersionException) {
    // e.message => "findBySlug() requires CoCart Starter. Please upgrade..."
    // e.statusCode is null — no HTTP request was made
}
```

### Expired Cart Key

CoCart Starter 5 refuses a cart key with no cart session on every cart route (`404 cocart_invalid_cart`), for example after the cart expired. The client then forgets the stored key (in its storage too) and retries the request once as a new guest, so this error doesn't reach your code for a guest; a fresh cart key comes back as usual.

### Item No Longer in the Cart

Getting, updating or removing an item whose key isn't in the cart (for example, another tab already removed it) fails with `404 cocart_item_not_in_cart`. Since CoCart Starter 5 the error carries the cart as it is now, so you can show it without fetching the cart again:

```kotlin
try {
    client.cart().updateItem(itemKey, 2.0)
} catch (e: CoCartException) {
    if (e.errorCode() == "cocart_item_not_in_cart") {
        e.getCart()?.let { render(it) } // null with CoCart 4.x
    }
}
```

### Quantity Reduced to the Maximum

Adding more than the purchase limit or the stock allows isn't an error: CoCart adds the maximum and says so with a notice:

```kotlin
val response = client.cart().addItem(123, 50.0)
val notices = response.getNotices()["notice"] // ["The quantity of "Beanie" was reduced to 5, the most allowed in the cart."]
```

### Rate Limiting

With rate limiting on (CoCart Plus), every response carries `RateLimit-*` headers:

```kotlin
val rateLimit = response.getRateLimit()
// RateLimitInfo(limit=25, remaining=24, reset=1791108883, retryAfter=null)
```

`reset` is the Unix time the window resets, and `retryAfter` the seconds to wait after a 429. Exceeding the limit throws `RateLimitException`. The client retries a CoCart Starter request up to `maxRetries` times, waiting as long as `RateLimit-Retry-After` (or `Retry-After`) says, up to 60 seconds. A CoCart Plus request (checkout, account, JWT) is never sent again by itself, since it can take payment:

```kotlin
try {
    client.cart().get()
} catch (e: RateLimitException) {
    val wait = e.retryAfter ?: 60L
    println("Rate limited. Try again in ${wait}s")
    // delay(wait * 1000) and retry
}
```

### Network / Timeout Errors

```kotlin
val client = CoCart(context, "https://your-store.com", CoCartOptions(
    timeout = 10_000L // 10 seconds
))

try {
    client.cart().get()
} catch (e: NetworkException) {
    println("Network error: ${e.message}")
    // e.cause holds the original IOException
}
```
