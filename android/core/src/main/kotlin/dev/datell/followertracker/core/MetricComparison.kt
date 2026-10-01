package dev.datell.followertracker.core

data class MetricComparison(val previousAt: Long, val currentAt: Long, val change: Long) {
    companion object {
        fun between(previous: MetricSnapshot?, current: MetricSnapshot?): MetricComparison? {
            if (previous == null || current == null || previous.accountKey != current.accountKey ||
                previous.precision != Precision.EXACT || current.precision != Precision.EXACT ||
                previous.observedAt >= current.observedAt) return null
            return MetricComparison(previous.observedAt, current.observedAt, current.followers - previous.followers)
        }
    }
}
