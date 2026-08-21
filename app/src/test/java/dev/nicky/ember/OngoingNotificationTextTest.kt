package dev.nicky.ember

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.MugSnapshot
import dev.nicky.ember.data.EmberAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingNotificationTextTest {
    @Test
    fun `ready summary replaces temperature with battery`() {
        val summary = OngoingNotificationText.summary(state(liquidState = 6))

        assertEquals("Ready · Battery 64%", summary)
        assertFalse(summary.contains("55"))
    }

    @Test
    fun `heating summary shows progress state and battery`() {
        val summary = OngoingNotificationText.summary(state(liquidState = 5))

        assertEquals("54.0 → 55.0 °C · Heating · Battery 64%", summary)
    }

    @Test
    fun `charging state is included with battery`() {
        val summary = OngoingNotificationText.summary(state(liquidState = 6, charging = true))

        assertEquals("Ready · Battery 64% · charging", summary)
    }

    @Test
    fun `disconnected summary retains last battery`() {
        val connectedState = state(liquidState = 5)
        val summary = OngoingNotificationText.summary(
            connectedState.copy(
                connectionState = ConnectionState.DISCONNECTED,
                connectionDetail = "Reconnecting…",
            ),
        )

        assertTrue(summary.contains("Reconnecting…"))
        assertTrue(summary.contains("Battery 64%"))
    }

    private fun state(liquidState: Int, charging: Boolean = false): EmberAppState {
        return EmberAppState(
            connectionState = ConnectionState.CONNECTED,
            snapshotFresh = true,
            snapshot = MugSnapshot(
                currentTemperatureC = 54f,
                targetTemperatureC = 55f,
                batteryPercent = 64,
                charging = charging,
                liquidState = liquidState,
            ),
        )
    }
}
