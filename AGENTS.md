# CoCart Kotlin Android SDK

Official Android SDK for the CoCart REST API.

- **Package:** `com.cocart:sdk`
- **Version:** 1.0.0
- **Distribution:** Maven Central, GitHub Packages
- **License:** MIT
- **Min SDK:** API 26 (Android 8.0)

---

## Commands

```bash
./gradlew :cocart:test          # run all unit tests
./gradlew :cocart:test --info   # run tests with verbose output
./gradlew :cocart:lint          # run Android lint checks
./gradlew :cocart:build         # full build (compile + test + lint)
./gradlew :cocart:publishToMavenLocal  # publish to ~/.m2 for local testing
```

Run a single test class:
```bash
./gradlew :cocart:test --tests "com.cocart.sdk.auth.AuthManagerTest"
```

Run tests matching a name pattern:
```bash
./gradlew :cocart:test --tests "*CartResource*"
```

---

## Tech Stack

| | |
|---|---|
| Language | Kotlin 2.0+ — strict, coroutines throughout |
| Min Android SDK | API 26 (Android 8.0) |
| Compile SDK | API 36 (Android 16) |
| HTTP | OkHttp 5.x |
| JSON | `org.json` (Android built-in) + `kotlinx.serialization` |
| Async | kotlinx.coroutines — all network calls are `suspend` functions |
| Storage | `EncryptedSharedPreferences` (default) / `MemoryStorage` (no-context fallback) |
| Tests | JUnit 4, MockK, kotlinx-coroutines-test |
| Build | Gradle 8.x with Kotlin DSL (`build.gradle.kts`) |

---

## Project Structure

```
cocart/src/main/kotlin/com/cocart/sdk/
  CoCart.kt                    # main client class and entry point
  CoCartOptions.kt             # configuration data class + MainPlugin enum
  auth/AuthManager.kt          # auth mode priority, header building, cart key capture
  http/HttpClient.kt           # OkHttp wrapper — retries, ETag, events, error parsing
  http/CoCartResponse.kt       # response wrapper with dot-notation .get()
  resources/
    CartResource.kt            # client.cart() — all cart operations
    ProductsResource.kt        # client.products()
    SessionsResource.kt        # client.sessions() — admin, consumer keys
    JwtResource.kt             # client.jwt() — login, refresh, 2FA, auto-refresh
    AccountResource.kt         # client.account() — profile, orders, downloads
  storage/
    CoCartStorage.kt           # interface
    EncryptedStorage.kt        # EncryptedSharedPreferences implementation
    MemoryStorage.kt           # in-memory (tests / no-context fallback)
  validation/Validators.kt     # validateProductId, validateQuantity, validateEmail
  utilities/CurrencyFormatter.kt
  errors/                      # CoCartException hierarchy

cocart/src/test/kotlin/com/cocart/sdk/
  auth/                        # AuthManagerTest, GuestSessionTest
  resources/                   # CartResourceTest, ProductsResourceTest
  http/                        # CoCartResponseTest
  validation/                  # ValidatorsTest
  mocks/                       # MockHttpClient helpers
```

---

## Code Style

- **File names:** `PascalCase.kt` (matching the class name)
- **Classes / Interfaces / Enums:** `PascalCase`
- **Functions, methods, variables:** `camelCase`
- **Constants:** `UPPER_SNAKE_CASE`
- All public API lives under `com.cocart.sdk`
- All network methods are `suspend` functions — no callbacks, no `LiveData`, no `Flow` (callers manage their own coroutine scope)
- No Android UI dependencies — pure library, no Views or Compose

---

## Auth Priority

JWT > Basic Auth > Consumer Keys > Guest. Implemented in `AuthManager`. Never change this order without updating `docs/authentication.md`.

---

## Key Conventions

- `CoCart(context, siteURL)` — `context` may be `null`; use `MemoryStorage` as fallback
- `captureCartKey()` is `suspend` — called directly inside `withContext(Dispatchers.IO)` in `HttpClient`
- Account resource routes use raw path `cocart/v2/my-account/...` — they bypass the namespace builder intentionally
- Legacy mode (`MainPlugin.LEGACY`) switches field filter param from `_fields` to `fields`; Basic-only methods throw `VersionException` immediately
- ETag cache is per-`HttpClient` instance, keyed by full URL string

---

## Git

- **Commit style:** Imperative, capital first letter — `Added X`, `Fix X`, `Bumped version`
- **Version bumps:** prefix with `Bumped version`
- **Co-author footer:** `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

---

## Testing

| | |
|---|---|
| Framework | JUnit 4 |
| Location | `cocart/src/test/` |
| File pattern | `*Test.kt` |
| Mocking | MockK (`mockk<T>()`, `coEvery { }`) |
| Coroutines | `runTest { }` from `kotlinx-coroutines-test` |
| Coverage | not configured |

Tests run on the JVM — no emulator or device needed. `MemoryStorage` is used in all tests instead of `EncryptedSharedPreferences` (which requires an Android context).
