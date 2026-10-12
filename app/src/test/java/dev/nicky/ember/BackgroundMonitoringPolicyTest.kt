package dev.nicky.ember

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.ble.MugSnapshot
import dev.nicky.ember.data.EmberAppState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMonitoringPolicyTest {
    @Test
    fun `fresh connected empty mug pauses monitoring`() {
        val state = monitoringState(liquidState = LiquidState.EMPTY, connected = true, fresh = true)

        assertTrue(BackgroundMonitoringPolicy.isKnownEmpty(state))
        assertTrue(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `stale empty state is not a live pause signal but keeps the service off`() {
        val state = monitoringState(liquidState = LiquidState.EMPTY, connected = false, fresh = false)

        assertFalse(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `non-empty mug keeps monitoring service active`() {
        val state = monitoringState(liquidState = LiquidState.HEATING, connected = true, fresh = true)

        assertFalse(BackgroundMonitoringPolicy.isLiveEmpty(state))
        assertTrue(BackgroundMonitoringPolicy.shouldRunService(state))
    }

    @Test
    fun `service needs the feature, permissions and a chosen mug`() {
        val base = monitoringState(liquidState = LiquidState.HEATING, connected = true, fresh = true)

        assertFalse(BackgroundMonitoringPolicy.shouldRunService(base.copy(backgroundMonitoring = false)))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(base.copy(hasPermissions = false)))
        assertFalse(BackgroundMonitoringPolicy.shouldRunService(base.copy(selectedAddress = null)))
    }

    @Test
    fun `unknown liquid state does not block monitoring`() {
        assertTrue(BackgroundMonitoringPolicy.shouldRunService(monitoringState(liquidState = null, connected = false, fresh = false)))
    }

    private fun monitoringState(liquidState: Int?, connected: Boolean, fresh: Boolean) = EmberAppState(
        connectionState = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
        hasPermissions = true,
        selectedAddress = "AA:BB:CC:DD:EE:FF",
        snapshot = MugSnapshot(liquidState = liquidState),
        snapshotFresh = fresh,
        backgroundMonitoring = true,
    )
}
