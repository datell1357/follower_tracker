package dev.datell.followertracker.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RefreshPolicyTest {
    private val owner = Account(Provider.INSTAGRAM, "42", "fixture_owner",
        profileUrl = "https://www.instagram.com/fixture_owner/", connectedAt = 1)

    @Test fun manualAndAutomaticRefreshBothHonorRateLimitCooldown() {
        val limited = owner.copy(status = SyncStatus.RATE_LIMITED, nextAllowedAt = 60_001)
        for (background in listOf(false, true)) {
            assertFalse(RefreshPolicy.canRefresh(limited, 60_000, background))
            assertTrue(RefreshPolicy.canRefresh(limited, 60_001, background))
        }
    }

    @Test fun backgroundRetriesStopForReauthenticationChallengeAndChangedFormats() {
        for (status in listOf(SyncStatus.REAUTH_REQUIRED, SyncStatus.CHECK_REQUIRED, SyncStatus.FORMAT_CHANGED))
            assertFalse(RefreshPolicy.canRefresh(owner.copy(status = status), 1, background = true))
    }

    @Test fun previouslyStoredInstagramForegroundAccountCanTryItsProfileRoute() {
        val legacy = owner.copy(status = SyncStatus.FOREGROUND_ONLY,
            capabilities = Capabilities(count = Capability.FOREGROUND_ONLY, background = Capability.FOREGROUND_ONLY))
        assertTrue(RefreshPolicy.usesProfileBrowser(legacy))
        assertTrue(RefreshPolicy.canRefresh(legacy, 1, background = true))
        val observed = owner.copy(countTransport = CountTransport.PROFILE_BROWSER, capabilities = Capabilities(count = Capability.OBSERVED, background = Capability.OBSERVED))
        assertTrue(RefreshPolicy.usesProfileBrowser(observed))
        assertTrue(RefreshPolicy.canRefresh(observed, 1, background = true))
    }

    @Test fun unsupportedForegroundProvidersAreNotPromotedToBackgroundSupport() {
        for (provider in listOf(Provider.FACEBOOK, Provider.X, Provider.TIKTOK)) {
            val unsupported = owner.copy(provider = provider, profileUrl = provider.loginUrl, status = SyncStatus.FOREGROUND_ONLY)
            assertFalse(RefreshPolicy.usesProfileBrowser(unsupported))
            assertFalse(RefreshPolicy.canRefresh(unsupported, 1, background = true))
        }
    }

    @Test fun olderStoredAccountWithoutTransportStillDecodes() {
        val oldJson = """{"provider":"INSTAGRAM","stableId":"42","username":"fixture_owner","profileUrl":"https://www.instagram.com/fixture_owner/","connectedAt":1}"""
        assertEquals(CountTransport.SESSION_HTTP, Json.decodeFromString<Account>(oldJson).countTransport)
    }
}
