# Owlmetry Android SDK

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Platforms](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-brightgreen)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-blue)](#requirements)

Kotlin SDK for Android — event logging, structured metrics, funnels, identity,
screen tracking, a drop-in user feedback view, and in-app questionnaires. Mirrors
the [Owlmetry Swift SDK](https://github.com/owlmetry/owlmetry-swift). The core
module has a single runtime dependency (`kotlinx-coroutines`, the analog of Swift
Concurrency); the optional Jetpack Compose UI lives in a separate artifact so
non-Compose apps stay lean.

Part of the [Owlmetry](https://owlmetry.com) self-hosted metrics platform.

**Full setup guide & API reference: [owlmetry.com/docs/sdks/android](https://owlmetry.com/docs/sdks/android)**

> **Status: early scaffold (work in progress).** Public API and docs are being
> built out phase by phase.

## Modules

The SDK ships as two artifacts. Add the core module always; add the Compose
module only if you want the drop-in UI.

| Artifact | Purpose |
|---|---|
| `com.owlmetry:owlmetry-android` | Core SDK — analytics, metrics, funnels, identity, feedback/questionnaire APIs. Framework-only + coroutines. |
| `com.owlmetry:owlmetry-android-compose` | Optional Jetpack Compose UI — `OwlFeedbackView`, `OwlQuestionnaireGate` / `OwlQuestionnaireView`, and the `Modifier.owlScreen` screen-tracking modifier. |

## Install

```kotlin
dependencies {
    implementation("com.owlmetry:owlmetry-android:0.1.0")
    // optional drop-in Compose UI:
    implementation("com.owlmetry:owlmetry-android-compose:0.1.0")
}
```

> See [releases](https://github.com/owlmetry/owlmetry-android/releases/latest)
> for the latest version.

## Quickstart

Configure the SDK once, as early as possible — `Application.onCreate()` is the
right place.

```kotlin
import android.app.Application
import com.owlmetry.android.Owl

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Owl.configure(
            context = this,
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_...",
        )
        Owl.info("app_launched")
    }
}
```

Register the `Application` subclass in your manifest:

```xml
<application android:name=".MyApp" ... >
```

`configure` validates its input and throws `OwlConfigurationError` on an invalid
endpoint, API key, or missing package name.

## Examples

### Logging

```kotlin
Owl.info("feed_loaded", screenName = "Feed")
Owl.warn("cache_miss", attributes = mapOf("key" to "user_profile"))
Owl.error("upload_failed", attributes = mapOf("reason" to "timeout"))

// attributes values may be null — null entries are dropped before the event
// ships, so optional strings flow through without unwrapping at the call site.
val draftId: String? = session.draftId
Owl.info("draft_created", attributes = mapOf("context" to "createDraft", "contractId" to draftId))

// Report a Throwable — the runtime type, stack trace, and cause chain are
// extracted into `_error_*` attributes the server uses to cluster issues.
try {
    upload()
} catch (e: Exception) {
    Owl.error(e, message = "while uploading photos")
}
```

### Identify a user

The SDK stamps events with an anonymous device id until you identify the user.
Call `setUser` after login; previously-sent anonymous events are retroactively
claimed to the real user.

```kotlin
Owl.setUser("user_12345")
Owl.setUserProperties(mapOf("plan" to "premium"))

// On logout — reverts to the anonymous id for future events.
Owl.clearUser()
// On a shared device, mint a fresh anonymous id:
Owl.clearUser(newAnonymousId = true)
```

### Measure an operation

```kotlin
val op = Owl.startOperation("photo-upload", attributes = mapOf("format" to "heic"))
// … do work …
op.complete(attributes = mapOf("size_kb" to "512"))
// or op.fail(error = "network")
// or op.cancel()

// Single-shot metric (no lifecycle):
Owl.recordMetric("checkout")
```

### Record a funnel step

```kotlin
Owl.step("welcome-screen")
Owl.step("create-account")
Owl.step("first-post")
```

### Track screens (Compose)

```kotlin
import com.owlmetry.android.compose.owlScreen

@Composable
fun HomeScreen() {
    Column(modifier = Modifier.owlScreen("Home")) { ... }
}
```

`owlScreen` emits a screen-appeared event on enter and a screen-disappeared event
with the visible duration on exit.

### Collect user feedback (Compose)

Drop `OwlFeedbackView` into a `ModalBottomSheet`, a full screen, or an embedded
section — the host owns presentation and dismissal:

```kotlin
import androidx.compose.material3.ModalBottomSheet
import com.owlmetry.android.compose.OwlFeedbackView

if (showFeedback) {
    ModalBottomSheet(onDismissRequest = { showFeedback = false }) {
        OwlFeedbackView(
            onSubmitted = { showFeedback = false },
            onCancel = { showFeedback = false },
        )
    }
}
```

For programmatic submission (e.g. forwarding feedback from your own form):

```kotlin
val receipt = Owl.sendFeedback(
    message = "Love the new update!",
    email = "me@example.com",
)
```

Every label, placeholder, and error message is overridable via `OwlFeedbackStrings`.

### In-app questionnaires (Compose)

Wrap a screen in `OwlQuestionnaireGate` to auto-present a questionnaire when its
trigger fires and the server reports the user eligible. The SDK saves partial
drafts as the user advances and resumes a half-finished questionnaire mid-flow:

```kotlin
import com.owlmetry.android.compose.OwlQuestionnaireGate
import com.owlmetry.android.OwlQuestionnaireTrigger

OwlQuestionnaireGate(
    slug = "post-onboarding-nps",
    trigger = OwlQuestionnaireTrigger.afterLaunch,
) {
    HomeScreen()
}
```

For full control, fetch and submit yourself:

```kotlin
val result = Owl.fetchQuestionnaire(slug = "post-onboarding-nps")
result.questionnaire?.let { /* render OwlQuestionnaireView, or your own UI */ }

val receipt = Owl.saveQuestionnaireResponse(slug = "post-onboarding-nps", answers = answers, isComplete = true)
if (receipt.wasSubmitted) { /* show a thank-you state */ }
```

## Privacy

The SDK collects **analytics events, diagnostics/crash data, and product
interaction**, plus — only when you opt in — a **user id** (`Owl.setUser`) and
**feedback name/email** (the `OwlFeedbackView` contact fields). It is **not** used
for tracking or advertising (no Advertising ID, no `AD_ID` permission), is **not
shared** with third parties (events go only to your own ingest endpoint), and is
**encrypted in transit** over HTTPS.

Before publishing, complete Google Play's **Data safety** form. The SDK ships a
ready-to-use guide that maps each SDK behavior to the exact form entries:

**→ [docs/play-data-safety.md](./docs/play-data-safety.md)**

This is the Android analog of the Swift SDK's `PrivacyInfo.xcprivacy` manifest +
[privacy compliance guide](https://owlmetry.com/docs/sdks/swift/privacy-compliance).

### Anonymous id persistence (optional)

The SDK stores a stable anonymous id (`owl_anon_*`) in a private
`SharedPreferences` file named `com.owlmetry.sdk`. On iOS the equivalent id lives
in the Keychain and survives an app delete + reinstall; Android wipes a package's
`SharedPreferences` on uninstall, and there is **no framework-only way** for the
SDK to replicate Keychain's reinstall persistence. By default a returning user is
therefore minted a fresh anonymous id after a reinstall.

If you want best-effort reinstall persistence, opt that prefs file into Android's
Auto Backup. The SDK ships two ready-made rule files — reference them from your
app's `<application>` tag:

```xml
<application
    android:fullBackupContent="@xml/owlmetry_backup_rules"
    android:dataExtractionRules="@xml/owlmetry_data_extraction_rules"
    ... >
```

- `@xml/owlmetry_backup_rules` — legacy `fullBackupContent` (Android 6–11).
- `@xml/owlmetry_data_extraction_rules` — Android 12+ cloud-backup +
  device-transfer.

Both include only the `com.owlmetry.sdk` prefs file, so the anonymous id can roam
to a reinstall via the user's own Google backup. This is **best-effort** (Auto
Backup is opportunistic and user-disablable) and **opt-in** — the SDK cannot force
the host app's backup config. If your app already defines its own backup rules,
merge the SDK's `<include domain="sharedpref" path="com.owlmetry.sdk.xml" />`
lines into them rather than overwriting.

## Requirements

- minSdk 24 (Android 7.0)
- Kotlin 2.0+, AGP 8.7+, JDK 17+
- The core module merges two install-time, no-prompt permissions into your app:
  `INTERNET` and `ACCESS_NETWORK_STATE`.

## Build & test

```bash
./gradlew assemble   # build all modules
./gradlew test       # JVM unit tests (Robolectric)
```

## License

MIT. See [LICENSE](./LICENSE).
