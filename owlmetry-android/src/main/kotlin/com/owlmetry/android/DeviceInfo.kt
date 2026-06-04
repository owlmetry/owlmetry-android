package com.owlmetry.android

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Snapshot of device + app metadata stamped onto every event. Field names and
 * semantics mirror the Swift `DeviceInfo`; the per-platform derivations differ
 * where Android genuinely diverges from iOS (documented inline + in PR notes).
 */
public data class DeviceInfo(
    /** Always [OwlPlatform.ANDROID]; serializes as `environment` = "android". */
    public val platform: OwlPlatform,
    public val osVersion: String,
    public val appVersion: String?,
    public val buildNumber: String?,
    public val deviceModel: String,
    /**
     * The *shown* locale (`Locale.getDefault()` → e.g. `en_US`). The Android
     * analog of Swift's `Locale.current.identifier` — the user's resolved
     * locale, app-constrained.
     */
    public val locale: String,
    /**
     * The user's top *wanted* language as a BCP-47 tag (e.g. `en-US`), the
     * localization-demand signal. Android analog of Swift's
     * `Locale.preferredLanguages.first`.
     */
    public val preferredLanguage: String?,
    /**
     * Every language this app binary ships. See [collect] for the Android
     * derivation + TODO.
     */
    public val supportedLanguages: List<String>,
) {
    public companion object {
        /**
         * Collect device + app info from [context]. Uses only the Android
         * framework (no reflection on BuildConfig of the host app).
         *
         * Android-vs-iOS choices (recorded for wire parity review):
         *  - `osVersion`: `Build.VERSION.RELEASE` (e.g. "14"). Swift emits
         *    three components ("17.4.1"); Android's release string is the
         *    closest public analog and is often a single component — we pass it
         *    through verbatim rather than padding fake `.0.0` minors.
         *  - `deviceModel`: `"<MANUFACTURER> <MODEL>"` (e.g. "Google Pixel 8").
         *    Swift sends the raw `hw.machine` identifier ("iPhone16,2"); Android
         *    has no single equivalent, and `Build.MODEL` alone ("Pixel 8") drops
         *    the maker, so we join manufacturer + model for a human-meaningful
         *    string.
         *  - `appVersion` / `buildNumber`: from the host's `PackageInfo`
         *    (`versionName` / `longVersionCode`), the analogs of
         *    `CFBundleShortVersionString` / `CFBundleVersion`.
         *  - `supportedLanguages`: the Android analog of Swift's
         *    `Bundle.main.localizations` — the locales the app ships *resources*
         *    for, via `AssetManager.getLocales()`. Falls back to an empty list
         *    (→ omitted from the wire) rather than the device language, so we
         *    never falsely report a language the app does not ship and suppress
         *    the server's wanted-vs-shipped locale-demand gap.
         */
        public fun collect(context: Context): DeviceInfo {
            val pkg = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()

            val appVersion = pkg?.versionName
            val buildNumber = pkg?.let { resolveBuildNumber(it) }

            return DeviceInfo(
                platform = OwlPlatform.ANDROID,
                osVersion = formatOsVersion(),
                appVersion = appVersion,
                buildNumber = buildNumber,
                deviceModel = deviceModel(),
                locale = Locale.getDefault().toString(),
                preferredLanguage = preferredLanguage(),
                supportedLanguages = supportedLanguages(context),
            )
        }

        private fun formatOsVersion(): String {
            // Build.VERSION.RELEASE is e.g. "14" or "13"; can be null on weird
            // images. Fall back to the API level so the field is never empty.
            val release = Build.VERSION.RELEASE
            return if (release.isNullOrEmpty()) Build.VERSION.SDK_INT.toString() else release
        }

        @Suppress("DEPRECATION")
        private fun resolveBuildNumber(pkg: android.content.pm.PackageInfo): String {
            // longVersionCode added in API 28; below that, versionCode is the
            // analog of CFBundleVersion. Both rendered as a plain string.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode.toString()
            } else {
                pkg.versionCode.toString()
            }
        }

        private fun deviceModel(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            return when {
                manufacturer.isEmpty() && model.isEmpty() -> "Unknown"
                manufacturer.isEmpty() -> model
                model.isEmpty() -> manufacturer
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }
        }

        private fun preferredLanguage(): String {
            // Locale.getDefault() is the device's primary locale. toLanguageTag()
            // yields a BCP-47 tag ("en-US"), matching Swift's
            // Locale.preferredLanguages.first shape.
            return Locale.getDefault().toLanguageTag()
        }

        private fun supportedLanguages(context: Context): List<String> {
            // Android analog of Swift's `Bundle.main.localizations`: the locales
            // the app ships resources for, reported by AssetManager.getLocales().
            // The empty-string entry is the default/unqualified resource bucket —
            // drop it (Android has no "Base" pseudo-locale to strip). Returns an
            // empty list when nothing is determinable, in which case the field is
            // omitted from the wire — strictly better than reporting the device
            // language, which would falsely mark a language as "shipped" and
            // suppress the server's wanted-vs-shipped locale-demand gap.
            return runCatching {
                context.assets.locales
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "und" }
                    .distinct()
                    .toList()
            }.getOrDefault(emptyList())
        }
    }
}
