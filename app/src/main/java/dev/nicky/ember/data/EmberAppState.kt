package dev.nicky.ember.data

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.MugCandidate
import dev.nicky.ember.ble.MugSnapshot

enum class DisplayUnit {
    CELSIUS,
    FAHRENHEIT,
    ;

    val symbol: String
        get() = if (this == CELSIUS) "°C" else "°F"

    /** Smallest step the target controls move by, in this unit. */
    val step: Float
        get() = if (this == CELSIUS) 0.5f else 1f

    fun fromCelsius(celsius: Float): Float =
        if (this == CELSIUS) celsius else EmberProtocol.celsiusToFahrenheit(celsius)

    fun toCelsius(value: Float): Float =
        if (this == CELSIUS) value else EmberProtocol.fahrenheitToCelsius(value)

    /** Display range that maps inside the mug's 49–63 °C limits. */
    val range: ClosedFloatingPointRange<Float>
        get() = if (this == CELSIUS) {
            EmberProtocol.MIN_TARGET_CELSIUS..EmberProtocol.MAX_TARGET_CELSIUS
        } else {
            121f..145f
        }
}

enum class ScanState {
    IDLE,
    SCANNING,
    COMPLETE,
    FAILED,
}

enum class WritePhase {
    IDLE,
    WRITING,
    CONFIRMED,
    FAILED,
}

data class WriteStatus(
    val phase: WritePhase = WritePhase.IDLE,
    val message: String = "",
    /** Increments on every status so the UI can show each failure exactly once. */
    val sequence: Long = 0L,
)

data class MugProfile(
    val address: String,
    val advertisedName: String = "Ember mug",
    val nickname: String = "",
    val lastSeenAt: Long = 0L,
    val lastRssi: Int? = null,
) {
    val displayName: String
        get() = nickname.ifBlank { advertisedName }
}

data class MugChoice(
    val candidate: MugCandidate,
    val profile: MugProfile?,
    val selected: Boolean,
    val connected: Boolean,
) {
    val displayName: String
        get() = profile?.displayName ?: candidate.name
}

data class EmberAppState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionDetail: String = "Not connected",
    val bluetoothEnabled: Boolean = true,
    val hasPermissions: Boolean = false,
    val selectedAddress: String? = null,
    val snapshotAddress: String? = null,
    val snapshot: MugSnapshot = MugSnapshot(),
    val snapshotUpdatedAt: Long = 0L,
    /** True only when [snapshot] was fully read from the mug during the current connection. */
    val snapshotFresh: Boolean = false,
    val scanState: ScanState = ScanState.IDLE,
    val scanDetail: String = "",
    val mugChoices: List<MugChoice> = emptyList(),
    val profiles: List<MugProfile> = emptyList(),
    val displayUnit: DisplayUnit = DisplayUnit.CELSIUS,
    val presetsCelsius: List<Float> = EmberPreferences.DEFAULT_PRESETS,
    val backgroundMonitoring: Boolean = false,
    val monitoringActive: Boolean = false,
    val writeStatus: WriteStatus = WriteStatus(),
    val logLines: List<String> = emptyList(),
) {
    val connected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    val busy: Boolean
        get() = connectionState != ConnectionState.DISCONNECTED && connectionState != ConnectionState.CONNECTED

    val selectedProfile: MugProfile?
        get() = selectedAddress?.let { address -> profiles.firstOrNull { it.address == address } }

    val mugDisplayName: String
        get() = selectedProfile?.nickname?.takeIf(String::isNotBlank)
            ?: snapshot.name?.takeIf(String::isNotBlank)
            ?: selectedProfile?.advertisedName
            ?: "Ember"

    val heaterOn: Boolean
        get() = (snapshot.targetTemperatureC ?: 0f) > 0f
}
