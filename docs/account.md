# Account API

The Account API provides access to customer-specific data — registration, profile, password, address, order history, downloads, reviews and subscriptions. All methods require the customer to be authenticated with Basic Auth or JWT, except `register()`, the guest order lookups and `requestOrderWithdrawal()`.

> **Note:** The Account API is fully supported by this SDK, but the Account API
> itself is not yet available in a released version of the CoCart plugin — it's
> coming in a future release. This documentation is ready for when it ships.

To access account methods, call `client.account()`:

```kotlin
val account = client.account()
```

> **Note:** All account routes call `cocart/v2/my-account/...` directly, bypassing the configurable namespace and REST prefix. This is by design — the Account API always lives at that fixed path.

Every account document has `object` (`customer`, `order`, `subscription`…), `actions` (what the customer can do next) and, after a change, `result` with what happened (`{code, message}`: `account_created`, `account_updated`…). The methods that read a document take optional `formatted` (amounts formatted for display) and `meta_keys` (only these keys in `meta`; a list is comma-joined) parameters.

## Register

Registers a new customer account. Unlike the other account methods, `register()` doesn't need the customer to be logged in: it's the entry point before authentication.

```kotlin
val response = client.account().register(
    email = "jane@example.com",
    username = "jane",               // optional if the store generates usernames from email
    password = "a-strong-password"  // optional if the store generates passwords
)

response.statusCode           // 201
response.get("result.code")   // "account_created"
response.get("id")            // 42
response.get("username")      // "jane"

// Log in next.
```

The response is the new customer, as `getProfile()` gives it. The username and password are sent as `requested_username` and `requested_password`, not `username` and `password`: CoCart treats a request with non-empty `username`/`password` fields as a Basic Auth attempt, which would reject the registration.

| Status | Code | Meaning |
|---|---|---|
| `400` | `cocart_invalid_email` | Email missing or invalid |
| `400` | `cocart_registration_invalid_username` | The username isn't valid |
| `400` | `cocart_registration_reserved_username` | The username is on the store's reserved list (e.g. `admin`) |
| `400` | `cocart_registration_missing_password` | The store needs a password and none was given |
| `400` | `cocart_registration_weak_password` | The password is too weak |
| `400` | `cocart_account_creation_failed` | WooCommerce couldn't create the account |
| `409` | `cocart_account_exists` | The email is already registered |
| `409` | `cocart_registration_username_exists` | The username is already taken |

## Profile

### Get Profile

The signed-in customer: name, email, addresses, what they've bought and what they can do next.

```kotlin
val customer = client.account().getProfile()

customer.get("first_name")               // "Jane"
customer.get("email")                    // the email the account logs in with
customer.get("pending_email")            // a new email waiting to be confirmed, or null
customer.get("stats.orders_count")       // 14
customer.get("stats.total_spent")        // "32000" (minor units; formatted = true for "$320.00")
customer.get("billing.formatted")        // the billing address on one line
customer.get("related.recent_order")     // the most recent order, or null

client.account().getProfile(mapOf("formatted" to true))
```

### Update Profile

Saves the account details. Send only what changes. It returns the customer with `account_updated` in `result`, and every field at fault is reported at once:

```kotlin
try {
    val customer = client.account().updateProfile(mapOf(
        "account_first_name" to "Jane",
        "account_email" to "jane.doe@example.com"
    ))
    customer.get("pending_email") // set when the store confirms a new email first (then verifyEmail())
} catch (e: CoCartException) {
    e.getFields().forEach { (field, message) -> showFieldError(field, message) }
}
```

| Field | Description |
|---|---|
| `account_first_name` | First name |
| `account_last_name` | Last name |
| `account_display_name` | Display name |
| `account_email` | Email address |

### Change Password

You pass readable names and the SDK sends WooCommerce's field names (`password_current`, `password_1`, `password_2`), and nothing else:

```kotlin
client.account().changePassword(
    current = "old_password",
    password = "new_password",
    confirm = "new_password"
)
```

It's one `POST my-account` with only the password fields; the rest of the account is left as it is. A wrong current password is a `ValidationException` with `errorCode()` `cocart_account_incorrect_current_password`; passwords that don't match are `cocart_account_new_passwords_do_not_match`.

### Update Address

Saves the customer's billing and/or shipping address, and returns the customer. Fields are unprefixed:

```kotlin
val address = mapOf("first_name" to "Jane", "address_1" to "1 Main St", "city" to "Paris", "postcode" to "75001", "country" to "FR")
client.account().updateAddress(mapOf(
    "billing_address" to address,
    "shipping_address" to address
))
```

