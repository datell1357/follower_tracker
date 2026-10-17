package dev.datell.followertracker.sync

import org.junit.Assert.*
import org.junit.Test

class TrackingSessionPolicyTest {
    private val hour = 60 * 60_000L

    @Test fun premiumRequiresAnExplicitDebugEntitlementOnASupportedDevice() {
        assertEquals(TrackingMode.PREMIUM_WIDGET_TEST, TrackingSessionPolicy.mode(true, true, 34))
        assertEquals(TrackingMode.PREMIUM_WIDGET_TEST, TrackingSessionPolicy.mode(true, true, 36))
        assertEquals(TrackingMode.FAST, TrackingSessionPolicy.mode(true, false, 36))
    }

    @Test fun aSavedTestEntitlementCannotEnablePremiumInRelease() {
        for (sdk in listOf(26, 34, 36, 99))
            assertEquals(TrackingMode.FAST, TrackingSessionPolicy.mode(false, true, sdk))
    }

    @Test fun olderDevicesKeepTheOrdinaryServiceType() {
        for (sdk in listOf(26, 29, 33))
            assertEquals(TrackingMode.FAST, TrackingSessionPolicy.mode(true, true, sdk))
    }

    @Test fun freeSessionKeepsItsExistingBoundary() {
        val end = 6 * hour - 30_000
        assertEquals(1L, TrackingSessionPolicy.remainingMillis(TrackingMode.FAST, end - 1))
        assertTrue(TrackingSessionPolicy.canContinue(TrackingMode.FAST, end - 1))
        assertEquals(0L, TrackingSessionPolicy.remainingMillis(TrackingMode.FAST, end))
        assertFalse(TrackingSessionPolicy.canContinue(TrackingMode.FAST, end))
        assertFalse(TrackingSessionPolicy.canContinue(TrackingMode.FAST, 24 * hour))
    }

    @Test fun premiumDoesNotInheritTheSixHourDeadline() {
        for (elapsed in listOf(0L, 6 * hour, 24 * hour, 72 * hour)) {
            assertNull(TrackingSessionPolicy.remainingMillis(TrackingMode.PREMIUM_WIDGET_TEST, elapsed))
            assertTrue(TrackingSessionPolicy.canContinue(TrackingMode.PREMIUM_WIDGET_TEST, elapsed))
        }
    }

    @Test fun stickyRestartRequiresAnOutstandingPremiumRequest() {
        assertTrue(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, false, true))
        assertFalse(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, false, false))
        assertFalse(TrackingSessionPolicy.canStart(TrackingMode.FAST, false, true))
        assertFalse(TrackingSessionPolicy.canStart(TrackingMode.FAST, false, false))
        assertTrue(TrackingSessionPolicy.canStart(TrackingMode.FAST, true, false))
        assertTrue(TrackingSessionPolicy.canStart(TrackingMode.PREMIUM_WIDGET_TEST, true, false))
    }
}
