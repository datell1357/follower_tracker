package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import org.junit.Assert.*
import org.junit.Test

class LoginNavigationPolicyTest {
    @Test fun optionalTikTokAppLinksStayOutsideTheWebAndCollectionBoundaries() {
        for (scheme in listOf("snssdk1340", "snssdk1233", "snssdk1180")) {
            for (host in listOf("aweme", "user")) {
                val target = "$scheme://$host/profile/42"
                assertTrue(isOptionalTikTokAppLink(Provider.TIKTOK, "https://www.tiktok.com/", target))
                assertEquals(LoginNavigation.BLOCK, loginNavigation(Provider.TIKTOK, target))
                assertFalse(Provider.TIKTOK.allows(target))
                assertFalse(canAutoConnect(Provider.TIKTOK, target, true, false, false))
            }
        }
    }
    @Test fun optionalAppLinksAreConsumedOnlyFromTheOfficialTikTokWebsite() {
        val target = "snssdk1340://user/profile/42"
        Provider.entries.filter { it != Provider.TIKTOK }.forEach {
            assertFalse(isOptionalTikTokAppLink(it, it.loginUrl, target))
        }
        listOf("https://accounts.google.com/", "https://tiktok.com.example.test/", "http://www.tiktok.com/",
            "https://user@www.tiktok.com/", "https://www.tiktok.com:8443/", "about:blank")
            .forEach { assertFalse(isOptionalTikTokAppLink(Provider.TIKTOK, it, target)) }
    }
    @Test fun unknownSchemesAndSpoofedAppLinksRemainBlocked() {
        listOf("snssdk1340://user.example.test/profile/42", "snssdk1340://private@user/profile/42",
            "snssdk1340://user:443/profile/42", "snssdk1340://unknown/profile/42", "unknown://user/profile/42",
            "intent://www.tiktok.com/", "javascript:alert(1)", "invalid uri")
            .forEach { assertFalse(isOptionalTikTokAppLink(Provider.TIKTOK, Provider.TIKTOK.loginUrl, it)) }
    }
    @Test fun blockedDestinationRetainsOnlySchemeAndHost() {
        assertEquals(LoginDestination("https", "example.test"),
            loginDestination("https://private@example.test/private-path?code=private&state=private#private"))
        assertEquals(LoginDestination("intent", "www.tiktok.com"),
            loginDestination("intent://www.tiktok.com/private#Intent;S.browser_fallback_url=private;end"))
        assertEquals(LoginDestination(null, null), loginDestination("invalid uri"))
    }
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
    @Test fun federatedLoginIsAllowedForAllProvidersWithoutExpandingCollectionHosts() {
        Provider.entries.forEach { provider ->
            listOf("https://accounts.google.com/o/oauth2/auth", "https://appleid.apple.com/auth/authorize").forEach { url ->
                assertEquals(LoginNavigation.AUTHENTICATE, loginNavigation(provider, url))
                assertFalse(provider.allows(url))
                assertFalse(canAutoConnect(provider, url, true, false, false))
            }
        }
    }
    @Test fun lookalikesInsecureUrlsAndUnrelatedDomainsCannotReceiveLoginNavigation() {
        listOf("https://facebook.com.example.test/login", "https://accounts.google.com.example.test/", "http://m.facebook.com/login",
            "https://user@m.facebook.com/login", "https://m.facebook.com:8443/login", "intent://login", "file:///login", "https://example.test/",
            "http://accounts.google.com/", "https://user@accounts.google.com/", "https://accounts.google.com:8443/", "https://appleid.apple.com.example.test/")
            .forEach { assertEquals(it, LoginNavigation.BLOCK, loginNavigation(Provider.INSTAGRAM, it)) }
    }
}
