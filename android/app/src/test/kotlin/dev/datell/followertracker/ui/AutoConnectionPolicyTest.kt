package dev.datell.followertracker.ui

import dev.datell.followertracker.core.*
import org.junit.Assert.*
import org.junit.Test

class AutoConnectionPolicyTest {
    @Test fun sessionAloneDoesNotConnectBeforeLoginOrChallengeCompletes() {
        val provider = Provider.INSTAGRAM
        assertFalse(canAutoConnect(provider, provider.loginUrl, true, false, false))
        assertFalse(canAutoConnect(provider, "https://www.instagram.com/challenge/", true, false, false))
        assertFalse(canAutoConnect(provider, "https://instagram.com.example.test/", true, false, false))
        assertFalse(canAutoConnect(provider, "https://www.instagram.com/", false, false, false))
        assertFalse(canAutoConnect(provider, "https://www.instagram.com/", true, true, false))
        assertFalse(canAutoConnect(provider, "https://www.instagram.com/", true, false, true))
        assertTrue(canAutoConnect(provider, "https://www.instagram.com/", true, false, false))
    }
    @Test fun pollingDoesNotSendDuplicateRequestsForOnePage() {
        val policy = AutoConnectionPolicy()
        assertTrue(policy.begin("self:home", 0))
        assertFalse(policy.begin("self:home", 1_000))
        assertTrue(policy.begin("self:profile", 2_000))
        assertTrue(policy.begin("other:profile", 3_000))
    }
    @Test fun pageChangesAndManualRetryCannotBypassRateLimit() {
        val policy = AutoConnectionPolicy()
        assertTrue(policy.begin("self:home", 0))
        policy.failed(CollectionFailure(SyncStatus.RATE_LIMITED, 120), 0)
        assertFalse(policy.begin("self:profile", 119_999))
        policy.requestRetry()
        assertFalse(policy.begin("self:home", 119_999))
        assertTrue(policy.begin("self:home", 120_000))
    }
    @Test fun defaultRateLimitWaitsFifteenMinutes() {
        val policy = AutoConnectionPolicy()
        policy.begin("self", 0)
        policy.failed(CollectionFailure(SyncStatus.RATE_LIMITED), 10)
        assertEquals(900_010L, policy.nextAllowedAt)
        assertFalse(policy.begin("self", 900_009))
        assertTrue(policy.begin("self", 900_010))
    }
    @Test fun offlineRetriesAreSpacedAndBoundedUntilRequested() {
        val policy = AutoConnectionPolicy()
        for (attempt in 0..2) {
            val time = attempt * 30_000L
            assertTrue(policy.begin("self", time))
            policy.failed(CollectionFailure(SyncStatus.OFFLINE), time)
            assertFalse(policy.begin("self", time + 29_999))
        }
        assertFalse(policy.begin("self", 90_000))
        policy.requestRetry()
        assertTrue(policy.begin("self", 90_000))
    }
    @Test fun requestLimitIsNotReportedAsAuthenticationFailure() {
        val message = connectionFailureMessage(CollectionFailure(SyncStatus.RATE_LIMITED))
        assertTrue(message.contains("요청을 잠시 제한"))
        assertFalse(message.contains("계정을 확인해주세요"))
        assertFalse(message.contains("다시 로그인"))
    }
    @Test fun signedInOwnerContextFailureDoesNotAskForAnotherLogin() {
        val message = webCaptureFailureMessage("owner_context_missing", CollectionFailure(SyncStatus.CHECK_REQUIRED))
        assertTrue(message.contains("로그인은 확인"))
        assertTrue(message.contains("계정 정보를 읽지 못"))
        assertFalse(message.contains("로그인을 완료"))
        assertFalse(message.contains("추가 인증"))
        assertEquals(connectionFailureMessage(CollectionFailure(SyncStatus.REAUTH_REQUIRED)),
            webCaptureFailureMessage("reauth_required", CollectionFailure(SyncStatus.REAUTH_REQUIRED)))
    }
}
