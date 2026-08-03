package dev.nicky.ember.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.round

object EmberProtocol {
    private const val UUID_TEMPLATE = "fc54%04x-236c-4c94-8fa9-944a3e5353fa"

    val SERVICE: UUID = characteristic(0x3622)
    val NAME: UUID = characteristic(0x0001)
    val CURRENT_TEMPERATURE: UUID = characteristic(0x0002)
    val TARGET_TEMPERATURE: UUID = characteristic(0x0003)
    val TEMPERATURE_UNIT: UUID = characteristic(0x0004)
    val LIQUID_LEVEL: UUID = characteristic(0x0005)
    val BATTERY: UUID = characteristic(0x0007)
    val LIQUID_STATE: UUID = characteristic(0x0008)
    val FIRMWARE: UUID = characteristic(0x000c)
    val UDSK: UUID = characteristic(0x000f)
    val PUSH_EVENT: UUID = characteristic(0x0012)
    val LED: UUID = characteristic(0x0014)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val readableCharacteristics = listOf(
        NAME,
        CURRENT_TEMPERATURE,
        TARGET_TEMPERATURE,
        TEMPERATURE_UNIT,
        LIQUID_LEVEL,
        BATTERY,
        LIQUID_STATE,
        FIRMWARE,
        UDSK,
        LED,
    )

    fun encodeTemperature(celsius: Float): ByteArray {
        require(celsius == 0f || celsius in 49f..63f) {
            "Temperature must be off (0 °C) or between 49 and 63 °C"
        }
        val hundredths = (celsius * 100f).toInt()
        return ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(hundredths.toShort())
            .array()
    }

    fun decodeTemperature(value: ByteArray): Float? {
        if (value.size < 2) return null
        val raw = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
        return raw / 100f
    }

    fun snapTargetForSlider(celsius: Float): Float = round(celsius * 2f) / 2f

    fun encodeName(name: String): ByteArray {
        require(NAME_PATTERN.matches(name)) {
            "Mug name must contain 1–16 supported characters"
        }
        return name.toByteArray(Charsets.US_ASCII)
    }

    fun encodeLedColor(red: Int, green: Int, blue: Int): ByteArray {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return byteArrayOf(red.toByte(), green.toByte(), blue.toByte(), 0xff.toByte())
    }

    fun encodeTemperatureUnit(fahrenheit: Boolean): ByteArray = byteArrayOf(if (fahrenheit) 1 else 0)

    fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 9f / 5f) + 32f

    fun fahrenheitToCelsius(fahrenheit: Float): Float = (fahrenheit - 32f) * 5f / 9f

    fun liquidStateLabel(code: Int?): String = when (code) {
        0 -> "Standby"
        1 -> "Empty"
        2 -> "Filling"
        3 -> "Cold · temperature control off"
        4 -> "Cooling"
        5 -> "Heating"
        6 -> "At target temperature"
        7 -> "Warm · temperature control off"
        null -> "Waiting for mug"
        else -> "Unknown state ($code)"
    }

    private fun characteristic(id: Int): UUID = UUID.fromString(UUID_TEMPLATE.format(id))

    private val NAME_PATTERN = Regex("^[A-Za-z0-9,.\\[\\]#()!\"';:|_+<>%= \\-]{1,16}$")
}

data class MugSnapshot(
    val name: String? = null,
    val currentTemperatureC: Float? = null,
    val targetTemperatureC: Float? = null,
    val temperatureUnit: Int? = null,
    val liquidLevel: Int? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val liquidState: Int? = null,
    val firmwareVersion: Int? = null,
    val hardwareVersion: Int? = null,
    val setupKeyPresent: Boolean? = null,
    val ledRgba: IntArray? = null,
)

data class MugCandidate(
    val address: String,
    val name: String,
    val rssi: Int?,
    val bonded: Boolean,
)

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    PAIRING,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
}
