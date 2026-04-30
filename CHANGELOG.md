# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial implementation of the CoCart Android SDK
- `CoCart` client with fluent configuration API
- Guest session management with `EncryptedSharedPreferences` persistence
- Basic Auth, JWT, and Consumer Keys authentication modes
- JWT auto-refresh with `withAutoRefresh` coroutine helper
- Two-factor authentication support via `TwoFactorAuthRequiredException`
- `CartResource` — full cart API including coupons, shipping, and fees (CoCart Plus)
- `ProductsResource` — products, variations, categories, tags, brands
- `AccountResource` — customer profile, orders, downloads, reviews
- `SessionsResource` — admin session management (consumer keys)
- `JwtResource` — JWT login, refresh, validate, token expiry helpers
- `CoCartResponse` with dot-notation `.get()` and response helper methods
- ETag / `If-None-Match` conditional request support
- Retry with exponential backoff for transient failures
- `CoCartEvent` event system (`REQUEST`, `RESPONSE`, `ERROR`)
- `CurrencyFormatter` with symbol position and separator support
- `MemoryStorage` for tests and no-context environments
- Client-side validation via `validateProductId`, `validateQuantity`, `validateEmail`
- Full error hierarchy: `CoCartException`, `AuthException`, `TwoFactorAuthRequiredException`, `ValidationException`, `VersionException`, `NetworkException`, `RateLimitException`
- ProGuard / R8 consumer rules
- GitHub Actions CI — unit tests across Android API 26 / 30 / 34 + lint

[Unreleased]: https://github.com/cocart-headless/cocart-kotlin/commits/main
