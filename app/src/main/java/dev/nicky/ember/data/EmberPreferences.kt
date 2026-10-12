package dev.nicky.ember.data

import android.content.Context
import androidx.core.content.edit
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.MugSnapshot

class EmberPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun profiles(): List<MugProfile> = knownAddresses().map { address ->
        MugProfile(
            address = address,
            advertisedName = preferences.getString(key(address, "name"), null) ?: "Ember mug",
            nickname = preferences.getString(key(address, "nickname"), null).orEmpty(),
            lastSeenAt = preferences.getLong(key(address, "seen"), 0L),
            lastRssi = preferences.getInt(key(address, "rssi"), NO_RSSI).takeUnless { it == NO_RSSI },
        )
    }.sortedByDescending(MugProfile::lastSeenAt)

    fun rememberCandidate(address: String, name: String, rssi: Int?) {
        val addresses = knownAddresses() + address
        preferences.edit {
            putStringSet(KEY_KNOWN_ADDRESSES, addresses)
            putString(key(address, "name"), name)
            putLong(key(address, "seen"), System.currentTimeMillis())
            if (rssi != null) putInt(key(address, "rssi"), rssi)
        }
    }

    fun forget(address: String) {
        val prefix = key(address, "")
        preferences.edit {
            putStringSet(KEY_KNOWN_ADDRESSES, knownAddresses() - address)
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
        }
    }

    fun setNickname(address: String, nickname: String) {
        preferences.edit { putString(key(address, "nickname"), nickname.trim()) }
    }

    fun displayUnit(): DisplayUnit = runCatching {
        DisplayUnit.valueOf(preferences.getString(KEY_DISPLAY_UNIT, null) ?: DisplayUnit.CELSIUS.name)
    }.getOrDefault(DisplayUnit.CELSIUS)

    fun setDisplayUnit(unit: DisplayUnit) {
        preferences.edit { putString(KEY_DISPLAY_UNIT, unit.name) }
    }

    fun presets(): List<Float> {
        val values = preferences.getString(KEY_PRESETS, null)
            ?.split(',')
            ?.mapNotNull(String::toFloatOrNull)
            ?.filter { it in EmberProtocol.MIN_TARGET_CELSIUS..EmberProtocol.MAX_TARGET_CELSIUS }
            .orEmpty()
        return values.takeIf { it.size == PRESET_COUNT } ?: DEFAULT_PRESETS
    }

    fun setPresets(values: List<Float>) {
        require(values.size == PRESET_COUNT)
        preferences.edit { putString(KEY_PRESETS, values.joinToString(",")) }
    }

    fun backgroundMonitoring(): Boolean = preferences.getBoolean(KEY_BACKGROUND_MONITORING, false)

    fun setBackgroundMonitoring(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_BACKGROUND_MONITORING, enabled) }
    }

    fun persistSnapshot(address: String, snapshot: MugSnapshot) {
        preferences.edit {
            putNullable(key(address, "name"), snapshot.name)
            putNullable(key(address, "current"), snapshot.currentTemperatureC)
            putNullable(key(address, "target"), snapshot.targetTemperatureC)
            putNullable(key(address, "battery"), snapshot.batteryPercent)
            putNullable(key(address, "charging"), snapshot.charging)
            putNullable(key(address, "level"), snapshot.liquidLevel)
            putNullable(key(address, "state"), snapshot.liquidState)
            putNullable(key(address, "firmware"), snapshot.firmwareVersion)
            putNullable(key(address, "hardware"), snapshot.hardwareVersion)
            putNullable(key(address, "bootloader"), snapshot.bootloaderVersion)
            putNullable(key(address, "setup_key"), snapshot.setupKeyPresent)
            putNullable(key(address, "led_rgb"), snapshot.ledColor)
            putLong(key(address, "snapshot_time"), System.currentTimeMillis())
        }
    }

    fun cachedSnapshot(address: String?): Pair<MugSnapshot, Long> {
        if (address == null || address !in knownAddresses()) return MugSnapshot() to 0L
        val snapshot = MugSnapshot(
            name = preferences.getString(key(address, "name"), null),
            currentTemperatureC = floatOrNull(key(address, "current")),
            targetTemperatureC = floatOrNull(key(address, "target")),
            liquidLevel = intOrNull(key(address, "level")),
            batteryPercent = intOrNull(key(address, "battery")),
            charging = booleanOrNull(key(address, "charging")),
            liquidState = intOrNull(key(address, "state")),
            firmwareVersion = intOrNull(key(address, "firmware")),
            hardwareVersion = intOrNull(key(address, "hardware")),
            bootloaderVersion = intOrNull(key(address, "bootloader")),
            setupKeyPresent = booleanOrNull(key(address, "setup_key")),
            ledColor = intOrNull(key(address, "led_rgb")) ?: legacyLedColor(address),
        )
        return snapshot to preferences.getLong(key(address, "snapshot_time"), 0L)
    }

    private fun knownAddresses(): Set<String> = preferences.getStringSet(KEY_KNOWN_ADDRESSES, emptySet()).orEmpty()

    private fun floatOrNull(key: String): Float? = if (preferences.contains(key)) preferences.getFloat(key, 0f) else null

    private fun intOrNull(key: String): Int? = if (preferences.contains(key)) preferences.getInt(key, 0) else null

    private fun booleanOrNull(key: String): Boolean? = if (preferences.contains(key)) preferences.getBoolean(key, false) else null

    /** Version 0.2 stored the LED colour as three separate channel keys. */
    private fun legacyLedColor(address: String): Int? {
        val red = intOrNull(key(address, "led_red")) ?: return null
        val green = intOrNull(key(address, "led_green")) ?: return null
        val blue = intOrNull(key(address, "led_blue")) ?: return null
        return (red shl 16) or (green shl 8) or blue
    }

    private fun android.content.SharedPreferences.Editor.putNullable(key: String, value: Any?) {
        when (value) {
            null -> {}
            is String -> putString(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }

    private fun key(address: String, suffix: String): String = "mug_${address.replace(':', '_')}_$suffix"

    companion object {
        private const val FILE_NAME = "ember_preferences"
        private const val KEY_KNOWN_ADDRESSES = "known_addresses"
        private const val KEY_DISPLAY_UNIT = "display_unit"
        private const val KEY_PRESETS = "temperature_presets"
        private const val KEY_BACKGROUND_MONITORING = "background_monitoring"
        private const val NO_RSSI = Int.MIN_VALUE
        const val PRESET_COUNT = 3
        val DEFAULT_PRESETS = listOf(54.5f, 57.5f, 60f)
    }
}
