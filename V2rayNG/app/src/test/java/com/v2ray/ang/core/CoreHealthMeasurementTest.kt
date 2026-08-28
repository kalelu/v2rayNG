package com.v2ray.ang.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreHealthMeasurementTest {

    private val measurement = CoreHealthMeasurement(
        measuredGuid = "measured",
        delayMillis = 42L,
    )

    @Test
    fun `measurement applies only while active and selected GUIDs still match`() {
        assertTrue(
            measurement.isStillCurrent(
                activeGuid = "measured",
                selectedGuid = "measured",
            )
        )
    }

    @Test
    fun `measurement is discarded after active core changes`() {
        assertFalse(
            measurement.isStillCurrent(
                activeGuid = "replacement",
                selectedGuid = "replacement",
            )
        )
    }

    @Test
    fun `measurement is discarded while persisted selection is changing`() {
        assertFalse(
            measurement.isStillCurrent(
                activeGuid = "measured",
                selectedGuid = "replacement",
            )
        )
    }
}
