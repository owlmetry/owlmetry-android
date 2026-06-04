package com.owlmetry.android

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Persistent identity storage — the Android analog of the Swift SDK's
 * `IdentityManager`.
 *
 * Swift splits identity across two stores:
 *  - the **anonymous ID** lives in the Keychain
 *    (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) so it survives app
 *    reinstalls and is device-bound, and
 *  - the **real user ID** lives in `UserDefaults`.
 *
 * Android has no Keychain. The SDK's dependency rule allows only
 * kotlinx-coroutines plus the Android framework, which rules out
 * `EncryptedSharedPreferences` (the `androidx.security` artifact — also
 * deprecated). The closest *framework* analog for both stores is
 * [SharedPreferences]. We keep the two IDs in distinct preference keys to
 * preserve the Swift split semantics (clearing the user ID must not disturb the
 * anonymous ID, and resetting the anonymous ID must not touch the user ID).
 *
 * Divergence from Swift, recorded deliberately:
 *  - **No reinstall survival.** iOS Keychain entries outlive an app delete;
 *    Android wipes an app's `SharedPreferences` on uninstall. There is no
 *    framework-only way to replicate Keychain's reinstall persistence (the
 *    nearest options — Auto Backup or a Backup Agent — are host-app concerns,
 *    not SDK-ownable, and don't write to device-only storage). A fresh
 *    anonymous ID is therefore minted after a reinstall. Functionally identical
 *    within an install's lifetime, which is what every read path here needs.
 *  - **Device-only access** (`...ThisDeviceOnly`) is implicit: app-private
 *    `SharedPreferences` never leave the device or sync across devices.
 *
 * The store is constructed once with the host app's (application) [Context] and
 * resolves its backing [SharedPreferences] eagerly, so every read/write is a
 * fast in-memory/disk hit with no per-call Context plumbing — matching the
 * Swift static-namespace ergonomics while staying Android-idiomatic.
 */
internal class IdentityStore(
    private val prefs: SharedPreferences,
) {
    /** Convenience constructor resolving the SDK's private preference file. */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    // MARK: - Anonymous ID (persistent, survives launches)

    /**
     * Return the persisted anonymous ID, generating and persisting a fresh one
     * on first call. Mirrors Swift `IdentityManager.anonymousId()`:
     * read-through, generate-on-miss, always returns a stable value for the
     * lifetime of the install.
     */
    fun anonymousId(): String {
        readAnonymousId()?.let { return it }
        val newId = generateAnonymousId()
        writeAnonymousId(newId)
        return newId
    }

    /**
     * Discard the current anonymous ID and persist a brand-new one, returning
     * it. Mirrors Swift `IdentityManager.resetAnonymousId()` — used by
     * `clearUser(newAnonymousId: true)` on shared devices.
     */
    fun resetAnonymousId(): String {
        val newId = generateAnonymousId()
        writeAnonymousId(newId)
        return newId
    }

    private fun generateAnonymousId(): String = "$ANONYMOUS_ID_PREFIX${UUID.randomUUID()}"

    private fun readAnonymousId(): String? = prefs.getString(KEY_ANONYMOUS_ID, null)

    private fun writeAnonymousId(value: String) {
        prefs.edit().putString(KEY_ANONYMOUS_ID, value).apply()
    }

    // MARK: - Real User ID

    /**
     * The persisted real user ID, or null if `setUser` has never been called
     * (or the user has logged out). Mirrors Swift `IdentityManager.savedUserId()`.
     */
    fun savedUserId(): String? = prefs.getString(KEY_USER_ID, null)

    /** Persist the real user ID. Mirrors Swift `IdentityManager.saveUserId(_:)`. */
    fun saveUserId(id: String) {
        prefs.edit().putString(KEY_USER_ID, id).apply()
    }

    /**
     * Remove the persisted real user ID (logout). Leaves the anonymous ID
     * untouched. Mirrors Swift `IdentityManager.clearUserId()`.
     */
    fun clearUserId() {
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    internal companion object {
        /** `owl_anon_` — same prefix the server keys anonymous app_users on. */
        const val ANONYMOUS_ID_PREFIX: String = "owl_anon_"

        /**
         * Private preference file for the SDK. Named after the Swift Keychain
         * service identifier (`com.owlmetry.sdk`) so the storage namespace reads
         * consistently across platforms.
         */
        const val PREFS_NAME: String = "com.owlmetry.sdk"

        /** Anonymous-ID key — analog of the Keychain `account` "anonymousId". */
        const val KEY_ANONYMOUS_ID: String = "anonymousId"

        /** Real-user-ID key — analog of the UserDefaults "owlmetry.userId" key. */
        const val KEY_USER_ID: String = "owlmetry.userId"
    }
}
