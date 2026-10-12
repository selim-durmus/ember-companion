package dev.nicky.ember.ble

import java.util.UUID
import kotlin.math.roundToInt

object EmberProtocol {
    private const val UUID_TEMPLATE = "fc54%04x-236c-4c94-8fa9-944a3e5353fa"

    const val MIN_TARGET_CELSIUS = 49f
    const val MAX_TARGET_CELSIUS = 63f
    const val HEATER_OFF_CELSIUS = 0f
    const val MAX_NAME_LENGTH = 16
    const val FULL_LIQUID_LEVEL = 30

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

    fun isValidTarget(celsius: Float): Boolean =
        celsius == HEATER_OFF_CELSIUS || celsius in MIN_TARGET_CELSIUS..MAX_TARGET_CELSIUS

    fun encodeTemperature(celsius: Float): ByteArray {
        require(isValidTarget(celsius)) {
            "Temperature must be off (0 °C) or between 49 and 63 °C"
        }
        // Round rather than truncate: 57.3f * 100 is 5729.9995 in binary floating point.
        val hundredths = (celsius * 100f).roundToInt()
        return byteArrayOf((hundredths and 0xff).toByte(), ((hundredths shr 8) and 0xff).toByte())
    }

    fun decodeTemperature(value: ByteArray): Float? {
        if (value.size < 2) return null
        return littleEndianU16(value, 0) / 100f
    }

    fun decodeBattery(value: ByteArray): BatteryInfo? {
        if (value.isEmpty()) return null
        return BatteryInfo(
            percent = (value[0].toInt() and 0xff).coerceIn(0, 100),
            charging = value.getOrNull(1)?.let { (it.toInt() and 0xff) == 1 },
        )
    }

    fun decodeFirmware(value: ByteArray): FirmwareInfo? {
        if (value.size < 4) return null
        return FirmwareInfo(
            firmware = littleEndianU16(value, 0),
            hardware = littleEndianU16(value, 2),
            bootloader = value.takeIf { it.size >= 6 }?.let { littleEndianU16(it, 4) },
        )
    }

    fun decodeName(value: ByteArray): String = value.toString(Charsets.UTF_8).trimEnd('\u0000').trim()

    /** Returns the colour as 0xRRGGBB, ignoring the trailing alpha byte. */
    fun decodeLedColor(value: ByteArray): Int? {
        if (value.size < 3) return null
        return ((value[0].toInt() and 0xff) shl 16) or
            ((value[1].toInt() and 0xff) shl 8) or
            (value[2].toInt() and 0xff)
    }

    fun snapTargetForSlider(celsius: Float): Float = (celsius * 2f).roundToInt() / 2f

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    fun encodeName(name: String): ByteArray {
        require(isValidName(name)) {
            "Mug name must contain 1–16 supported characters"
        }
        return name.toByteArray(Charsets.US_ASCII)
    }

    fun encodeLedColor(red: Int, green: Int, blue: Int): ByteArray {
        require(red in 0..255 && green in 0..255 && blue in 0..255) { "LED channels must be 0–255" }
        return byteArrayOf(red.toByte(), green.toByte(), blue.toByte(), 0xff.toByte())
    }

    fun encodeTemperatureUnit(fahrenheit: Boolean): ByteArray = byteArrayOf(if (fahrenheit) 1 else 0)

    fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 9f / 5f) + 32f

    fun fahrenheitToCelsius(fahrenheit: Float): Float = (fahrenheit - 32f) * 5f / 9f

    fun liquidStateLabel(code: Int?): String = when (code) {
        LiquidState.STANDBY -> "Standby"
        LiquidState.EMPTY -> "Empty"
        LiquidState.FILLING -> "Filling"
        LiquidState.COLD_NO_CONTROL -> "Cold · temperature control off"
        LiquidState.COOLING -> "Cooling"
        LiquidState.HEATING -> "Heating"
        LiquidState.AT_TARGET -> "At target temperature"
        LiquidState.WARM_NO_CONTROL -> "Warm · temperature control off"
        null -> "Waiting for mug"
        else -> "Unknown state ($code)"
    }

    private fun characteristic(id: Int): UUID = UUID.fromString(UUID_TEMPLATE.format(id))

    private fun littleEndianU16(value: ByteArray, offset: Int): Int {
        return (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)
    }

    private val NAME_PATTERN = Regex("^[A-Za-z0-9,.\\[\\]#()!\"';:|_+<>%= \\-]{1,$MAX_NAME_LENGTH}$")
}

object LiquidState {
    const val STANDBY = 0
    const val EMPTY = 1
    const val FILLING = 2
    const val COLD_NO_CONTROL = 3
    const val COOLING = 4
    const val HEATING = 5
    const val AT_TARGET = 6
    const val WARM_NO_CONTROL = 7
}

/** Push-event identifiers sent on [EmberProtocol.PUSH_EVENT]. */
object PushEvent {
    const val BATTERY_CHANGED = 1
    const val CHARGING = 2
    const val NOT_CHARGING = 3
    const val TARGET_TEMPERATURE_CHANGED = 4
    const val CURRENT_TEMPERATURE_CHANGED = 5
    const val AUTH_INFO_MISSING = 6
    const val LIQUID_LEVEL_CHANGED = 7
    const val LIQUID_STATE_CHANGED = 8
}

data class BatteryInfo(
    val percent: Int,
    val charging: Boolean?,
)

data class FirmwareInfo(
    val firmware: Int,
    val hardware: Int,
    val bootloader: Int?,
)

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
    val bootloaderVersion: Int? = null,
    val setupKeyPresent: Boolean? = null,
    /** LED colour as 0xRRGGBB. */
    val ledColor: Int? = null,
) {
    val hasData: Boolean
        get() = name != null || currentTemperatureC != null || batteryPercent != null || liquidState != null

    /** Fills every field this snapshot has not read yet from [base]. */
    fun withFallback(base: MugSnapshot): MugSnapshot = MugSnapshot(
        name = name ?: base.name,
        currentTemperatureC = currentTemperatureC ?: base.currentTemperatureC,
        targetTemperatureC = targetTemperatureC ?: base.targetTemperatureC,
        temperatureUnit = temperatureUnit ?: base.temperatureUnit,
        liquidLevel = liquidLevel ?: base.liquidLevel,
        batteryPercent = batteryPercent ?: base.batteryPercent,
        charging = charging ?: base.charging,
        liquidState = liquidState ?: base.liquidState,
        firmwareVersion = firmwareVersion ?: base.firmwareVersion,
        hardwareVersion = hardwareVersion ?: base.hardwareVersion,
        bootloaderVersion = bootloaderVersion ?: base.bootloaderVersion,
        setupKeyPresent = setupKeyPresent ?: base.setupKeyPresent,
        ledColor = ledColor ?: base.ledColor,
    )
}

data class MugCandidate(
    val address: String,
    val name: String,
    val rssi: Int?,
    val bonded: Boolean,
)

enum class ConnectionState {
    DISCONNECTED,
    PAIRING,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
}
