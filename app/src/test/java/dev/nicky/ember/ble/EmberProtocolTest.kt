package dev.nicky.ember.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmberProtocolTest {
    @Test
    fun `temperature bytes are little endian hundredths of celsius`() {
        assertArrayEquals(byteArrayOf(0x7c, 0x15), EmberProtocol.encodeTemperature(55f))
        assertEquals(55f, EmberProtocol.decodeTemperature(byteArrayOf(0x7c, 0x15))!!, 0.001f)
    }

    @Test
    fun `zero target disables heating`() {
        assertArrayEquals(byteArrayOf(0, 0), EmberProtocol.encodeTemperature(0f))
    }

    @Test
    fun `unsafe target is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmberProtocol.encodeTemperature(70f)
        }
    }

    @Test
    fun `raw mug target rounds to nearest half degree for slider`() {
        assertEquals(57.5f, EmberProtocol.snapTargetForSlider(57.49f), 0.001f)
        assertEquals(57f, EmberProtocol.snapTargetForSlider(57.24f), 0.001f)
    }

    @Test
    fun `liquid state labels cover known protocol values`() {
        assertEquals("Empty", EmberProtocol.liquidStateLabel(1))
        assertEquals("Heating", EmberProtocol.liquidStateLabel(5))
        assertEquals("At target temperature", EmberProtocol.liquidStateLabel(6))
    }

    @Test
    fun `mug name validation matches the sixteen character protocol limit`() {
        assertArrayEquals("Office Ember".toByteArray(), EmberProtocol.encodeName("Office Ember"))
        assertThrows(IllegalArgumentException::class.java) {
            EmberProtocol.encodeName("This name is much too long")
        }
    }

    @Test
    fun `LED colour is encoded as RGBA`() {
        assertArrayEquals(
            byteArrayOf(0x6c, 0x00, 0xff.toByte(), 0xff.toByte()),
            EmberProtocol.encodeLedColor(108, 0, 255),
        )
    }

    @Test
    fun `temperature unit conversions round trip`() {
        val fahrenheit = EmberProtocol.celsiusToFahrenheit(57.5f)
        assertEquals(135.5f, fahrenheit, 0.001f)
        assertEquals(57.5f, EmberProtocol.fahrenheitToCelsius(fahrenheit), 0.001f)
    }
}
