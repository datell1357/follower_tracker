package dev.datell.followertracker.ui

import org.junit.Assert.*
import org.junit.Test

class FacebookPageConnectionRequestTest {
    private val page = "https://www.facebook.com/fixture.page/"
    private fun action(request: FacebookPageConnectionRequest, current: String = page,
        authenticated: Boolean = true, loading: Boolean = false, busy: Boolean = false) =
        request.next(current, authenticated, loading, busy)

    @Test fun aLoginRedirectOverridesStaleCookiePresenceWithoutCreatingAReturnLoop() {
        val request = checkNotNull(FacebookPageConnectionRequest.create(page, authenticated = true))
            .observing("https://www.facebook.com/login/")
        assertEquals(FacebookPageAction.WAIT, action(request, current = "https://www.facebook.com/checkpoint/"))
        assertEquals(FacebookPageAction.OPEN_TARGET, action(request, current = "https://www.facebook.com/"))
        val returned = request.copy(returnedAfterLogin = true).observing("https://www.facebook.com/checkpoint/")
        assertEquals(FacebookPageAction.WAIT, action(returned, current = "https://www.facebook.com/"))
    }

    @Test fun anExplicitLinkWaitsForAuthenticationAndReturnsToItsTargetOnce() {
        val request = checkNotNull(FacebookPageConnectionRequest.create(page, authenticated = false))
        assertEquals(FacebookPageAction.WAIT, action(request, authenticated = false))
        assertEquals(FacebookPageAction.OPEN_TARGET, action(request)) // Refresh a page opened before authentication.
        for (path in listOf("login/", "login.php", "checkpoint/", "two_step_verification/", "auth_platform/"))
            assertEquals(FacebookPageAction.WAIT, action(request, current = "https://www.facebook.com/$path"))
        assertEquals(FacebookPageAction.OPEN_TARGET, action(request, current = "https://www.facebook.com/"))
        val returned = request.copy(returnedAfterLogin = true)
        assertEquals(FacebookPageAction.WAIT, action(returned, current = "https://www.facebook.com/"))
        assertEquals(FacebookPageAction.CAPTURE, action(returned))
    }

    @Test fun matchingTargetCapturesOnceAndNeverCapturesAnotherPageOrIdentity() {
        val request = checkNotNull(FacebookPageConnectionRequest.create(page, authenticated = true))
        assertEquals(FacebookPageAction.CAPTURE, action(request, current = "https://m.facebook.com/fixture.page?locale=ko_KR"))
        assertEquals(FacebookPageAction.WAIT, action(request.copy(captureAttempted = true)))
        assertEquals(FacebookPageAction.WAIT, action(request, loading = true))
        assertEquals(FacebookPageAction.WAIT, action(request, busy = true))
        assertEquals(FacebookPageAction.WAIT, action(request, current = "https://www.facebook.com/other.page"))
        val numeric = checkNotNull(FacebookPageConnectionRequest.create("https://facebook.com/profile.php?id=42", true))
        assertEquals(FacebookPageAction.WAIT, action(numeric, current = "https://facebook.com/profile.php?id=99"))
    }

    @Test fun opaqueShareLinksRequireConfirmationOfTheResolvedDestination() {
        val share = "https://www.facebook.com/share/fixture123/?mibextid=example"
        val request = checkNotNull(FacebookPageConnectionRequest.create(share, true))
        assertTrue(request.requiresConfirmation)
        assertEquals(FacebookPageAction.WAIT, action(request))
        assertEquals(FacebookPageAction.WAIT, action(request, current = "https://www.facebook.com/other.page"))
        assertEquals(FacebookPageAction.WAIT, action(request, current = "https://example.test/fixture.page"))
        assertEquals(FacebookPageAction.WAIT, action(request, current = "https://www.facebook.com/"))
    }

    @Test fun invalidAndNonPageLinksCannotStartTheFlow() {
        assertEquals(page, FacebookPageConnectionRequest.create("www.facebook.com/fixture.page/", false)?.target)
        for (input in listOf("", "https://facebook.com/", "https://facebook.com/login/", "https://facebook.com/checkpoint/",
            "https://facebook.com/share/p/fixture/", "https://facebook.com/groups/123/", "https://facebook.com/fixture.page/posts/123",
            "https://facebook.com/profile.php?id=42&id=99", "https://example.test/fixture.page", "https://user@facebook.com/fixture.page",
            "https://facebook.com.evil.test/fixture.page", "https://facebook.com/" + "a".repeat(17_000)))
            assertNull(input.take(80), FacebookPageConnectionRequest.create(input, true))
    }
}
