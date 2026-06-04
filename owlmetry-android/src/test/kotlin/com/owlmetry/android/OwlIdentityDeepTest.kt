package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deeper [Owl] identity coverage complementing [OwlIdentityTest]: idempotent
 * setUser, user switching, clearUser before configure, session-id regeneration
 * on reconfigure, anon-id stability across login/logout cycles, and the
 * documented pre-configure persistence contract.
 *
 * Mirrors the Swift `Owl` identity semantics: identity is "saved real user id,
 * otherwise the persistent anonymous id"; [Owl.currentUserId] surfaces whichever
 * is active.
 */
@RunWith(RobolectricTestRunner::class)
class OwlIdentityDeepTest {

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

    // MARK: - setUser semantics

    @Test
    fun setUserIsIdempotentForTheSameIdentifier() {
        configure()
        Owl.setUser("u-1")
        Owl.setUser("u-1")
        assertEquals("u-1", Owl.currentUserId)
        // Persisted exactly once → reconfigure resolves the same id.
        Owl.resetForTesting()
        configure()
        assertEquals("u-1", Owl.currentUserId)
    }

    @Test
    fun setUserSwitchesBetweenIdentifiers() {
        configure()
        Owl.setUser("alice")
        assertEquals("alice", Owl.currentUserId)
        Owl.setUser("bob")
        assertEquals("bob", Owl.currentUserId)
        // The most-recent id is the one persisted.
        Owl.resetForTesting()
        configure()
        assertEquals("bob", Owl.currentUserId)
    }

    @Test
    fun setUserAfterClearUserRelogsIn() {
        configure()
        Owl.setUser("alice")
        Owl.clearUser()
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
        Owl.setUser("alice")
        assertEquals("alice", Owl.currentUserId)
    }

    // MARK: - clearUser semantics

    @Test
    fun clearUserBeforeConfigureIsNoOp() {
        // Mirrors setUserBeforeConfigureIsNoOp in OwlIdentityTest: the surface is
        // gated on configured state, so an unconfigured clearUser can't throw or
        // mutate anything observable.
        Owl.clearUser()
        Owl.clearUser(newAnonymousId = true)
        assertNull(Owl.currentUserId)
        // After configure the anon id resolves normally.
        configure()
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun clearUserIsIdempotent() {
        configure()
        val anon = Owl.currentUserId
        Owl.setUser("alice")
        Owl.clearUser()
        Owl.clearUser()
        assertEquals(anon, Owl.currentUserId)
    }

    @Test
    fun clearUserWithNewAnonymousIdThenPlainClearKeepsTheNewAnon() {
        configure()
        Owl.setUser("alice")
        Owl.clearUser(newAnonymousId = true)
        val freshAnon = Owl.currentUserId
        assertNotNull(freshAnon)
        // A subsequent plain clearUser reverts to the *current* (fresh) anon id,
        // not the original — clearUser() reads s.anonymousId, which was updated.
        Owl.setUser("bob")
        Owl.clearUser()
        assertEquals(freshAnon, Owl.currentUserId)
    }

    @Test
    fun resetAnonymousIdViaClearUserPersistsAcrossReconfigure() {
        configure()
        val original = Owl.currentUserId
        Owl.clearUser(newAnonymousId = true)
        val fresh = Owl.currentUserId
        assertNotEquals(original, fresh)
        // The fresh anon id was written through to storage → reconfigure reads it.
        Owl.resetForTesting()
        configure()
        assertEquals(fresh, Owl.currentUserId)
    }

    // MARK: - Session id

    @Test
    fun sessionIdIsNonNullAndUuidShapedAfterConfigure() {
        configure()
        val session = Owl.sessionId
        assertNotNull(session)
        assertEquals(36, session!!.length)
        // Canonical UUID round-trip.
        assertEquals(session, java.util.UUID.fromString(session).toString())
    }

    @Test
    fun reconfigureMintsAFreshSessionId() {
        configure()
        val first = Owl.sessionId
        Owl.resetForTesting()
        configure()
        val second = Owl.sessionId
        assertNotNull(first)
        assertNotNull(second)
        // Each configure starts a new session (Swift sets s.sessionId = UUID()).
        assertNotEquals(first, second)
    }

    // MARK: - Anonymous id stability

    @Test
    fun anonymousIdSurvivesLoginLogoutCycleWithoutReset() {
        configure()
        val anon = Owl.currentUserId
        Owl.setUser("alice")
        Owl.clearUser() // plain clear → same anon
        Owl.setUser("bob")
        Owl.clearUser() // plain clear → same anon
        assertEquals("plain login/logout cycles never re-mint the anon id", anon, Owl.currentUserId)
    }

    @Test
    fun setUserDoesNotDisturbThePersistedAnonymousId() {
        configure()
        val anon = Owl.currentUserId
        Owl.setUser("alice")
        // Even with a real user active, the underlying anon id is preserved so a
        // later clearUser can revert to it.
        Owl.clearUser()
        assertEquals(anon, Owl.currentUserId)
    }

    // MARK: - Pre-configure persistence (Swift parity)

    @Test
    fun setUserBeforeConfigureResolvesToRealUserAfterConfigure() {
        // Swift's `setUser` persists unconditionally (only the server claim is
        // transport-gated), so a pre-configure setUser must win over the anon id
        // at the next configure. The Android port stashes the id and applies it
        // at configure() (where the persistence Context first exists).
        Owl.setUser("pre-config-user")
        assertNull(Owl.currentUserId) // no resolved state until configure
        configure()
        assertEquals("pre-config-user", Owl.currentUserId)
    }

    @Test
    fun clearBeforeConfigureBeatsEarlierPreConfigureSetUser() {
        // Last write wins, and clear takes precedence: a pre-configure setUser
        // followed by a pre-configure clearUser resolves to the anon id.
        Owl.setUser("pre-config-user")
        Owl.clearUser()
        configure()
        assertTrue(Owl.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }
}
