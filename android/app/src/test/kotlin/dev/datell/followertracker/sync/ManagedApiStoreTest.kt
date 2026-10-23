package dev.datell.followertracker.sync

import dev.datell.followertracker.core.*
import org.junit.Assert.*
import org.junit.Test

class ManagedApiStoreTest {
    private fun page(id: String) = Account(Provider.FACEBOOK, id, id, "Fixture", "https://www.facebook.com/$id",
        connectedAt = 1, accountType = AccountType.PAGE, countTransport = CountTransport.OFFICIAL_API)
    @Test fun encryptedInstallationPendingFlowAndBindingsSurviveRecreation() {
        var persisted: String? = null
        fun create() = ManagedApiStore({ persisted }, { persisted = it }, { it.reversedArray() }, { it.reversedArray() })
        val store = create(); val token = "t".repeat(43)
        store.installation("https://service.example", token); store.bind(page("99"))
        store.pending(PendingFacebookLogin("f".repeat(43), page("99").key))
        assertFalse(persisted!!.contains(token))
        val reopened = create(); assertTrue(reopened.owns(page("99")))
        assertEquals(token, reopened.state().installationToken)
        assertEquals(page("99").key, reopened.pending()?.expectedKey)
    }
    @Test fun removingOnePagePreservesOtherPagesAndInvalidatesExistingRequests() {
        var persisted: String? = null
        val store = ManagedApiStore({ persisted }, { persisted = it }, { it }, { it })
        store.installation("https://service.example", "t".repeat(43))
        store.bind(page("99")); store.bind(page("100"))
        val guard = store.guard(); store.remove(page("99"))
        assertFalse(guard()); assertFalse(store.owns(page("99"))); assertTrue(store.owns(page("100")))
        assertEquals(setOf("99"), store.state().revocations)
    }
    @Test fun legacyLogoutBeforeServiceSetupCannotCancelTheFirstServiceLogin() {
        var persisted: String? = null
        val store = ManagedApiStore({ persisted }, { persisted = it }, { it }, { it })
        store.clear(Provider.FACEBOOK)
        assertTrue(store.state().revocations.isEmpty())
        store.installation("https://service.example", "t".repeat(43))
        store.pending(PendingFacebookLogin("f".repeat(43)))
        assertTrue(store.state().revocations.isEmpty())
        assertNotNull(store.pending())
    }
    @Test fun loginProgressDoesNotInvalidateUnrelatedBackgroundObservations() {
        var persisted: String? = null
        val store = ManagedApiStore({ persisted }, { persisted = it }, { it }, { it })
        store.bind(page("99")); val guard = store.guard()
        store.pending(PendingFacebookLogin("f".repeat(43))); store.cancelLogin()
        assertTrue(guard()); assertNull(store.pending()); assertEquals(1, store.state().cancelledFlows.size)
    }
}
