package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import org.junit.Assert.*
import org.junit.Test

class LoginNavigationPolicyTest {
    @Test fun pageLinksNormalizeHttpToHttpsAndRetainOnlyOfficialFacebookOrigins() {
        assertEquals("https://facebook.com/fixture.page?locale=ko_KR", facebookPageUrl(" http://facebook.com/fixture.page?locale=ko_KR "))
        assertEquals("https://www.facebook.com/fixture.page", facebookPageUrl("www.facebook.com/fixture.page"))
        for (url in listOf("https://facebook.com.example.test/page", "http://private@facebook.com/page", "https://facebook.com:8443/page",
            "javascript:alert(1)", "file:///facebook.com/page", "https://example.test/page", "//facebook.com/page", "invalid uri"))
            assertNull(facebookPageUrl(url))
    }

    private fun facebookIntent(fallback: String = "https://www.facebook.com/profile.php?id=42", fields: String = "scheme=fb;package=com.facebook.katana") =
        "intent://profile/42#Intent;$fields;S.browser_fallback_url=${java.net.URLEncoder.encode(fallback, "UTF-8")};end"

    @Test fun facebookNativeHandoffOpensOnlyItsOfficialHttpsProfileFallback() {
        for (host in listOf("www.facebook.com", "m.facebook.com")) {
            for (pkg in listOf("com.facebook.katana", "com.facebook.lite")) {
                val fallback = "https://$host/profile.php?id=42&ref=fixture+web"
                val target = facebookIntent(fallback, "scheme=fb;package=$pkg")
                assertEquals(fallback, facebookBrowserFallback(Provider.FACEBOOK, "https://www.facebook.com/", target))
                assertEquals(LoginNavigation.BLOCK, loginNavigation(Provider.FACEBOOK, target))
                assertFalse(Provider.FACEBOOK.allows(target))
                assertFalse(canAutoConnect(Provider.FACEBOOK, target, true, false, false))
            }
        }
    }
    @Test fun facebookNamedPageHandoffOpensItsOfficialWebFallback() {
        for (host in listOf("www.facebook.com", "m.facebook.com")) {
            val fallback = "https://$host/fixture.page/?locale=ko_KR&_rdr"
            val target = facebookIntent(fallback)
            assertEquals(fallback, facebookBrowserFallback(Provider.FACEBOOK, "https://www.facebook.com/fixture.page", target))
            assertNull(facebookCollectionFallback(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl, target, "42"))
        }
        for (path in listOf("login", "home.php", "checkpoint", "search", "groups", "fixture.page/posts/42", "fixture.page/photos"))
            assertNull(facebookBrowserFallback(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl,
                facebookIntent("https://www.facebook.com/$path/")))
        assertNull(facebookCollectionFallback(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl,
            facebookIntent("https://www.facebook.com/fixture.page?id=42"), "42"))
    }
    @Test fun desktopPageReadsKeepTheSelectedOfficialTargetAndRejectFeedsAndAmbiguousIDs() {
        val source = "https://m.facebook.com/fixture.page/?locale=ko_KR#top"
        assertEquals("https://www.facebook.com/fixture.page/?locale=ko_KR", facebookPageDesktopUrl(source))
        assertTrue(sameFacebookPageTarget(source, "https://www.facebook.com/fixture.page?locale=en_US"))
        assertTrue(sameFacebookPageTarget("https://m.facebook.com/profile.php?id=42", "https://www.facebook.com/profile.php?id=42&_rdr"))
        assertFalse(sameFacebookPageTarget(source, "https://www.facebook.com/other"))
        assertFalse(sameFacebookPageTarget("https://m.facebook.com/profile.php?id=42", "https://www.facebook.com/profile.php?id=99"))
        for (url in listOf("https://facebook.com.example.test/fixture.page", "http://www.facebook.com/fixture.page", "https://user@www.facebook.com/fixture.page",
            "https://www.facebook.com:8443/fixture.page", "https://www.facebook.com/", "https://www.facebook.com/login/",
            "https://www.facebook.com/fixture.page/posts/42", "https://www.facebook.com/profile.php", "https://www.facebook.com/profile.php?id=42&id=99"))
            assertNull(facebookPageDesktopUrl(url))
    }
    @Test fun facebookFallbackRejectsUntrustedOriginsAndDifferentProviders() {
        Provider.entries.filter { it != Provider.FACEBOOK }.forEach {
            assertNull(facebookBrowserFallback(it, it.loginUrl, facebookIntent()))
        }
        for (source in listOf("about:blank", "https://accounts.google.com/", "http://www.facebook.com/",
            "https://facebook.com.example.test/", "https://user@www.facebook.com/", "https://www.facebook.com:8443/"))
            assertNull(facebookBrowserFallback(Provider.FACEBOOK, source, facebookIntent()))
        for (fallback in listOf("http://www.facebook.com/profile.php?id=42", "https://facebook.com.example.test/profile.php?id=42",
            "https://user@www.facebook.com/profile.php?id=42", "https://www.facebook.com:8443/profile.php?id=42",
            "https://example.test/profile.php?id=42", "https://www.facebook.com/login/", "javascript:alert(1)"))
            assertNull(facebookBrowserFallback(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl, facebookIntent(fallback)))
    }
    @Test fun countCollectionFallbackMustTargetExactlyTheAuthenticatedOwner() {
        val source = Provider.FACEBOOK.loginUrl
        assertEquals("https://www.facebook.com/profile.php?id=42",
            facebookCollectionFallback(Provider.FACEBOOK, source, facebookIntent(), "42"))
        for (fallback in listOf("https://www.facebook.com/profile.php?id=99", "https://www.facebook.com/profile.php",
            "https://www.facebook.com/profile.php?id=42&id=99", "https://www.facebook.com/profile.php?id=42&id=42"))
            assertNull(facebookCollectionFallback(Provider.FACEBOOK, source, facebookIntent(fallback), "42"))
        assertNull(facebookCollectionFallback(Provider.FACEBOOK, source, facebookIntent(), "not-an-id"))
    }
    @Test fun malformedAndAmbiguousFacebookIntentsRemainBlocked() {
        val valid = facebookIntent()
        for (target in listOf(valid.replace("intent:", "fb:"), valid.replace("//profile/", "//profile.example.test/"),
            valid.replace("//profile/", "//user@profile/"), valid.replace("//profile/", "//profile:443/"),
            facebookIntent(fields = "scheme=unknown;package=com.facebook.katana"),
            facebookIntent(fields = "scheme=fb;package=com.other.app"),
            facebookIntent(fields = "scheme=fb;scheme=fb;package=com.facebook.katana"),
            valid.replace(";end", ";S.browser_fallback_url=https%3A%2F%2Fwww.facebook.com%2Fprofile.php;end"),
            valid.replace(";end", ";end;"), "intent://profile/42#Intent;scheme=fb;package=com.facebook.katana;end", "invalid uri"))
            assertNull(facebookBrowserFallback(Provider.FACEBOOK, Provider.FACEBOOK.loginUrl, target))
    }

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
