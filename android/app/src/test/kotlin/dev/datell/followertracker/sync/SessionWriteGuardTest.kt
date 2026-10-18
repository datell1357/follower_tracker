package dev.datell.followertracker.sync

import org.junit.Assert.*
import org.junit.Test

class SessionWriteGuardTest {
    @Test fun anAbsentSessionCanReportReauthButCannotOverwriteANewLogin() {
        var saved: String? = null
        val pending = sessionWriteGuard(saved) { saved }
        // The collector still rejects missing authentication; its REAUTH status must be writable.
        assertTrue(pending())
        saved = "synthetic-new-session"
        assertFalse(pending())
    }

    @Test fun logoutRejectsBothALateSuccessAndALateErrorRestoration() {
        var saved: String? = "synthetic-original-session"
        val success = sessionWriteGuard(saved) { saved }
        val restoreErrorStatus = sessionWriteGuard(saved) { saved }
        assertTrue(success())
        saved = null
        assertFalse(success()); assertFalse(restoreErrorStatus())
    }

    @Test fun aNewLoginDoesNotRevalidateTheOldCollectorsToken() {
        var saved: String? = "synthetic-original-session"
        val pending = sessionWriteGuard(saved) { saved }
        saved = "synthetic-replaced-session"
        assertFalse(pending())
        assertTrue(sessionWriteGuard(saved) { saved }())
    }

    @Test fun loggingOutOneProviderPreservesTheOtherProvidersInFlightObservation() {
        val sessions = mutableMapOf("instagram" to "synthetic-ig", "x" to "synthetic-x")
        val instagram = sessionWriteGuard(sessions["instagram"]) { sessions["instagram"] }
        val x = sessionWriteGuard(sessions["x"]) { sessions["x"] }
        sessions.remove("instagram")
        assertFalse(instagram()); assertTrue(x())
    }
}
