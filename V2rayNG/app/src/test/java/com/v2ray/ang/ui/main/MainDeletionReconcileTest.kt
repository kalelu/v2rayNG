package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MainDeletionReconcileTest {

    @Test
    fun `deletion still restarts when selection returns to the previous guid`() {
        assertEquals(AppConfig.MSG_STATE_RESTART, serviceMessageAfterDeletion("replacement-guid"))
    }

    @Test
    fun `deletion stops a running daemon when no profile remains selected`() {
        assertEquals(AppConfig.MSG_STATE_STOP, serviceMessageAfterDeletion(null))
    }

    @Test
    fun `delete all nodes uses the global storage operation once and stops the daemon`() {
        val dataSource = mock<MainDataSource>()
        whenever(dataSource.removeAllServer()).thenReturn(4)

        val count = deleteAllNodesAndReconcileService(dataSource)

        assertEquals(4, count)
        verify(dataSource).removeAllServer()
        verify(dataSource, never()).removeServer(any(), any())
        verify(dataSource, never()).getSelectServer()
        verify(dataSource).sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
    }
}
