package dev.datell.followertracker.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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

    @Test fun allStoredPersonalProfilesCanTryTheirOfficialBrowserRouteWithoutClaimingSuccess() {
        for (provider in Provider.entries) {
            val legacy = owner.copy(provider = provider, profileUrl = provider.loginUrl, status = SyncStatus.FOREGROUND_ONLY,
                capabilities = Capabilities(count = Capability.FOREGROUND_ONLY, background = Capability.FOREGROUND_ONLY))
            assertTrue(provider.name, RefreshPolicy.usesProfileBrowser(legacy))
            assertTrue(provider.name, RefreshPolicy.canRefresh(legacy, 1, background = true))
            assertEquals(Capability.FOREGROUND_ONLY, legacy.capabilities.background)
        }
    }

    @Test fun facebookPagesKeepTheirExplicitPageConfirmationFlow() {
        val page = owner.copy(provider = Provider.FACEBOOK, profileUrl = Provider.FACEBOOK.loginUrl,
            accountType = AccountType.PAGE, sessionOwnerId = "99", status = SyncStatus.FOREGROUND_ONLY,
            countTransport = CountTransport.PROFILE_BROWSER)
        assertFalse(RefreshPolicy.usesProfileBrowser(page))
        assertFalse(RefreshPolicy.canRefresh(page, 1, background = true))
    }

    @Test fun olderStoredAccountWithoutTransportStillDecodes() {
        val oldJson = """{"provider":"INSTAGRAM","stableId":"42","username":"fixture_owner","profileUrl":"https://www.instagram.com/fixture_owner/","connectedAt":1}"""
        assertEquals(CountTransport.SESSION_HTTP, Json.decodeFromString<Account>(oldJson).countTransport)
        assertNull(Json.decodeFromString<Account>(oldJson).transientRetry)
    }

    @Test fun transientRetriesGrowFromOneMinuteToFifteenMinutesAndStayBounded() {
        var previous = owner
        for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            val next = RefreshPolicy.nextTransientRetry(previous, 1_000)
            assertEquals(1_000 + delay, next.nextAttemptAt)
            previous = previous.copy(transientRetry = next)
        }
        assertEquals(5, previous.transientRetry?.failureCount)
    }

    @Test fun localBackoffOnlyBlocksAutomaticRequestsButServiceCooldownBlocksBoth() {
        val waiting = owner.copy(status = SyncStatus.OFFLINE, transientRetry = TransientRetryState(1, 60_001))
        assertFalse(RefreshPolicy.canRefresh(waiting, 60_000, background = true))
        assertTrue(RefreshPolicy.canRefresh(waiting, 60_001, background = true))
        assertTrue(RefreshPolicy.canRefresh(waiting, 1, background = false))
        for (background in listOf(false, true))
            assertFalse(RefreshPolicy.canRefresh(waiting.copy(nextAllowedAt = 60_002), 60_001, background))
    }

    @Test fun failureResponseTimeStartsTheNewDelayAndStoredStateRoundTrips() {
        val first = owner.copy(lastAttemptAt = 1_000, transientRetry = TransientRetryState(1, 61_000))
        val second = RefreshPolicy.nextTransientRetry(first, 30_000)
        assertEquals(150_000L, second.nextAttemptAt)
        val saved = first.copy(transientRetry = second)
        assertEquals(second, Json.decodeFromString<Account>(Json.encodeToString(saved)).transientRetry)
    }

    @Test fun serverRetryAfterAlsoAppliesToTemporaryServerFailures() {
        assertEquals(121_000L, RefreshPolicy.serviceRetryAt(CollectionFailure(SyncStatus.OFFLINE, 120), 1_000))
        assertEquals(901_000L, RefreshPolicy.serviceRetryAt(CollectionFailure(SyncStatus.RATE_LIMITED), 1_000))
        assertNull(RefreshPolicy.serviceRetryAt(CollectionFailure(SyncStatus.OFFLINE), 1_000))
        assertNull(RefreshPolicy.serviceRetryAt(CollectionFailure(SyncStatus.CHECK_REQUIRED, 120), 1_000))
    }

    @Test fun extremeRetryValuesCannotOverflowIntoAnAlreadyElapsedDeadline() {
        assertEquals(Long.MAX_VALUE, RefreshPolicy.nextTransientRetry(owner, Long.MAX_VALUE - 1).nextAttemptAt)
        assertEquals(Long.MAX_VALUE, RefreshPolicy.serviceRetryAt(CollectionFailure(SyncStatus.RATE_LIMITED, Long.MAX_VALUE), 1_000))
    }
}
