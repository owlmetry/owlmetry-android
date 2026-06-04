package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 scaffold smoke test — proves the module compiles, resolves, and runs JVM unit tests. */
class OwlmetryVersionTest {

    @Test
    fun sdkNameIsAndroid() {
        assertEquals("owlmetry-android", OwlmetryVersion.NAME)
    }

    @Test
    fun versionIsSemver() {
        assertTrue(
            "version must be semver X.Y.Z, was ${OwlmetryVersion.CURRENT}",
            Regex("""^\d+\.\d+\.\d+$""").matches(OwlmetryVersion.CURRENT),
        )
    }
}
