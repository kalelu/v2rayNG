package com.v2ray.ang.lite

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteEnergyWindowTest {
    private val hour = 60L * 60L * 1000L

    @Test
    fun `long interval is apportioned to its overlap with the latest 24 hours`() {
        val now = 100L * hour

        val fraction = LiteEnergyWindow.overlapFraction(
            startTimestamp = now - 48L * hour,
            endTimestamp = now,
            now = now,
            windowMillis = 24L * hour,
        )

        assertEquals(0.5, fraction, 0.000_001)
    }

    @Test
    fun `interval before the window contributes nothing`() {
        val now = 100L * hour

        val fraction = LiteEnergyWindow.overlapFraction(
            startTimestamp = now - 48L * hour,
            endTimestamp = now - 25L * hour,
            now = now,
            windowMillis = 24L * hour,
        )

        assertEquals(0.0, fraction, 0.0)
    }
}
