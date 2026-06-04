package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the [Owl] identity surface wired in Phase 3 under Robolectric:
 * configure resolves a persistent anonymous id, [Owl.currentUserId] reflects the
 * resolved id (real user id once set, otherwise anonymous), and
 * [Owl.setUser] / [Owl.clearUser] persist + flip the resolved id. Mirrors the
 * Swift `Owl` identity semantics.
 */
@RunWith(RobolectricTestRunner::class)
class OwlIdentityTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun clearSdkPrefs() {
        context.getSharedPreferences(IdentityStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Before
    fun setUp() {
        Owl.resetForTesting()
        clearSdkPrefs()
    }

    @After
    fun tearDown() {
        Owl.resetForTesting()
        clearSdkPrefs()
    }

    private fun configure() {
        Owl.configure(
            context = context,
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_test",
        )
    }

    @Test
    fun currentUserIdAndSessionIdAreNullBeforeConfigure() {
        assertNull(Owl.currentUserId)
        assertNull(Owl.sessionId)
    }

    @Test
    fun configureResolvesAnonymousIdAsCurrentUser() {
        configure()
        val id = Owl.currentUserId
        assertNotNull(id)
        assertTrue(
            "current user id should be the anonymous id pre-login, got: $id",
            id!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX),
        )
        assertNotNull(Owl.sessionId)
    }

    @Test
    fun anonymousIdIsStableAcrossReconfigure() {
        configure()
        val first = Owl.currentUserId
        Owl.resetForTesting()
        configure()
        val second = Owl.currentUserId
        // The anonymous id is persisted, so a second configure resolves the
        // same id (mirrors Swift reading from the Keychain on each launch).
        assertEquals(first, second)
    }

    @Test
    fun setUserBecomesCurrentUserAndPersists() {
        configure()
        Owl.setUser("real-user-42")
        assertEquals("real-user-42", Owl.currentUserId)

        // Persisted: a reconfigure prefers the saved real user id over anon.
        Owl.resetForTesting()
        configure()
        assertEquals("real-user-42", Owl.currentUserId)
    }

    @Test
    fun clearUserRevertsToAnonymousIdWithoutResettingIt() {
        configure()
        val anonBefore = Owl.currentUserId
        Owl.setUser("real-user-42")
        Owl.clearUser()
        // Reverts to the same anonymous id (not a fresh one).
        assertEquals(anonBefore, Owl.currentUserId)
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun clearUserWithNewAnonymousIdMintsFreshId() {
        configure()
        val anonBefore = Owl.currentUserId
        Owl.setUser("real-user-42")
        Owl.clearUser(newAnonymousId = true)
        val anonAfter = Owl.currentUserId
        assertNotNull(anonAfter)
        assertTrue(anonAfter!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
        assertNotEquals(
            "clearUser(newAnonymousId = true) must mint a distinct anonymous id",
            anonBefore,
            anonAfter,
        )
    }

    @Test
    fun clearUserDoesNotRestoreSavedUserOnReconfigure() {
        configure()
        Owl.setUser("real-user-42")
        Owl.clearUser()
        // The saved user id was cleared from storage, so a reconfigure resolves
        // back to the anonymous id (logout survives relaunch).
        Owl.resetForTesting()
        configure()
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun setUserBeforeConfigurePersistsIntoNextSession() {
        // Mirrors Swift: Owl.setUser persists unconditionally, so a pre-configure
        // setUser must survive into the next configure() and win over the anon id.
        Owl.setUser("real-user-42")
        // currentUserId is still null pre-configure (no resolved state yet).
        assertNull(Owl.currentUserId)
        configure()
        assertEquals("real-user-42", Owl.currentUserId)
    }

    @Test
    fun clearUserBeforeConfigurePersistsLogoutIntoNextSession() {
        // Establish a saved real user id, then log out before a fresh configure.
        configure()
        Owl.setUser("real-user-42")
        Owl.resetForTesting()
        Owl.clearUser()
        configure()
        // The pre-configure logout was applied: resolution falls back to anon.
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }
}
