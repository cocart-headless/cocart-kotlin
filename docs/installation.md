# Configuration & Setup

For installation instructions and requirements, see the [README](../README.md#installation).

## Configuration Options

The second and third arguments to `CoCart()` are the Android `Context` and an optional `CoCartOptions` object. You only need to include the options relevant to your setup — everything has sensible defaults.

```kotlin
val client = CoCart(
    context = this,
    siteURL = "https://your-store.com",
    options = CoCartOptions(
        // Guest session
        cartKey = "existing_cart_key",

        // Basic Auth
        username = "customer@email.com",
        password = "password",

        // JWT Auth
        jwtToken = "your-jwt-token",
        jwtRefreshToken = "your-refresh-token",

        // Admin (Sessions API)
        consumerKey = "ck_xxxxx",
        consumerSecret = "cs_xxxxx",

        // HTTP timeout in milliseconds (default: 30000)
        timeout = 30_000L,

        // REST API prefix (default: "wp-json")
        restPrefix = "wp-json",

        // API namespace (default: "cocart")
        namespace = "cocart",

        // CoCart main plugin: MainPlugin.BASIC (default) or MainPlugin.LEGACY
        mainPlugin = MainPlugin.BASIC,

        // Retry transient failures (default: 2)
        maxRetries = 2,

        // Storage adapter — defaults to EncryptedSharedPreferences when context is provided,
        // MemoryStorage when context is null. Pass a custom adapter to override.
        storage = null,
        storageKey = "cocart_cart_key",

        // Custom auth header name (default: "Authorization")
        // Useful when hosting strips the Authorization header
        authHeaderName = "X-Auth-Token",

        // Transform every API response before it's returned
        responseTransformer = null,

        // Enable ETag conditional requests for reduced bandwidth (default: true)
        etag = true,

        // Enable debug logging to Logcat (default: false)
        debug = true,
    )
)
```

### Fluent Configuration

A **fluent API** lets you chain multiple configuration methods in a single expression. Each method returns the client itself, so you can keep adding calls with dots:

```kotlin
val client = CoCart(context, "https://your-store.com")
    .setTimeout(60_000L)
    .setMaxRetries(3)
    .setRestPrefix("api")
    .setNamespace("mystore")
    .addHeader("X-Custom-Header", "value")
    .setAuthHeaderName("X-Auth-Token")
    .setETag(true)
    .setMainPlugin(MainPlugin.BASIC)
    .setDebug(true)
```

### No-Context Mode

If you don't have an Android `Context` (e.g. in unit tests or a non-Android module), pass `null`. The SDK automatically falls back to `MemoryStorage` instead of `EncryptedSharedPreferences`:

```kotlin
// Tests / non-Android environment
val client = CoCart(null, "https://your-store.com")
```

## White-Labelling / Custom REST Prefix

WordPress exposes its REST API at `/wp-json/` by default. The SDK builds URLs like `https://your-store.com/wp-json/cocart/v2/cart`. If your site or hosting changes this prefix, or if the CoCart plugin has been renamed (white-labelled), configure the SDK to match:

```kotlin
// Custom REST prefix (site uses /api/ instead of /wp-json/)
val client = CoCart(context, "https://your-store.com", CoCartOptions(
    restPrefix = "api"
))
// Requests go to: https://your-store.com/api/cocart/v2/cart

// White-labelled namespace
val client2 = CoCart(context, "https://your-store.com", CoCartOptions(
    namespace = "mystore"
))
// Requests go to: https://your-store.com/wp-json/mystore/v2/cart

// Both together
val client3 = CoCart(context, "https://your-store.com", CoCartOptions(
    restPrefix = "api",
    namespace = "mystore"
))
// Requests go to: https://your-store.com/api/mystore/v2/cart
```

## Legacy Plugin Support

The SDK supports both **CoCart Basic** and the **legacy CoCart plugin** (`cart-rest-api-for-woocommerce` v4.x). By default, the SDK targets CoCart Basic.

To use the SDK with the legacy plugin, set `mainPlugin` to `MainPlugin.LEGACY`:

```kotlin
val client = CoCart(context, "https://your-store.com", CoCartOptions(
    mainPlugin = MainPlugin.LEGACY
))

// Or use the fluent setter
client.setMainPlugin(MainPlugin.LEGACY)
```

### What changes in legacy mode

**Basic-only methods throw immediately.** Methods that require CoCart Basic will throw a `VersionException` before making any HTTP request:

```kotlin
import com.cocart.sdk.errors.VersionException

val client = CoCart(context, "https://your-store.com", CoCartOptions(
    mainPlugin = MainPlugin.LEGACY
))

try {
    client.products().findBySlug("blue-hoodie")
} catch (e: VersionException) {
    // "findBySlug() requires CoCart Basic. Please upgrade..."
}
```

Basic-only methods include:

- `products().findBySlug()`
- `products().variation()`, `category()`, `tag()`
- `products().brands()`, `brand()`, `byBrand()`
- `products().myReviews()`
- `cart().getFees()`, `addFee()`, `removeFees()`

**Field filtering uses `fields` instead of `_fields`.** The legacy plugin uses CoCart's custom `fields` query parameter, while CoCart Basic uses the WordPress standard `_fields`. The SDK handles this automatically — `cart().getFiltered()` sends the correct parameter based on the configured main plugin.
