# CoCart Android SDK

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue?style=for-the-badge&labelColor=000000)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/Min_SDK-API_26-brightgreen?style=for-the-badge&labelColor=000000)](https://developer.android.com/about/versions/oreo)
[![Tests](https://img.shields.io/github/actions/workflow/status/cocart-headless/cocart-kotlin/tests.yml?label=tests&style=for-the-badge&labelColor=000000)](https://github.com/cocart-headless/cocart-kotlin/actions/workflows/tests.yml)
[![License](https://img.shields.io/github/license/cocart-headless/cocart-kotlin?color=9cf&style=for-the-badge&labelColor=000000)](https://github.com/cocart-headless/cocart-kotlin/blob/main/LICENSE)

Official Android SDK for the [CoCart](https://cocartapi.com) REST API. Build **headless WooCommerce storefronts** on Android — your app talks to WooCommerce through our API, giving you full control over the shopping experience.

> [!IMPORTANT]
> This SDK is looking for feedback. If you experience a bug please report it.

## TODO to complete the SDK

* [ ] Add SDK docs to documentation site
* [ ] Add support for Cart API extras
* [x] Add Checkout API support — implemented ([`client.checkout()`](docs/checkout.md)); needs CoCart Plus 2.0, which isn't released yet
* [x] Add Customers Account API support — implemented ([`client.account()`](docs/account.md)); needs CoCart Plus 2.0, which isn't released yet

---

## Requirements

- **Android API 26+** (Android 8.0 Oreo) — The minimum Android version required. You can check your `minSdk` in your app's `build.gradle.kts`.
- **Kotlin 2.0+** — The SDK is written in Kotlin and uses coroutines for async operations.
- **CoCart plugin** installed on your WooCommerce store — The WordPress plugin that provides the REST API endpoints the SDK communicates with.
- **CoCart Plus 2.0** for the account and checkout APIs (not released yet) — The add-on plugin that provides customer accounts, checkout and payment. The cart and product APIs don't need it.
- [CoCart JWT Authentication](https://wordpress.org/plugins/cocart-jwt-authentication/) plugin for JWT features (optional) — Only needed if you want to use JSON Web Token authentication (explained in the [Authentication](docs/authentication.md) guide).

## Support Policy

See [SUPPORT.md](SUPPORT.md) for our versioning policy, supported Android API levels, and support lifecycle.

## Features

- **OkHttp 5.x** — battle-tested Android HTTP client with connection pooling
- **Coroutines** — all network calls are `suspend` functions; no callbacks or threading boilerplate
- **Encrypted session storage** — cart keys and tokens persisted via `EncryptedSharedPreferences` (AES-256-GCM)
- **Client-side input validation** — catches errors before network requests
- **Currency formatting** — format prices with symbol, position, decimal and thousand separators
- **Event system** — request/response/error lifecycle hooks
- **Response transformer** — custom processing of every API response
- **Configurable auth header name** — for proxies that strip `Authorization`
- **JWT authentication** with auto-refresh and 2FA support
- **ETag / conditional requests** — reduced bandwidth on repeat requests (responses marked `no-store` or `private` are never cached)
- **Retry with backoff** — configurable automatic retry on transient failures, honouring `RateLimit-Retry-After`
- **Legacy CoCart plugin support** — version-aware endpoint guards

## Installation

### Via Maven Central

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.cocart:sdk:1.0.0")
}
```

### Via GitHub Packages

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/cocart-headless/cocart-kotlin-sdk")
    }
}

dependencies {
    implementation("com.cocart:sdk:1.0.0")
}
```

## Quick Start

An **SDK** (Software Development Kit) is a library that provides ready-made functions for talking to a specific service — in this case, the CoCart REST API on your WooCommerce store. Instead of writing raw HTTP requests yourself, you call simple methods like `client.cart().addItem(123, 2)` and the SDK handles the details.

All network methods are `suspend` functions and must be called from a coroutine scope. In Android, use `lifecycleScope` or `viewModelScope`:

```kotlin
import com.cocart.sdk.CoCart

// Create a client pointing to your WooCommerce store
val client = CoCart(context, "https://your-store.com")

// Inside a coroutine (e.g. viewModelScope.launch { ... })

// Browse products — no auth required
val products = client.products().all(mapOf("per_page" to "12"))

// Add to cart — guest session created automatically
val response = client.cart().addItem(123, 2)

// Get cart
val cart = client.cart().get()
println(cart.getItems())              // List of items in the cart
println(cart.get("totals.total"))     // Dot-notation access to nested data
```

> **Note:** `suspend` functions must be called from a coroutine. In an Activity or Fragment use `lifecycleScope.launch { }`. In a ViewModel use `viewModelScope.launch { }`:
>
> ```kotlin
> viewModelScope.launch {
>     val cart = client.cart().get()
>     // update UI
> }
> ```

## Documentation

| Guide | Description |
|-------|-------------|
| [Configuration & Setup](docs/installation.md) | Options, fluent config, white-labelling, legacy plugin support |
| [Authentication](docs/authentication.md) | Guest, Basic Auth, JWT, 2FA, consumer keys |
| [Cart API](docs/cart.md) | Add, update, remove items, coupons, shipping, fees |
| [Products API](docs/products.md) | List, filter, search, sort, variations, categories, tags, attributes, brands, reviews, shipping rates, SEO |
| [Account API](docs/account.md) | Registration, customer profile, password, address, orders, downloads, reviews and subscriptions (needs CoCart Plus 2.0, not released yet) |
| [Checkout API](docs/checkout.md) | Place orders and take payment, 3D Secure, payments made outside the checkout, saved payment methods, the thank-you page, paying for unpaid orders, finding out how an order ended (needs CoCart Plus 2.0, not released yet) |
| [Sessions API](docs/sessions.md) | Admin sessions, storage adapters |
| [Error Handling](docs/error-handling.md) | Error hierarchy, catching errors, fields at fault, common scenarios, expired carts, rate limits |
| [Utilities](docs/utilities.md) | Currency formatter, response transformer |
| [Extension Data](docs/extension-data.md) | Plugin data under `extensions` (CoCart Plus integrations), and sending a plan, gift or warranty choice |

## Features

### Fluent API

Configure the client with method chaining — each setter returns the client itself:

```kotlin
val client = CoCart(context, "https://your-store.com")
    .setTimeout(15_000L)
    .setMaxRetries(2)
    .addHeader("X-Custom", "value")
```

### Dot-Notation Response Access

Access nested data in API responses using a simple string path with dots:

```kotlin
val cart = client.cart().get()
cart.get("totals.total")          // nested object
cart.get("currency.currency_code")
cart.get("items.0.name")          // array item by index
```

### Currency Formatting

```kotlin
import com.cocart.sdk.utilities.CurrencyFormatter

val fmt = CurrencyFormatter()
val currency = response.getCurrency()!!

fmt.format(4599L, currency)        // "$45.99"
fmt.formatDecimal(4599L, currency) // "45.99"
```

### Client-Side Validation

Invalid inputs are caught before making a network request:

```kotlin
// Throws ValidationException immediately — no network call
client.cart().addItem(-1, 0.0)
// ValidationException: "Product ID must be a positive integer"
```

### Checkout (CoCart Plus 2.0)

```kotlin
// On the review screen: snapshots the total the customer sees.
val checkout = client.checkout().get()

// Then, when the customer pays. payment_data carries the gateway's tokens only.
val result = client.checkout().placeOrder(mapOf(
    "billing_address" to billingAddress,
    "payment_method" to "stripe",
    "payment_data" to mapOf("wc-stripe-payment-method" to paymentMethod.id),
))

when {
    result.isComplete -> showThankYou(client.checkout().getOrderReceived(result.orderId!!, result.orderKey!!))
    result.isAwaitingPayment -> payOutsideCheckout(result)   // a wallet, a voucher, BLIK…
    result.needsConfirmation -> confirmCard(result.clientSecret!!) // then place the order again
    result.needsRedirect -> openOffSite(result.redirectUrl!!)      // then confirmReturn()
    result.isPending -> confirmAgainShortly()
    else -> showError(result.message)                              // plain text; pay again with payForOrder()
}
```

`result.code` is `order_placed`, `action_required`, `payment_pending` or `payment_failed`. When no answer comes back, or another request is still paying (`cocart_payment_in_progress`), `placeOrder()` throws `CheckoutOutcomeUnknownException`, and `checkout().getStatus(e.checkoutId).code` says `order_placed`, `payment_required`, `in_progress` or `not_placed`. Saved payment methods come from `getSavedPaymentMethods()`, each with a `label` such as "Visa ending in 4242". See [Checkout API](docs/checkout.md).

### Event System

```kotlin
client.on(CoCartEvent.REQUEST)  { payload -> println("${payload["method"]} ${payload["url"]}") }
client.on(CoCartEvent.RESPONSE) { payload -> println("Status: ${payload["status"]}") } // payload["response"] is the CoCartResponse
client.on(CoCartEvent.ERROR)    { payload -> println("Error: ${payload["error"]}") }
```

The `url` in an event is the full URL, query string included: it can carry the cart key, an order key or an email. Don't forward it as it is (to analytics, for example). Debug output (`debug = true`) shows every query value as `***`.

### Encrypted Session Storage

Cart keys and tokens are stored in `EncryptedSharedPreferences` automatically when a `Context` is provided:

```kotlin
// Uses EncryptedSharedPreferences — no extra setup needed
val client = CoCart(context, "https://your-store.com")

// Restore persisted cart key on app start
client.restoreSession()
```

### JWT with Auto-Refresh

```kotlin
// Auto-refresh handles token expiry transparently
val result = client.jwt().withAutoRefresh {
    client.cart().get()
}
```

## CoCart Channels

We have different channels at your disposal where you can find information about the CoCart project, discuss it and get involved:

[![Twitter: cocartapi](https://img.shields.io/twitter/follow/cocartapi?style=social)](https://twitter.com/cocartapi) [![CoCart GitHub Stars](https://img.shields.io/github/stars/cocart-headless/cocart-kotlin-sdk?style=social)](https://github.com/cocart-headless/cocart-kotlin-sdk)

<ul>
  <li>📖 <strong>Documentation</strong>: this is the place to learn how to use CoCart API. <a href="https://cocartapi.com/docs/?utm_medium=gh&utm_source=github&utm_campaign=readme&utm_content=cocart">Get started!</a></li>
  <li>👪 <strong>Community</strong>: use our Discord chat room to share any doubts, feedback and meet great people. This is your place too to share <a href="https://cocartapi.com/community/?utm_medium=gh&utm_source=github&utm_campaign=readme&utm_content=cocart">how are you planning to use CoCart!</a></li>
  <li>🐞 <strong>GitHub</strong>: we use GitHub for bugs and pull requests, doubts are solved with the community.</li>
  <li>🐦 <strong>Social media</strong>: a more informal place to interact with CoCart users, reach out to us on <a href="https://twitter.com/cocartapi">X/Twitter.</a></li>
</ul>

## Credits

Website [cocartapi.com](https://cocartapi.com/?ref=github) &nbsp;&middot;&nbsp;
GitHub [@cocart-headless](https://github.com/cocart-headless) &nbsp;&middot;&nbsp;
X/Twitter [@cocartapi](https://twitter.com/cocartapi) &nbsp;&middot;&nbsp;
[Facebook](https://www.facebook.com/cocartforwc/) &nbsp;&middot;&nbsp;
[Instagram](https://www.instagram.com/cocartheadless/)

## License

MIT
