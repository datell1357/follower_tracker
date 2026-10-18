package dev.datell.followertracker.widget

import java.math.BigDecimal
import java.math.RoundingMode

internal enum class WidgetKind { ACCOUNT, OVERVIEW }

internal fun isOverviewWidget(overviewProvider: Boolean, accountKey: String?, storedKind: String?): Boolean =
    overviewProvider || (accountKey == null && storedKind == null)

/** Stable 64-bit IDs preserve collection scroll position when account data changes. */
internal fun stableAccountId(key: String): Long = key.toByteArray(Charsets.UTF_8).fold(-3750763034362895579L) { hash, byte ->
    (hash xor (byte.toLong() and 255)) * 1099511628211L
}.and(Long.MAX_VALUE)

/** Display-only abbreviation. The saved observation and accessibility label stay exact. */
internal fun compactWidgetCount(count: Long): String {
    val units = listOf(10_000_000_000_000_000L to "경", 1_000_000_000_000L to "조",
        100_000_000L to "억", 10_000L to "만")
    val unit = units.firstOrNull { count >= it.first } ?: return count.toString()
    return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(unit.first), 2, RoundingMode.DOWN)
        .stripTrailingZeros().toPlainString() + unit.second
}
