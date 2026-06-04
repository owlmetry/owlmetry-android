# Owlmetry Android SDK

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

Kotlin SDK for Android — event logging, structured metrics, funnels, identity, screen tracking, a
drop-in user feedback view, and in-app questionnaires. Mirrors the [Owlmetry Swift
SDK](https://github.com/owlmetry/owlmetry-swift). The core module has a single runtime dependency
(`kotlinx-coroutines`, the analog of Swift Concurrency); the optional Compose UI lives in a separate
artifact so non-Compose apps stay lean.

Part of the [Owlmetry](https://owlmetry.com) self-hosted metrics platform.

> **Status: early scaffold (work in progress).** Public API and docs are being built out phase by phase.

## Modules

| Artifact | Purpose |
|---|---|
| `com.owlmetry:owlmetry-android` | Core SDK — analytics, metrics, funnels, identity, feedback/questionnaire APIs. Framework-only + coroutines. |
| `com.owlmetry:owlmetry-android-compose` | Optional Jetpack Compose UI — `OwlFeedbackView`, `OwlQuestionnaireView`, `owlScreen` modifier. |

## Install (planned)

```kotlin
dependencies {
    implementation("com.owlmetry:owlmetry-android:0.1.0")
    // optional drop-in Compose UI:
    implementation("com.owlmetry:owlmetry-android-compose:0.1.0")
}
```

## Quickstart (planned)

```kotlin
// Application.onCreate()
Owl.configure(
    context = this,
    endpoint = "https://ingest.owlmetry.com",
    apiKey = "owl_client_...",
)

Owl.info("app_launched")
```

## Requirements

- minSdk 24 (Android 7.0)
- Kotlin 2.0+, AGP 8.7+, JDK 17+

## Build & test

```bash
./gradlew assemble   # build all modules
./gradlew test       # JVM unit tests
```

## License

MIT. See [LICENSE](./LICENSE).
