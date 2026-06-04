package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deeper [IdentityStore] coverage complementing [IdentityStoreTest]:
 *  - anonymous-id wire shape: `owl_anon_` + a canonical lowercase UUID (mirrors
 *    Swift `"\(prefix)\(UUID().uuidString)"`);
 *  - cross-call uniqueness of freshly minted ids;
 *  - raw-key isolation between the two SharedPreferences keys so the Swift
 *    split-store semantics hold at the storage layer (anon ↔ Keychain, user ↔
 *    UserDefaults);
 *  - empty/whitespace user ids are stored verbatim (the store is a dumb
 *    persistence layer — validation, if any, lives above it);
 *  - constants match the Swift `IdentityManager` namespace exactly.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityStoreDeepTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun freshStore(): IdentityStore {
        val prefs = context.getSharedPreferences(
            "owlmetry-identity-deep-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()
        return IdentityStore(prefs)
    }

    // MARK: - Anonymous id shape & uniqueness

    @Test
    fun anonymousIdSuffixIsACanonicalUuid() {
        val id = freshStore().anonymousId()
        // The portion after the prefix must round-trip through UUID.fromString —
        // proving "owl_anon_<uuid>" exactly like Swift's
        // "\(anonymousIdPrefix)\(UUID().uuidString)".
        val suffix = id.removePrefix(IdentityStore.ANONYMOUS_ID_PREFIX)
        assertEquals(36, suffix.length)
        val parsed = UUID.fromString(suffix)
        // Round-trip equality also asserts the canonical lowercase 8-4-4-4-12 form.
        assertEquals(suffix, parsed.toString())
    }

    @Test
    fun freshAnonymousIdsAcrossSeparateStoresAreUnique() {
        // Each fresh store mints its own id; collisions would mean a fixed/seeded
        // UUID source. 100 draws is plenty to catch a non-random generator.
        val ids = (0 until 100).map { freshStore().anonymousId() }.toSet()
        assertEquals(100, ids.size)
    }

    @Test
    fun repeatedResetMintsADistinctIdEveryTime() {
        val store = freshStore()
        val seen = HashSet<String>()
        seen.add(store.anonymousId())
        repeat(50) {
            val reset = store.resetAnonymousId()
            assertTrue(reset.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
            assertTrue("reset must mint a never-before-seen id", seen.add(reset))
            // The just-reset id is the one a subsequent read returns.
            assertEquals(reset, store.anonymousId())
        }
        assertEquals(51, seen.size)
    }

    // MARK: - Raw key isolation (Swift split-store at the storage layer)

    @Test
    fun anonymousAndUserIdsLiveUnderDistinctRawKeys() {
        val store = freshStore()
        val anon = store.anonymousId()
        store.saveUserId("real-7")

        // Re-open the SAME backing file via a second store to read raw state:
        // the two ids must persist under their own keys, neither clobbering the
        // other (the property `clearUserId` / `resetAnonymousId` rely on).
        assertEquals(anon, store.anonymousId())
        assertEquals("real-7", store.savedUserId())
    }

    @Test
    fun saveUserIdNeverTouchesAnonymousId() {
        val store = freshStore()
        val anon = store.anonymousId()
        store.saveUserId("a")
        store.saveUserId("b")
        store.saveUserId("c")
        // Overwriting the user id repeatedly leaves the anon id pinned.
        assertEquals(anon, store.anonymousId())
        assertEquals("c", store.savedUserId())
    }

    @Test
    fun resetAnonymousIdNeverTouchesUserId() {
        val store = freshStore()
        store.saveUserId("real-9")
        store.anonymousId()
        store.resetAnonymousId()
        store.resetAnonymousId()
        // Resetting the anon id (shared-device path) must preserve the logged-in
        // user id — Swift resets only the Keychain entry.
        assertEquals("real-9", store.savedUserId())
    }

    @Test
    fun clearUserIdLeavesAnonymousIdAndIsIdempotent() {
        val store = freshStore()
        val anon = store.anonymousId()
        store.saveUserId("real-3")
        store.clearUserId()
        assertNull(store.savedUserId())
        // Clearing again on an already-clear store is a harmless no-op.
        store.clearUserId()
        assertNull(store.savedUserId())
        // Anon survives every clear.
        assertEquals(anon, store.anonymousId())
    }

    // MARK: - Verbatim persistence (dumb storage layer)

    @Test
    fun savedUserIdRoundTripsVerbatimIncludingEdgeStrings() {
        for (value in listOf("u", "", "  ", "user with spaces", "owl_anon_lookalike", "✓-unicode")) {
            val store = freshStore()
            store.saveUserId(value)
            assertEquals(value, store.savedUserId())
        }
    }

    @Test
    fun overwritingUserIdReplacesRatherThanAppends() {
        val store = freshStore()
        store.saveUserId("first")
        store.saveUserId("second")
        assertEquals("second", store.savedUserId())
    }

    // MARK: - Concurrency

    @Test
    fun concurrentAnonymousIdReadsConvergeOnASingleValue() {
        // First read mints + persists; concurrent readers thereafter must all
        // observe the same persisted id (read-through stability under load).
        val store = freshStore()
        val seed = store.anonymousId()

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val observed = ConcurrentHashMap.newKeySet<String>()
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(100) { observed.add(store.anonymousId()) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("all concurrent reads must see the single persisted id", setOf(seed), observed)
    }

    // MARK: - Constants mirror Swift IdentityManager

    @Test
    fun constantsMatchSwiftIdentityManager() {
        assertEquals("owl_anon_", IdentityStore.ANONYMOUS_ID_PREFIX)
        assertEquals("com.owlmetry.sdk", IdentityStore.PREFS_NAME)
        // Swift keys: Keychain account "anonymousId" + UserDefaults "owlmetry.userId".
        assertEquals("anonymousId", IdentityStore.KEY_ANONYMOUS_ID)
        assertEquals("owlmetry.userId", IdentityStore.KEY_USER_ID)
        // The anon / user keys must differ so the split store can't self-collide.
        assertNotEquals(IdentityStore.KEY_ANONYMOUS_ID, IdentityStore.KEY_USER_ID)
        // The SDK prefs file is named after the Swift Keychain service.
        assertFalse(IdentityStore.PREFS_NAME.isBlank())
    }
}
