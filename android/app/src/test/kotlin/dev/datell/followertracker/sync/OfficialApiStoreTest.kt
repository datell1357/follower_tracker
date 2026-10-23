package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import org.junit.Assert.*
import org.junit.Test

class OfficialApiStoreTest {
    private val persisted = mutableMapOf<String, String>()
    // Test-only reversible cipher. Device encryption uses DataCipher/Android Keystore.
    private fun store() = OfficialApiStore({ persisted[it] }, { key, value ->
        if (value == null) persisted.remove(key) else persisted[key] = value
    }, { it.reversedArray() }, { it.reversedArray() })
    private fun page(id: String) = Account(Provider.FACEBOOK, id, id, "Fixture", "https://www.facebook.com/$id",
        connectedAt = 1, accountType = AccountType.PAGE, countTransport = CountTransport.OFFICIAL_API)
    @Test fun persistenceCanBeReadAfterRecreationWithoutStoringPlainCredentials() {
        val store = store(); store.saveYouTubeKey("fixture-key-123"); store.savePageToken("99", "fixture-token-123")
        assertFalse(persisted.values.any { it.contains("fixture-key") || it.contains("fixture-token") })
        val recreated = store()
        assertEquals("fixture-key-123", recreated.youtubeKey()); assertEquals("fixture-token-123", recreated.pageToken("99"))
        assertNull(recreated.pageToken("100"))
    }
    @Test fun replacementLogoutAndDisconnectFenceInFlightResults() {
        val store = store(); val initial = store.guard(Provider.FACEBOOK)
        store.savePageToken("99", "first-fixture-token"); assertFalse(initial())
        val first = store.guard(Provider.FACEBOOK)
        store.savePageToken("99", "next-fixture-token"); assertFalse(first())
        val second = store.guard(Provider.FACEBOOK)
        store.removeAccount(page("99")); assertFalse(second()); assertNull(store.pageToken("99"))
        store.savePageToken("99", "fixture-token-123"); val connected = store.guard(Provider.FACEBOOK)
        store.clear(Provider.FACEBOOK); assertFalse(connected()); assertNull(store.pageToken("99"))
    }
    @Test fun removingOnePageKeepsOtherPagesAndTheOtherProvider() {
        val store = store(); store.savePageToken("99", "first-fixture-token"); store.savePageToken("100", "other-fixture-token")
        store.saveYouTubeKey("fixture-key-123"); val youtube = store.guard(Provider.YOUTUBE)
        store.removeAccount(page("99"))
        assertEquals("other-fixture-token", store.pageToken("100")); assertTrue(youtube())
        store.clear(Provider.FACEBOOK); assertEquals("fixture-key-123", store.youtubeKey())
    }
    @Test fun unreadableCredentialsDoNotSilentlyBecomeAnAnonymousFallback() {
        persisted[Provider.YOUTUBE.name] = "invalid-base64!"
        try { store().youtubeKey(); fail() } catch (e: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, e.status) }
    }
}
