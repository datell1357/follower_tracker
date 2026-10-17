package dev.datell.followertracker.sync

internal enum class TrackingMode { FAST, PREMIUM_WIDGET_TEST }

internal object TrackingSessionPolicy {
    private const val FAST_DURATION = 6 * 60 * 60_000L - 30_000

    fun mode(debugBuild: Boolean, premiumTestEnabled: Boolean, sdk: Int): TrackingMode =
        if (debugBuild && premiumTestEnabled && sdk >= 34) TrackingMode.PREMIUM_WIDGET_TEST else TrackingMode.FAST

    fun remainingMillis(mode: TrackingMode, elapsed: Long): Long? = when (mode) {
        TrackingMode.FAST -> (FAST_DURATION - elapsed.coerceAtLeast(0)).coerceAtLeast(0)
        TrackingMode.PREMIUM_WIDGET_TEST -> null
    }

    fun canContinue(mode: TrackingMode, elapsed: Long): Boolean = remainingMillis(mode, elapsed) != 0L

    fun canStart(mode: TrackingMode, explicitStart: Boolean, requested: Boolean): Boolean =
        explicitStart || mode == TrackingMode.PREMIUM_WIDGET_TEST && requested
}
