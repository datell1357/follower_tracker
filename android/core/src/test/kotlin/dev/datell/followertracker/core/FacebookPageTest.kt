package dev.datell.followertracker.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FacebookPageTest {
    private val payload = """{"provider":"FACEBOOK","accountType":"PAGE","stableId":"99","sessionOwnerId":"42",
        "username":"fixture.page","displayName":"Fixture Page","profileURL":"https://www.facebook.com/profile.php?id=99",
        "followers":0,"following":800,"precision":"EXACT","source":"facebook-webview-page"}"""
    private fun changed(key: String, value: JsonElement): String = JsonObject(ResponseParser.objectBody(payload).toMutableMap().apply { put(key, value) }).toString()
    private fun rejects(body: String, expected: Account? = null, owner: String? = "42", status: SyncStatus = SyncStatus.CHECK_REQUIRED) {
        try { ResponseParser.facebookPageCapture(body, owner, expected, 10); fail("Invalid Page capture must fail") }
        catch (failure: CollectionFailure) { assertEquals(status, failure.status) }
    }
    @Test fun pageCountAndOwnerAreStoredSeparatelyAndExactZeroIsPreserved() {
        val (page, metric) = ResponseParser.facebookPageCapture(payload, "42", null, 10)
        assertEquals("FACEBOOK:PAGE:99", page.key)
        assertEquals("42", page.sessionOwnerId)
        assertEquals(AccountType.PAGE, page.accountType)
        assertEquals("Facebook 페이지", page.connectionTitle)
        assertEquals("Fixture Page", page.identityLabel)
        assertEquals(0L, metric.followers)
        assertNull(metric.following)
        assertEquals(SyncStatus.FOREGROUND_ONLY, page.status)
        assertEquals(Capability.UNAVAILABLE, page.capabilities.followers)
        assertFalse(RefreshPolicy.canRefresh(page, 10, background = true))
        assertEquals(page, Json.decodeFromString<Account>(Json.encodeToString(page)))
    }
    @Test fun pageUpdatePreservesConnectionAndRejectsDifferentTarget() {
        val page = ResponseParser.facebookPageCapture(payload, "42", null, 10).first
        assertEquals(10L, ResponseParser.facebookPageCapture(payload, "42", page, 20).first.connectedAt)
        rejects(changed("stableId", JsonPrimitive("88")), page)
        rejects(payload, page.copy(accountType = AccountType.PROFILE))
    }
    @Test fun onlyExplicitFacebookPagePayloadWithTheCurrentSessionIsAccepted() {
        rejects(changed("accountType", JsonPrimitive("PROFILE")))
        rejects(changed("provider", JsonPrimitive("INSTAGRAM")))
        rejects(changed("sessionOwnerId", JsonPrimitive("other")))
        rejects(changed("stableId", JsonPrimitive("42")))
        rejects(changed("stableId", JsonPrimitive("not-an-id")))
        rejects(payload, owner = null, status = SyncStatus.REAUTH_REQUIRED)
        rejects(changed("source", JsonPrimitive("facebook-webview-profile")), status = SyncStatus.FORMAT_CHANGED)
        rejects(changed("precision", JsonPrimitive("ROUNDED")), status = SyncStatus.FORMAT_CHANGED)
    }
    @Test fun pageUrlMustNameTheCapturedTargetOnTheOfficialHttpsOrigin() {
        for (url in listOf("http://www.facebook.com/profile.php?id=99", "https://facebook.com.example.test/profile.php?id=99",
            "https://user@www.facebook.com/profile.php?id=99", "https://www.facebook.com:8443/profile.php?id=99",
            "https://www.facebook.com/profile.php?id=88", "https://www.facebook.com/profile.php?id=99&id=88", "https://www.facebook.com/fixture.page"))
            rejects(changed("profileURL", JsonPrimitive(url)))
    }
    @Test fun invalidCountsCannotReplaceAPageMetric() {
        for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1.2K"), JsonPrimitive(9_007_199_254_740_992L)))
            rejects(changed("followers", value), status = SyncStatus.FORMAT_CHANGED)
    }
    @Test fun legacyAccountsKeepTheirKeysAndOnlyPersonalProfilesConflict() {
        val legacy = Json.decodeFromString<Account>("""{"provider":"FACEBOOK","stableId":"42","username":"fixture.owner",
            "profileUrl":"https://www.facebook.com/profile.php?id=42","connectedAt":1}""")
        assertEquals(AccountType.PROFILE, legacy.accountType)
        assertEquals("FACEBOOK:42", legacy.key)
        assertNull(legacy.sessionOwnerId)
        val page = ResponseParser.facebookPageCapture(payload, "42", null, 10).first
        assertFalse(legacy.conflictsWith(page)); assertFalse(page.conflictsWith(legacy))
        assertFalse(page.conflictsWith(page.copy(stableId = "88")))
        assertTrue(legacy.conflictsWith(legacy.copy(stableId = "77")))
        assertFalse(legacy.conflictsWith(legacy))
    }
}
