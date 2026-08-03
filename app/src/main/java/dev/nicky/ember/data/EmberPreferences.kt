package dev.nicky.ember.data

import android.content.Context
import androidx.core.content.edit
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
        val addresses = knownAddresses().toMutableSet().apply { add(address) }
        preferences.edit {
            putStringSet(KEY_KNOWN_ADDRESSES, addresses)
            putString(key(address, "name"), name)
            putLong(key(address, "seen"), System.currentTimeMillis())
            if (rssi != null) putInt(key(address, "rssi"), rssi)
        }
    }

    fun setNickname(address: String, nickname: String) {
        preferences.edit { putString(key(address, "nickname"), nickname.trim()) }
    }

    fun displayUnit(): DisplayUnit = runCatching {
        DisplayUnit.valueOf(preferences.getString(KEY_DISPLAY_UNIT, DisplayUnit.CELSIUS.name)!!)
    }.getOrDefault(DisplayUnit.CELSIUS)

    fun setDisplayUnit(unit: DisplayUnit) {
        preferences.edit { putString(KEY_DISPLAY_UNIT, unit.name) }
    }

    fun presets(): List<Float> {
        val values = preferences.getString(KEY_PRESETS, null)
            ?.split(',')
            ?.mapNotNull(String::toFloatOrNull)
            ?.filter { it in 49f..63f }
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
            snapshot.name?.let { putString(key(address, "name"), it) }
            snapshot.currentTemperatureC?.let { putFloat(key(address, "current"), it) }
            snapshot.targetTemperatureC?.let { putFloat(key(address, "target"), it) }
            snapshot.batteryPercent?.let { putInt(key(address, "battery"), it) }
            snapshot.charging?.let { putBoolean(key(address, "charging"), it) }
            snapshot.liquidLevel?.let { putInt(key(address, "level"), it) }
            snapshot.liquidState?.let { putInt(key(address, "state"), it) }
            putLong(key(address, "snapshot_time"), System.currentTimeMillis())
        }
    }

    fun cachedSnapshot(address: String?): Pair<MugSnapshot, Long> {
        if (address == null || !knownAddresses().contains(address)) return MugSnapshot() to 0L
        val snapshot = MugSnapshot(
            name = preferences.getString(key(address, "name"), null),
            currentTemperatureC = floatOrNull(key(address, "current")),
            targetTemperatureC = floatOrNull(key(address, "target")),
            liquidLevel = intOrNull(key(address, "level")),
            batteryPercent = intOrNull(key(address, "battery")),
            charging = booleanOrNull(key(address, "charging")),
            liquidState = intOrNull(key(address, "state")),
        )
        return snapshot to preferences.getLong(key(address, "snapshot_time"), 0L)
    }

    private fun knownAddresses(): Set<String> = preferences.getStringSet(KEY_KNOWN_ADDRESSES, emptySet()).orEmpty()

    private fun floatOrNull(key: String): Float? = if (preferences.contains(key)) preferences.getFloat(key, 0f) else null

    private fun intOrNull(key: String): Int? = if (preferences.contains(key)) preferences.getInt(key, 0) else null

    private fun booleanOrNull(key: String): Boolean? = if (preferences.contains(key)) preferences.getBoolean(key, false) else null

    private fun key(address: String, suffix: String): String = "mug_${address.replace(':', '_')}_$suffix"

    private companion object {
        const val FILE_NAME = "ember_preferences"
        const val KEY_KNOWN_ADDRESSES = "known_addresses"
        const val KEY_DISPLAY_UNIT = "display_unit"
        const val KEY_PRESETS = "temperature_presets"
        const val KEY_BACKGROUND_MONITORING = "background_monitoring"
        const val NO_RSSI = Int.MIN_VALUE
        const val PRESET_COUNT = 3
        val DEFAULT_PRESETS = listOf(54.5f, 57.5f, 60f)
    }
}
