package dev.nicky.ember

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.MugSnapshot
import dev.nicky.ember.data.EmberAppState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMonitoringPolicyTest {
    @Test
    fun `fresh connected empty mug pauses monitoring`() {
        val state = monitoringState(liquidState = 1, connected = true, fresh = true)

        assertTrue(BackgroundMonitoringPolicy.isKnownEmpty(state))
        assertTrue(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `stale empty state is not treated as a live auto-pause signal`() {
        val state = monitoringState(liquidState = 1, connected = false, fresh = false)

        assertFalse(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `non-empty mug keeps monitoring service active`() {
        val state = monitoringState(liquidState = 5, connected = true, fresh = true)

        assertFalse(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertTrue(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `only a known empty to non-empty transition counts as refill`() {
        assertTrue(BackgroundMonitoringPolicy.isRefill(1, 2))
        assertTrue(BackgroundMonitoringPolicy.isRefill(1, 5))
        assertFalse(BackgroundMonitoringPolicy.isRefill(1, null))
        assertFalse(BackgroundMonitoringPolicy.isRefill(null, 2))
        assertFalse(BackgroundMonitoringPolicy.isRefill(5, 6))
    }

    private fun monitoringState(liquidState: Int, connected: Boolean, fresh: Boolean): EmberAppState {
        return EmberAppState(
            connectionState = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
            snapshot = MugSnapshot(liquidState = liquidState),
            snapshotFresh = fresh,
            backgroundMonitoring = true,
        )
    }
}
