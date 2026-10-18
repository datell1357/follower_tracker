package dev.datell.followertracker.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun oldInstalledWidgetsKeepTheirAccountOrAllAccountSelection() {
        assertTrue(isOverviewWidget(false, null, null))
        assertFalse(isOverviewWidget(false, "instagram:owner", null))
        assertTrue(isOverviewWidget(true, null, WidgetKind.OVERVIEW.name))
        assertTrue(isOverviewWidget(true, "stale-legacy-key", null))
    }

    @Test fun anEmptyNewSingleWidgetCannotFallBackToAnotherConnectedAccount() {
        assertFalse(isOverviewWidget(false, null, WidgetKind.ACCOUNT.name))
        assertFalse(isOverviewWidget(false, "removed-owner", WidgetKind.ACCOUNT.name))
    }

    @Test fun smallCountsRemainExactIncludingARealZero() {
        listOf(0L, 1L, 999L, 9_999L).forEach { assertEquals(it.toString(), compactWidgetCount(it)) }
    }

    @Test fun abbreviationsUseKoreanUnitsWithoutRoundingUpAnObservation() {
        assertEquals("1만", compactWidgetCount(10_000))
        assertEquals("1.23만", compactWidgetCount(12_399))
        assertEquals("9999.99만", compactWidgetCount(99_999_999))
        assertEquals("1억", compactWidgetCount(100_000_000))
        assertEquals("12.34억", compactWidgetCount(1_234_999_999))
    }

    @Test fun veryLargeCountsDoNotOverflowOrTurnIntoScientificNotation() {
        assertEquals("1조", compactWidgetCount(1_000_000_000_000))
        assertEquals("1경", compactWidgetCount(10_000_000_000_000_000))
        assertEquals("922.33경", compactWidgetCount(Long.MAX_VALUE))
    }

    @Test fun stableCollectionIdsKeepAccountsSeparateAcrossUpdatesAndJavaHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(stableAccountId("Aa"), stableAccountId("BB"))
        val keys = listOf("instagram:owner", "tiktok:owner", "facebook:owner", "facebook:page:one", "facebook:page:two")
        assertEquals(keys.size, keys.map(::stableAccountId).toSet().size)
        keys.forEach { assertEquals(stableAccountId(it), stableAccountId(it)); assertTrue(stableAccountId(it) >= 0) }
    }
}
