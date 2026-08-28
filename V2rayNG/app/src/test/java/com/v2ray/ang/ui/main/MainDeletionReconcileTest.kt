package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MainDeletionReconcileTest {

    @Test
    fun `deletion still restarts when selection returns to the previous guid`() {
        assertEquals(AppConfig.MSG_STATE_RESTART, serviceMessageAfterDeletion("replacement-guid"))
    }

    @Test
    fun `deletion stops a running daemon when no profile remains selected`() {
        assertEquals(AppConfig.MSG_STATE_STOP, serviceMessageAfterDeletion(null))
    }
}
