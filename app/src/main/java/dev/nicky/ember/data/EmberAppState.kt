package dev.nicky.ember.data

import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.MugCandidate
import dev.nicky.ember.ble.MugSnapshot

enum class DisplayUnit {
    CELSIUS,
    FAHRENHEIT,
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
    val selectedAddress: String? = null,
    val snapshotAddress: String? = null,
    val snapshot: MugSnapshot = MugSnapshot(),
    val snapshotUpdatedAt: Long = 0L,
    val snapshotFresh: Boolean = false,
    val scanState: ScanState = ScanState.IDLE,
    val scanDetail: String = "",
    val mugChoices: List<MugChoice> = emptyList(),
    val profiles: List<MugProfile> = emptyList(),
    val displayUnit: DisplayUnit = DisplayUnit.CELSIUS,
    val presetsCelsius: List<Float> = listOf(54.5f, 57.5f, 60f),
    val backgroundMonitoring: Boolean = false,
    val writeStatus: WriteStatus = WriteStatus(),
    val logLines: List<String> = emptyList(),
) {
    val connected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    val selectedProfile: MugProfile?
        get() = selectedAddress?.let { address -> profiles.firstOrNull { it.address == address } }
}
