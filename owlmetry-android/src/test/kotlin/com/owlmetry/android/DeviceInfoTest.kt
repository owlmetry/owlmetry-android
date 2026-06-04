package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [DeviceInfo.collect] populates the device/app snapshot under
 * Robolectric. Mirrors the Swift `DeviceInfo.collect()` contract: `environment`
 * is the platform discriminator, app/build versions come from the host
 * PackageInfo, locale + preferred-language are populated, and
 * `supportedLanguages` is non-null.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun environmentIsAndroid() {
        val info = DeviceInfo.collect(context)
        assertEquals(OwlPlatform.ANDROID, info.platform)
        // The wire value stamped into the event's `environment` field.
        assertEquals("android", info.platform.wire)
    }

    @Test
    fun osVersionIsNonEmpty() {
        val info = DeviceInfo.collect(context)
        assertNotNull(info.osVersion)
        assertTrue("osVersion should be non-empty", info.osVersion.isNotEmpty())
    }

    @Test
    fun deviceModelIsNonEmpty() {
        val info = DeviceInfo.collect(context)
        assertNotNull(info.deviceModel)
        assertTrue("deviceModel should be non-empty", info.deviceModel.isNotEmpty())
    }

    @Test
    fun appVersionMirrorsPackageInfoVersionName() {
        val info = DeviceInfo.collect(context)
        // appVersion is a faithful pass-through of pkg.versionName (the analog of
        // CFBundleShortVersionString), which is *nullable* — Robolectric's
        // synthesized PackageInfo leaves versionName null. The contract under
        // test is the pass-through, not non-nullness: collect must surface
        // exactly what PackageInfo reports.
        val expected = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
        assertEquals(expected, info.appVersion)
    }

    @Test
    fun buildNumberResolvesFromVersionCode() {
        val info = DeviceInfo.collect(context)
        // buildNumber is derived from longVersionCode/versionCode (analog of
        // CFBundleVersion) and is always a non-null string. Robolectric's default
        // versionCode is 0.
        assertNotNull("buildNumber should resolve from PackageInfo", info.buildNumber)
        assertTrue("buildNumber should be all digits", info.buildNumber!!.all { it.isDigit() })
        assertEquals("0", info.buildNumber)
    }

    @Test
    fun localeIsPresent() {
        val info = DeviceInfo.collect(context)
        assertNotNull(info.locale)
        assertTrue("locale should be non-empty", info.locale.isNotEmpty())
    }

    @Test
    fun preferredLanguageIsPresent() {
        val info = DeviceInfo.collect(context)
        // Android analog of Locale.preferredLanguages.first — a BCP-47 tag.
        assertNotNull(info.preferredLanguage)
        assertTrue(
            "preferredLanguage should be non-empty",
            info.preferredLanguage!!.isNotEmpty(),
        )
    }

    @Test
    fun supportedLanguagesIsNonNull() {
        val info = DeviceInfo.collect(context)
        // The field itself is non-null (analog of Bundle.main.localizations);
        // under the default Robolectric locale it carries at least the device tag.
        assertNotNull(info.supportedLanguages)
        assertFalse(
            "supportedLanguages should not contain the Base pseudo-localization",
            info.supportedLanguages.contains("Base"),
        )
    }

    @Test
    fun collectIsStableAcrossCalls() {
        // No randomness in DeviceInfo (unlike EventBuilder's UUID/timestamp): two
        // collections in the same process produce equal snapshots.
        assertEquals(DeviceInfo.collect(context), DeviceInfo.collect(context))
    }
}
