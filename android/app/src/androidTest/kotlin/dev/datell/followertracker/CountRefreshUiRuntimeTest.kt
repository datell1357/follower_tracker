package dev.datell.followertracker

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.ui.AccountDetail
import dev.datell.followertracker.ui.TrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI only; never starts network requests or writes accounts/sessions/settings. */
@RunWith(AndroidJUnit4::class)
class CountRefreshUiRuntimeTest {
    @get:Rule val rule = createComposeRule()
    private fun row(provider: Provider, page: Boolean = false): AccountOverview {
        val now = System.currentTimeMillis()
        val account = Account(provider, "42", "refresh_fixture", profileUrl = provider.loginUrl,
            status = SyncStatus.FOREGROUND_ONLY, connectedAt = now,
            capabilities = Capabilities(count = Capability.FOREGROUND_ONLY, background = Capability.FOREGROUND_ONLY),
            accountType = if (page) AccountType.PAGE else AccountType.PROFILE,
            sessionOwnerId = if (page) "99" else null)
        return AccountOverview(account, listOf(MetricSnapshot(account.key, now, 100, following = null, source = "synthetic-refresh-fixture")))
    }

    @Test fun legacyPersonalProfilesCanUseTheRefreshButton() {
        val displayed = mutableStateOf(row(Provider.INSTAGRAM))
        var refreshed: Provider? = null
        rule.setContent { TrackerTheme { Surface {
            AccountDetail(displayed.value, false, { refreshed = displayed.value.account.provider }, {})
        } } }
        for (provider in Provider.entries) {
            rule.runOnIdle { displayed.value = row(provider); refreshed = null }
            rule.onNodeWithText("지금 갱신").performScrollTo().assertIsEnabled().performClick()
            rule.runOnIdle { assertEquals(provider, refreshed) }
        }
    }

    @Test fun facebookPageStillRequiresItsExplicitPageFlow() {
        rule.setContent { TrackerTheme { Surface { AccountDetail(row(Provider.FACEBOOK, page = true), false, {}, {}) } } }
        rule.onNodeWithText("지금 갱신").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("페이지 열고 갱신").assertIsEnabled()
    }
}
