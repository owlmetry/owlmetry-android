package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [IdentityStore] under Robolectric (real [android.content.SharedPreferences]).
 * Mirrors the Swift `IdentityManager` contract: the anonymous id is generated
 * once, persisted, read-through stable, `owl_anon_`-prefixed, and resettable;
 * the real user id is saved/cleared independently of the anonymous id.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Fresh store over a uniquely-named prefs file so cases don't bleed. */
    private fun freshStore(): IdentityStore {
        val prefs = context.getSharedPreferences(
            "owlmetry-identity-test-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()
        return IdentityStore(prefs)
    }

    @Test
    fun anonymousIdIsGeneratedWithExpectedPrefix() {
        val id = freshStore().anonymousId()
        assertTrue(
            "anonymous id must carry the owl_anon_ prefix, got: $id",
            id.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX),
        )
        assertTrue(id.length > IdentityStore.ANONYMOUS_ID_PREFIX.length)
    }

    @Test
    fun anonymousIdIsStableAcrossReads() {
        val store = freshStore()
        val first = store.anonymousId()
        val second = store.anonymousId()
        assertEquals("anonymous id must be read-through stable", first, second)
    }

    @Test
    fun anonymousIdPersistsAcrossStoreInstances() {
        // Same backing prefs file → a brand-new IdentityStore must read the
        // already-persisted id rather than mint a new one (launch persistence).
        val prefs = context.getSharedPreferences(
            "owlmetry-identity-persist-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()

        val first = IdentityStore(prefs).anonymousId()
        val second = IdentityStore(prefs).anonymousId()
        assertEquals(first, second)
    }

    @Test
    fun resetAnonymousIdMintsAFreshDistinctId() {
        val store = freshStore()
        val original = store.anonymousId()
        val reset = store.resetAnonymousId()
        assertNotEquals(original, reset)
        assertTrue(reset.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
        // The reset id is now the persisted one.
        assertEquals(reset, store.anonymousId())
    }

    @Test
    fun savedUserIdIsNullUntilSet() {
        assertNull(freshStore().savedUserId())
    }

    @Test
    fun saveUserIdPersistsAndReadsBack() {
        val store = freshStore()
        store.saveUserId("user-123")
        assertEquals("user-123", store.savedUserId())
    }

    @Test
    fun clearUserIdRemovesItButLeavesAnonymousIdIntact() {
        val store = freshStore()
        val anon = store.anonymousId()
        store.saveUserId("user-123")
        store.clearUserId()
        assertNull(store.savedUserId())
        // Clearing the user id must not disturb the anonymous id (Swift split).
        assertEquals(anon, store.anonymousId())
    }

    @Test
    fun resetAnonymousIdLeavesSavedUserIdIntact() {
        val store = freshStore()
        store.saveUserId("user-123")
        store.resetAnonymousId()
        assertEquals("user-123", store.savedUserId())
    }
}