Both addresses are checked before either is saved; every field at fault is in `e.getFields()` (`cocart_invalid_address_fields`).

## Orders

### Get All Orders

A page of the customer's orders, newest first, each as a summary, with `pagination` (also in the `X-WP-Total` and `X-WP-TotalPages` headers):

```kotlin
val response = client.account().getOrders()

(response.get("orders") as List<*>).forEach { order ->
    order as Map<*, *>
    order["number"]                          // "1042"
    (order["status"] as Map<*, *>)["code"]   // "completed" (use this in code; "label" is for display)
}
response.get("pagination.total_pages")

// Processing and on-hold orders from this year, 20 per page
val filtered = client.account().getOrders(mapOf(
    "status" to listOf("processing", "on-hold"),
    "after" to "2026-01-01T00:00:00",
    "per_page" to 20
))
```

| Parameter | Description |
|---|---|
| `page`, `per_page` | Page number (default 1) and results per page (default 10) |
| `order` | `"DESC"` (default) or `"ASC"` |
| `status` | One status, or a list (sent comma-joined) |
| `after`, `before` | Only orders placed after or before this date (ISO 8601; without an offset it's UTC) |
| `subscription_order_type` | `parent`, `renewal`, `switch` or `resubscribe` |
| `formatted` | Amounts formatted for display |
| `meta_keys` | Only these keys in each `meta` (a list is comma-joined) |

A boolean is sent as `"true"` or `"false"`, and a `null` value is left out.

### Get a Single Order

The whole order: what was bought, the totals as lines to display, the payment, the customer, the delivery (shipping lines and downloads), notes and what can be done next.

```kotlin
val order = client.account().getOrder(id = 123)

order.get("status.code")           // "completed"
order.get("total_lines")           // [{key, label, amount, detail}] in display order
order.get("payment.needs_payment") // true while it can still be paid
order.get("delivery.downloads")

client.account().getOrder(123, mapOf("formatted" to true, "meta_keys" to listOf("gift_message")))
```

Someone else's order, or one that doesn't exist, is `404 cocart_order_not_found`.

### Guest Order Lookup

Look up an order by ID and the billing email — no authentication required. The order comes without its `key`, and lookups are rate limited (`429 cocart_guest_lookup_rate_limited`):

```kotlin
val response = client.account().getGuestOrder(
    id = 123,
    email = "customer@example.com"
)
```

### Cancel an Order

Cancels one of the customer's orders where WooCommerce allows it (for example a pending or failed order), and returns the order with what happened in `result`:

```kotlin
val order = client.account().cancelOrder(id = 123)
order.get("status.code") // "cancelled"
```

An order that can't be cancelled is `409 cocart_order_cannot_be_cancelled`.

## Downloads

### Get All Downloads

Every file the customer can download, across all orders: `{ "downloads": [...] }`.

```kotlin
val response = client.account().getDownloads()

(response.get("downloads") as List<*>).forEach { file ->
    file as Map<*, *>
    file["name"]                 // "my-ebook-v2.pdf"
    file["downloads_remaining"]  // 3, or null when unlimited
    file["href"]                 // the link that downloads the file
}
```

### Get Order Downloads

The files one order can download (`GET my-account/orders/{id}/downloads`), returned as sent, in the same `{ "downloads": [...] }` shape:

```kotlin
val response = client.account().getOrderDownloads(id = 123)

// For a guest order: the billing email must match
val guest = client.account().getGuestOrderDownloads(id = 123, email = "customer@example.com")

response.get("downloads")
```

## Reviews

Returns product reviews written by the authenticated customer (`GET products/reviews/mine`, the same as `products().myReviews()`):

```kotlin
val response = client.account().getReviews()
```

## Email Verification

```kotlin
// Confirm a pending email change, with the key and user ID from the emailed link.
client.account().verifyEmail(key, userId).get("email") // the address the account now has

// Prove ownership of the account email so WooCommerce links past guest orders to it (WooCommerce 11+).
client.account().sendOrderEmailVerification().get("retry_after") // seconds before another can be asked for
client.account().verifyOrderEmail(key, userId)                    // returns the customer
```

An expired or wrong key is `400 cocart_invalid_or_expired_key`; an email already verified is `409 cocart_email_already_verified`.

## Subscriptions

Needs WooCommerce Subscriptions.

```kotlin
val list = client.account().getSubscriptions(mapOf("status" to "active", "per_page" to 10))
val one = client.account().getSubscription(512)
val formatted = client.account().getSubscription(512, params = mapOf("formatted" to true))

client.account().suspendSubscription(512)
client.account().reactivateSubscription(512)
client.account().cancelSubscription(512)
client.account().setSubscriptionAutoRenew(512, enabled = false)

// Resubscribe puts the ended subscription's items in the cart; check out as usual, or undo it.
client.account().resubscribe(512)
client.account().cancelResubscribe(512)
```

`getSubscriptions()` takes `page`, `per_page`, `order`, `status`, `formatted` and `meta_keys`, and returns `subscriptions` with `pagination`. Each change returns the subscription with what happened in `result`. A change the subscription's state refuses is a `409` (e.g. `cocart_subscription_cannot_suspend`).

## Changing a Subscription's Payment Method

> Requires WooCommerce Subscriptions. The customer must be logged in.

A subscription can switch to a payment method the customer has saved, or to a new card. Use a gateway that can charge renewals: `supportsSubscriptions` on a saved method from `checkout().getSavedPaymentMethods()` (the gateway's `supports.subscriptions`).

`updateSubscriptionPaymentMethod()` can take payment like checkout, so it returns the same [`CheckoutResult`](checkout.md#reading-the-result), read from the subscription the response sends (`objectType` is `subscription`). A new card may need 3D Secure.

**With a saved payment method:**

```kotlin
val saved = client.checkout().getSavedPaymentMethods().filter { it.supportsSubscriptions }

val result = client.account().updateSubscriptionPaymentMethod(512, mapOf(
    "payment_method" to saved.first().gatewayId,
    "token_id" to saved.first().id,
))
```

**With a new card**, create a payment method as in the [Quick Start](checkout.md#quick-start):

```kotlin
val result = client.account().updateSubscriptionPaymentMethod(512, mapOf(
    "payment_method" to "stripe",
    "payment_data" to mapOf("wc-stripe-payment-method" to paymentMethod.id),
))
```

The new card is saved to the customer's account, since the renewals charge it.

**Reading the result:**

```kotlin
when {
    result.isComplete -> {
        // code "payment_method_changed"; message says which method it now renews with.
        result.subscriptionId                  // 512
        result.response.get("payment.title")   // "Visa ending in 4242"
    }
    result.needsConfirmation -> confirmCard(result) // then call updateSubscriptionPaymentMethod() again with the same input
    result.needsRedirect -> openOffSite(result.redirectUrl!!) // then confirmReturn(), which gives the subscription
    result.isPending -> { /* Not confirmed yet: getSubscription(512) shows the new method once it is. */ }
    else -> showError(result.message)
}
```

Confirm as in [3D Secure in the App](checkout.md#3d-secure-in-the-app); a new card usually gives a SetupIntent (`result.intentType` is `"setup_intent"`).

| Code (thrown) | HTTP | Meaning |
|---|---|---|
| `cocart_subscription_not_found` | 404 | No such subscription for this customer |
| `cocart_subscription_payment_method_change_unsupported` | 400 | The subscription's payment method can't be changed |
| `cocart_subscription_cannot_change_payment_method` | 409 | The subscription's state doesn't allow it now |
| `cocart_subscription_payment_token_required` | 400 | Pass a saved method's `token_id`, or a new card in `payment_data` |
| `cocart_invalid_payment_method` | 400 | The payment method isn't usable for this |
| `cocart_subscription_payment_token_needs_confirmation` | 400 | The saved method must be confirmed before it can pay renewals |
| `cocart_payment_in_progress` | 409 | Another change is still taking payment for this subscription: check it before trying again |

A declined card isn't an error: it comes back as a failed result, as at checkout.

## Order Withdrawal

Submits a right-of-withdrawal request (WooCommerce's order withdrawal feature, WooCommerce 11.1+). No login needed; the response is the same whether or not the order matched, with `withdrawal_requested` in `result` and the email the acknowledgement went to:

```kotlin
client.account().requestOrderWithdrawal(mapOf(
    "first_name" to "Jane",
    "last_name" to "Doe",
    "email" to "jane@example.com",
    "email_confirmation" to "jane@example.com",
    "order_number" to "1042",
    "withdrawal_type" to "full_order" // or "specific_items_only" with additional_details
))
```

## Error Handling

If the CoCart plugin is not installed or the account endpoint is unreachable, the SDK catches `rest_no_route` errors and rethrows them with a clearer code:

```kotlin
import com.cocart.sdk.errors.CoCartException

try {
    client.account().getProfile()
} catch (e: CoCartException) {
    if (e.errorCode() == "cocart_plugin_required") {
        // CoCart plugin is not active on the store
    }
}
```
