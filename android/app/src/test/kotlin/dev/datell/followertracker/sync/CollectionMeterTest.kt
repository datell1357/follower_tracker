package dev.datell.followertracker.sync

import org.junit.Assert.*
import org.junit.Test

class CollectionMeterTest {
    @Test fun unsupportedOrResetCountersAreUnknownInsteadOfZero() {
        assertNull(CollectionMeter.byteDelta(-1, -1))
        assertNull(CollectionMeter.byteDelta(-1, 100))
        assertNull(CollectionMeter.byteDelta(100, -1))
        assertNull(CollectionMeter.byteDelta(100, 10))
        assertEquals(0L, CollectionMeter.byteDelta(100, 100))
        assertEquals(60L, CollectionMeter.byteDelta(100, 160))
    }
}
