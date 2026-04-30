<!-- CoCart SDK Support Policy Template v1 -->

# Support & Versioning Policy

> **Note:** This SDK is currently in development. The full support lifecycle (maintenance phase for previous major versions, EOL grace periods) takes effect once the SDK is declared stable and production-ready.

## Versioning

This SDK follows [Semantic Versioning](https://semver.org/) (SemVer):

- **Major** (X.0.0) — Breaking changes to the public API
- **Minor** (x.Y.0) — New features that are backward-compatible
- **Patch** (x.y.Z) — Bug fixes and security patches

Only the **latest major version** receives active development. Older major versions remain available but receive no updates. Migration guides are provided in the `docs/` folder for major version upgrades.

### What constitutes a breaking change

- Removing or renaming a public class, function, or interface
- Changing required parameters of a public method
- Changing return types in a way that breaks compatibility
- Dropping an Android API level from the supported matrix
- Changing the Maven group ID, artifact ID, or package name

### What is NOT a breaking change

- Adding new optional parameters to existing methods
- Adding new public classes, functions, or response fields
- Internal refactors that do not affect the public API
- Adding a new Android API level to the supported matrix
- Bug fixes that correct behavior to match documentation

## SDK Lifecycle

| Phase | Description | Duration |
|---|---|---|
| **Active** | New features, bug fixes, security patches | Current major version |
| **Maintenance** | Security patches and critical bug fixes only | Previous major version, 12 months |
| **Deprecated** | No updates; remains installable | After maintenance ends |

## Supported Android Versions

| Android | API Level | Status | SDK Support | Notes |
|---|---|---|---|---|
| Android 16 | 36 | Current | Supported | Tested in CI |
| Android 15 | 35 | Active | Supported | Tested in CI |
| Android 14 | 34 | Active LTS | Supported | Tested in CI |
| Android 11 | 30 | Maintenance | Supported | Tested in CI |
| Android 8.0 | 26 | Maintenance | Minimum version | `minSdk` floor |
| Android 7.x and below | < 26 | EOL | Not supported | |

### Kotlin compatibility

| Kotlin | Support |
|---|---|
| 2.0+ | Recommended; full coroutine and serialization support |
| 1.9.x | May work but is not tested or guaranteed |

### Version support policy

We support Android API levels that are in active use according to the [Android distribution dashboard](https://developer.android.com/about/dashboards). The SDK `minSdk` floor is API 26 (Android 8.0).

- **Adding new versions:** When a new Android API level is released to stable, we add CI testing and official support.
- **Dropping old versions:** When an Android API level drops below 1% of active devices on the distribution dashboard, we raise `minSdk` in the next major SDK release.

## Deprecation Notices

We communicate deprecations through:

1. **`@Deprecated` annotations** — render as strikethrough in Android Studio and IntelliJ IDEA
2. **Changelog entry** — every deprecation is noted in release notes
3. **Minimum one minor release** — a deprecation notice ships at least one minor version before the deprecated feature is removed
4. **Migration guide** — major version upgrades include a migration guide in the `docs/` folder

## Getting Help

- **Documentation:** https://cocartapi.com/docs
- **Community:** https://cocartapi.com/community
- **Issues:** https://github.com/cocart-headless/cocart-kotlin/issues
