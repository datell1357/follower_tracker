package dev.datell.followertracker.core

import org.junit.Assert.*
import org.junit.Test

class MetricComparisonTest {
    private fun metric(at: Long, count: Long = 10, precision: Precision = Precision.EXACT, owner: String = "owner") =
        MetricSnapshot(owner, at, count, null, precision, "synthetic-comparison-fixture")

    @Test fun irregularIntervalsRetainActualObservationTimesAndZeroChanges() {
        val result = MetricComparison.between(metric(100), metric(900_000, 12))!!
        assertEquals(100L, result.previousAt); assertEquals(900_000L, result.currentAt); assertEquals(2L, result.change)
        assertEquals(0L, MetricComparison.between(metric(100), metric(200))?.change)
        assertEquals(-5L, MetricComparison.between(metric(100), metric(200, 5))?.change)
    }
    @Test fun baselineAndImpreciseMetricsHaveNoComparisonTime() {
        assertNull(MetricComparison.between(null, metric(100)))
        assertNull(MetricComparison.between(metric(100), null))
        for (precision in listOf(Precision.ROUNDED, Precision.ESTIMATED)) {
            assertNull(MetricComparison.between(metric(100, precision = precision), metric(200)))
            assertNull(MetricComparison.between(metric(100), metric(200, precision = precision)))
        }
    }
    @Test fun changedOwnerAndNonIncreasingTimesCannotCreateDeltas() {
        assertNull(MetricComparison.between(metric(100), metric(200, owner = "other")))
        assertNull(MetricComparison.between(metric(100), metric(100)))
        assertNull(MetricComparison.between(metric(100), metric(50)))
    }
}
