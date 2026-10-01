package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import org.junit.Assert.*
import org.junit.Test

class LoginNavigationPolicyTest {
    @Test fun officialLoginAndChallengePagesRemainAvailable() {
        Provider.entries.forEach { assertEquals(LoginNavigation.ALLOW, loginNavigation(it, it.loginUrl)) }
        assertEquals(LoginNavigation.ALLOW, loginNavigation(Provider.INSTAGRAM, "https://www.instagram.com/challenge/"))
    }
    @Test fun instagramCanUseFacebookWithoutExpandingCollectionCookieHosts() {
        val url = "https://m.facebook.com/login.php"
        assertEquals(LoginNavigation.ALLOW, loginNavigation(Provider.INSTAGRAM, url))
        assertFalse(Provider.INSTAGRAM.allows(url))
        assertEquals(LoginNavigation.BLOCK, loginNavigation(Provider.REDDIT, url))
    }
    @Test fun xCanFollowItsLegacyLoginRedirect() {
        val url = "https://twitter.com/i/flow/login"
        assertEquals(LoginNavigation.ALLOW, loginNavigation(Provider.X, url))
        assertFalse(Provider.X.allows(url))
    }
    @Test fun unsupportedFederatedLoginHasAnExplicitResult() {
        assertEquals(LoginNavigation.EXTERNAL_SIGN_IN, loginNavigation(Provider.REDDIT, "https://accounts.google.com/o/oauth2/auth"))
        assertEquals(LoginNavigation.EXTERNAL_SIGN_IN, loginNavigation(Provider.TIKTOK, "https://appleid.apple.com/auth/authorize"))
    }
    @Test fun lookalikesInsecureUrlsAndUnrelatedDomainsCannotReceiveLoginNavigation() {
        listOf("https://facebook.com.example.test/login", "https://accounts.google.com.example.test/", "http://m.facebook.com/login",
            "https://user@m.facebook.com/login", "https://m.facebook.com:8443/login", "intent://login", "file:///login", "https://example.test/")
            .forEach { assertEquals(it, LoginNavigation.BLOCK, loginNavigation(Provider.INSTAGRAM, it)) }
    }
}
