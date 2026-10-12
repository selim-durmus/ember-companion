package dev.nicky.ember

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.ble.MugSnapshot
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayFormattingTest {
    @Test
    fun `temperatures format per unit`() {
        assertEquals("57.5 °C", TemperatureFormat.format(57.5f, DisplayUnit.CELSIUS))
        assertEquals("136 °F", TemperatureFormat.format(57.5f, DisplayUnit.FAHRENHEIT))
        assertEquals("57.5°", TemperatureFormat.format(57.5f, DisplayUnit.CELSIUS, compact = true))
    }

    @Test
    fun `fahrenheit range stays inside the mug limits`() {
        val range = DisplayUnit.FAHRENHEIT.range
        assertTrue(DisplayUnit.FAHRENHEIT.toCelsius(range.start) >= EmberProtocol.MIN_TARGET_CELSIUS)
        assertTrue(DisplayUnit.FAHRENHEIT.toCelsius(range.endInclusive) <= EmberProtocol.MAX_TARGET_CELSIUS)
    }

    @Test
    fun `external state reports readiness and heater`() {
        val ready = ExternalState.from(
            connected(MugSnapshot(currentTemperatureC = 57.5f, targetTemperatureC = 57.5f, liquidState = LiquidState.AT_TARGET)),
        )
        assertEquals("Ready to drink", ready.status)
        assertEquals("57.5°", ready.current)
        assertTrue(ready.heaterOn)

        val off = ExternalState.from(connected(MugSnapshot(targetTemperatureC = 0f, liquidState = LiquidState.WARM_NO_CONTROL)))
        assertEquals("Heater off", off.status)
        assertEquals("Off", off.target)
    }

    @Test
    fun `external state ignores changes the widget does not show`() {
        val state = connected(MugSnapshot(currentTemperatureC = 50f))
        assertEquals(ExternalState.from(state), ExternalState.from(state.copy(logLines = listOf("x"))))
        assertNotEquals(ExternalState.from(state), ExternalState.from(state.copy(snapshot = MugSnapshot(currentTemperatureC = 51f))))
    }

    private fun connected(snapshot: MugSnapshot) = EmberAppState(
        connectionState = ConnectionState.CONNECTED,
        selectedAddress = "AA:BB:CC:DD:EE:FF",
        snapshot = snapshot,
        snapshotFresh = true,
    )
}
