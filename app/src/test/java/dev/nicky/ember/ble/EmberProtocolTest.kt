package dev.nicky.ember.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmberProtocolTest {
    @Test
    fun `temperature bytes are little endian hundredths of celsius`() {
        assertArrayEquals(byteArrayOf(0x7c, 0x15), EmberProtocol.encodeTemperature(55f))
        assertEquals(55f, EmberProtocol.decodeTemperature(byteArrayOf(0x7c, 0x15))!!, 0.001f)
    }

    @Test
    fun `temperature encoding rounds instead of truncating float error`() {
        // 57.3f * 100f is 5729.9995f; truncation would send 57.29 °C.
        assertEquals(5730, decodeRaw(EmberProtocol.encodeTemperature(57.3f)))
        // A Fahrenheit target converted to Celsius must still encode to the nearest hundredth.
        assertEquals(5722, decodeRaw(EmberProtocol.encodeTemperature(EmberProtocol.fahrenheitToCelsius(135f))))
    }

    @Test
    fun `upper limit encodes without overflow`() {
        assertEquals(6300, decodeRaw(EmberProtocol.encodeTemperature(63f)))
    }

    @Test
    fun `zero target disables heating`() {
        assertArrayEquals(byteArrayOf(0, 0), EmberProtocol.encodeTemperature(0f))
    }

    @Test
    fun `unsafe targets are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EmberProtocol.encodeTemperature(70f) }
        assertThrows(IllegalArgumentException::class.java) { EmberProtocol.encodeTemperature(30f) }
        assertFalse(EmberProtocol.isValidTarget(48.9f))
        assertTrue(EmberProtocol.isValidTarget(49f))
    }

    @Test
    fun `raw mug target rounds to nearest half degree for slider`() {
        assertEquals(57.5f, EmberProtocol.snapTargetForSlider(57.49f), 0.001f)
        assertEquals(57f, EmberProtocol.snapTargetForSlider(57.24f), 0.001f)
    }

    @Test
    fun `battery decodes percent and optional charger flag`() {
        assertEquals(BatteryInfo(64, true), EmberProtocol.decodeBattery(byteArrayOf(64, 1, 30)))
        assertEquals(BatteryInfo(64, false), EmberProtocol.decodeBattery(byteArrayOf(64, 0)))
        assertEquals(BatteryInfo(64, null), EmberProtocol.decodeBattery(byteArrayOf(64)))
        assertNull(EmberProtocol.decodeBattery(byteArrayOf()))
    }

    @Test
    fun `liquid state labels cover known protocol values`() {
        assertEquals("Empty", EmberProtocol.liquidStateLabel(LiquidState.EMPTY))
        assertEquals("Heating", EmberProtocol.liquidStateLabel(LiquidState.HEATING))
        assertEquals("At target temperature", EmberProtocol.liquidStateLabel(LiquidState.AT_TARGET))
        assertEquals("Unknown state (42)", EmberProtocol.liquidStateLabel(42))
    }

    @Test
    fun `mug name validation matches the sixteen character protocol limit`() {
        assertArrayEquals("Office Ember".toByteArray(), EmberProtocol.encodeName("Office Ember"))
        assertThrows(IllegalArgumentException::class.java) { EmberProtocol.encodeName("This name is much too long") }
        assertThrows(IllegalArgumentException::class.java) { EmberProtocol.encodeName("Café") }
    }

    @Test
    fun `names are decoded without padding`() {
        assertEquals("Mug", EmberProtocol.decodeName(byteArrayOf(0x4d, 0x75, 0x67, 0, 0)))
    }

    @Test
    fun `LED colour is encoded as RGBA and decoded as RGB`() {
        val encoded = EmberProtocol.encodeLedColor(108, 0, 255)
        assertArrayEquals(byteArrayOf(0x6c, 0x00, 0xff.toByte(), 0xff.toByte()), encoded)
        assertEquals(0x6C00FF, EmberProtocol.decodeLedColor(encoded))
        assertNull(EmberProtocol.decodeLedColor(byteArrayOf(1, 2)))
    }

    @Test
    fun `firmware payload includes optional bootloader version`() {
        val info = EmberProtocol.decodeFirmware(byteArrayOf(0x65, 0x01, 0x04, 0x00, 0x21, 0x01))!!
        assertEquals(357, info.firmware)
        assertEquals(4, info.hardware)
        assertEquals(289, info.bootloader)
    }

    @Test
    fun `short firmware payload remains compatible`() {
        val info = EmberProtocol.decodeFirmware(byteArrayOf(0x65, 0x01, 0x04, 0x00))!!
        assertEquals(357, info.firmware)
        assertEquals(4, info.hardware)
        assertNull(info.bootloader)
    }

    @Test
    fun `temperature unit conversions round trip`() {
        val fahrenheit = EmberProtocol.celsiusToFahrenheit(57.5f)
        assertEquals(135.5f, fahrenheit, 0.001f)
        assertEquals(57.5f, EmberProtocol.fahrenheitToCelsius(fahrenheit), 0.001f)
    }

    @Test
    fun `live values override cached ones field by field`() {
        val cached = MugSnapshot(name = "Office", currentTemperatureC = 40f, batteryPercent = 80, ledColor = 0xFF0000)
        val merged = MugSnapshot(currentTemperatureC = 55f, batteryPercent = 79).withFallback(cached)
        assertEquals("Office", merged.name)
        assertEquals(55f, merged.currentTemperatureC!!, 0f)
        assertEquals(79, merged.batteryPercent)
        assertEquals(0xFF0000, merged.ledColor)
    }

    @Test
    fun `snapshots compare by value`() {
        assertEquals(MugSnapshot(ledColor = 0x123456), MugSnapshot(ledColor = 0x123456))
    }

    private fun decodeRaw(bytes: ByteArray): Int = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
}
