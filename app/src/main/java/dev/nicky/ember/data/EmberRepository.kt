package dev.nicky.ember.data

import android.content.Context
import dev.nicky.ember.BackgroundMonitoringPolicy
import dev.nicky.ember.EmberConnectionService
import dev.nicky.ember.EmberWidgetProvider
import dev.nicky.ember.ble.BleWritePhase
import dev.nicky.ember.ble.BleWriteStatus
import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.EmberBleClient
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.MugCandidate
import dev.nicky.ember.ble.MugSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmberRepository(private val context: Context) : EmberBleClient.Listener {
    private val preferences = EmberPreferences(context)
    private val client = EmberBleClient(context, this)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val initialAddress = client.preferredAddress()
    private val initialCachedSnapshot = preferences.cachedSnapshot(initialAddress)
    private val _state = MutableStateFlow(
        EmberAppState(
            selectedAddress = initialAddress,
            snapshotAddress = initialAddress,
            snapshot = initialCachedSnapshot.first,
            snapshotUpdatedAt = initialCachedSnapshot.second,
            snapshotFresh = false,
            profiles = preferences.profiles(),
            displayUnit = preferences.displayUnit(),
            presetsCelsius = preferences.presets(),
            backgroundMonitoring = preferences.backgroundMonitoring(),
        ),
    )
    val state: StateFlow<EmberAppState> = _state.asStateFlow()

    private var started = false
    private var pendingTargetAfterConnect: Float? = null
    @Volatile private var appVisible = false

    fun start(): Boolean {
        if (started) return false
        started = true
        refreshKnownMugs(client.knownMugs())
        if (BackgroundMonitoringPolicy.shouldRunService(_state.value)) {
            EmberConnectionService.setEnabled(context, true)
        } else if (_state.value.backgroundMonitoring && BackgroundMonitoringPolicy.isKnownEmpty(_state.value)) {
            EmberConnectionService.pauseForEmptyMug(context)
        }
        return true
    }

    fun hasPermissions(): Boolean = client.hasPermissions()

    fun connectPreferred() {
        start()
        client.connectPreferred()
    }

    fun disconnect() {
        pendingTargetAfterConnect = null
        client.disconnect()
    }

    fun cancelConnection() {
        pendingTargetAfterConnect = null
        client.cancelConnection()
    }

    fun startMugScan() {
        start()
        refreshKnownMugs(client.knownMugs())
        client.scanForMugs()
    }

    fun stopMugScan() = client.stopMugScan()

    fun selectMug(address: String) {
        pendingTargetAfterConnect = null
        client.selectMug(address)
    }

    fun refresh() = client.refresh()

    fun setTargetTemperature(celsius: Float) {
        if (_state.value.connected) {
            client.setTargetTemperature(celsius)
        } else {
            pendingTargetAfterConnect = celsius
            _state.update {
                it.copy(writeStatus = WriteStatus(WritePhase.WRITING, "Connecting before applying change…"))
            }
            client.connectPreferred()
        }
    }

    fun setDisplayUnit(unit: DisplayUnit) {
        preferences.setDisplayUnit(unit)
        _state.update { it.copy(displayUnit = unit) }
        if (_state.value.connected) client.setTemperatureUnit(unit == DisplayUnit.FAHRENHEIT)
        publishExternalState()
    }

    fun setPresets(values: List<Float>) {
        val safeValues = values.map { it.coerceIn(49f, 63f) }
        preferences.setPresets(safeValues)
        _state.update { it.copy(presetsCelsius = safeValues) }
        publishExternalState()
    }

    fun setLocalNickname(nickname: String) {
        val address = _state.value.selectedAddress ?: return
        preferences.setNickname(address, nickname)
        refreshProfilesAndChoices()
        appendLog(if (nickname.isBlank()) "Local mug nickname cleared" else "Local mug nickname set to $nickname")
    }

    fun setHardwareName(name: String) = client.setMugName(name)

    fun setLedColor(red: Int, green: Int, blue: Int) = client.setLedColor(red, green, blue)

    fun setBackgroundMonitoring(enabled: Boolean) {
        preferences.setBackgroundMonitoring(enabled)
        _state.update { it.copy(backgroundMonitoring = enabled) }
        if (enabled && BackgroundMonitoringPolicy.isKnownEmpty(_state.value)) {
            EmberConnectionService.pauseForEmptyMug(context)
        } else {
            EmberConnectionService.setEnabled(context, enabled && BackgroundMonitoringPolicy.shouldRunService(_state.value))
        }
        publishExternalState()
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        val state = _state.value
        if (!state.backgroundMonitoring) return

        if (visible) {
            if (state.connectionState == ConnectionState.DISCONNECTED && client.hasPermissions()) {
                client.connectPreferred()
            }
            if (BackgroundMonitoringPolicy.isKnownEmpty(state)) {
                EmberConnectionService.pauseForEmptyMug(context)
            } else {
                EmberConnectionService.setEnabled(context, BackgroundMonitoringPolicy.shouldRunService(state))
            }
        } else if (BackgroundMonitoringPolicy.isKnownEmpty(state)) {
            EmberConnectionService.pauseForEmptyMug(context)
            if (state.connectionState != ConnectionState.DISCONNECTED) client.disconnect()
        } else {
            EmberConnectionService.setEnabled(context, true)
        }
    }

    override fun onConnectionStateChanged(state: ConnectionState, detail: String, selectedAddress: String?) {
        val addressChanged = selectedAddress != null && selectedAddress != _state.value.snapshotAddress
        val cached = if (addressChanged) preferences.cachedSnapshot(selectedAddress) else null
        val pendingFailed = state == ConnectionState.DISCONNECTED && pendingTargetAfterConnect != null && detail != "Disconnected"
        if (pendingFailed) pendingTargetAfterConnect = null
        _state.update { current ->
            current.copy(
                connectionState = state,
                connectionDetail = detail,
                selectedAddress = selectedAddress ?: current.selectedAddress,
                snapshotAddress = if (addressChanged) selectedAddress else current.snapshotAddress,
                snapshot = cached?.first ?: current.snapshot,
                snapshotUpdatedAt = cached?.second ?: current.snapshotUpdatedAt,
                snapshotFresh = state == ConnectionState.CONNECTED && !addressChanged && current.snapshotFresh,
                writeStatus = if (pendingFailed) {
                    WriteStatus(WritePhase.FAILED, "Could not connect to apply the change")
                } else if (state == ConnectionState.DISCONNECTED && current.writeStatus.phase == WritePhase.WRITING) {
                    WriteStatus(WritePhase.FAILED, "Connection ended before the change was confirmed")
                } else {
                    current.writeStatus
                },
            )
        }
        refreshProfilesAndChoices()
        if (state == ConnectionState.CONNECTED) {
            pendingTargetAfterConnect?.let { target ->
                pendingTargetAfterConnect = null
                client.setTargetTemperature(target)
            }
        }
        publishExternalState()
    }

    override fun onSnapshotChanged(address: String, snapshot: MugSnapshot) {
        val previousLiquidState = _state.value.snapshot.liquidState
        val hasData = snapshot.name != null || snapshot.currentTemperatureC != null || snapshot.batteryPercent != null
        if (hasData) preferences.persistSnapshot(address, snapshot)
        snapshot.name?.let { preferences.rememberCandidate(address, it, null) }
        _state.update {
            it.copy(
                selectedAddress = address,
                snapshotAddress = address,
                snapshot = snapshot,
                snapshotUpdatedAt = if (hasData) System.currentTimeMillis() else it.snapshotUpdatedAt,
                snapshotFresh = hasData && it.connectionState == ConnectionState.CONNECTED,
            )
        }
        refreshProfilesAndChoices()
        publishExternalState()

        val state = _state.value
        if (BackgroundMonitoringPolicy.isLiveEmpty(state)) {
            EmberConnectionService.pauseForEmptyMug(context)
            if (previousLiquidState != 1) appendLog("Background monitoring paused while the mug is empty")
            if (!appVisible) client.disconnect()
        } else if (
            state.backgroundMonitoring &&
            BackgroundMonitoringPolicy.isRefill(previousLiquidState, snapshot.liquidState)
        ) {
            EmberConnectionService.setEnabled(context, true)
        }
    }

    override fun onMugCandidates(candidates: List<MugCandidate>) {
        candidates.forEach { preferences.rememberCandidate(it.address, it.name, it.rssi) }
        refreshKnownMugs(candidates)
    }

    override fun onScanStateChanged(scanning: Boolean, detail: String) {
        _state.update {
            it.copy(
                scanState = when {
                    scanning -> ScanState.SCANNING
                    detail.startsWith("Scan failed") || detail.contains("unavailable") -> ScanState.FAILED
                    else -> ScanState.COMPLETE
                },
                scanDetail = detail,
            )
        }
    }

    override fun onWriteStatus(status: BleWriteStatus) {
        _state.update {
            it.copy(
                writeStatus = WriteStatus(
                    phase = when (status.phase) {
                        BleWritePhase.WRITING -> WritePhase.WRITING
                        BleWritePhase.CONFIRMED -> WritePhase.CONFIRMED
                        BleWritePhase.FAILED -> WritePhase.FAILED
                    },
                    message = status.message,
                ),
            )
        }
        publishExternalState()
    }

    override fun onLog(message: String) = appendLog(message)

    private fun refreshKnownMugs(candidates: List<MugCandidate>) {
        val profiles = preferences.profiles()
        val byAddress = linkedMapOf<String, MugCandidate>()
        candidates.forEach { byAddress[it.address] = it }
        profiles.forEach { profile ->
            byAddress.putIfAbsent(
                profile.address,
                MugCandidate(
                    address = profile.address,
                    name = profile.advertisedName,
                    rssi = profile.lastRssi,
                    bonded = false,
                ),
            )
        }
        val selectedAddress = _state.value.selectedAddress
        val connected = _state.value.connected
        val choices = byAddress.values.map { candidate ->
            MugChoice(
                candidate = candidate,
                profile = profiles.firstOrNull { it.address == candidate.address },
                selected = candidate.address == selectedAddress,
                connected = connected && candidate.address == selectedAddress,
            )
        }.sortedWith(
            compareByDescending<MugChoice> { it.connected }
                .thenByDescending { it.selected }
                .thenByDescending { it.candidate.bonded }
                .thenByDescending { it.candidate.rssi ?: Int.MIN_VALUE },
        )
        _state.update { it.copy(profiles = profiles, mugChoices = choices) }
    }

    private fun refreshProfilesAndChoices() {
        refreshKnownMugs(_state.value.mugChoices.map(MugChoice::candidate))
    }

    private fun appendLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        _state.update { it.copy(logLines = (listOf(line) + it.logLines).take(MAX_LOG_LINES)) }
    }

    private fun publishExternalState() {
        EmberWidgetProvider.updateAll(context, _state.value)
    }

    companion object {
        const val MAX_LOG_LINES = 12

        fun displayTemperature(celsius: Float, unit: DisplayUnit): Float = when (unit) {
            DisplayUnit.CELSIUS -> celsius
            DisplayUnit.FAHRENHEIT -> EmberProtocol.celsiusToFahrenheit(celsius)
        }

        fun inputToCelsius(value: Float, unit: DisplayUnit): Float = when (unit) {
            DisplayUnit.CELSIUS -> value
            DisplayUnit.FAHRENHEIT -> EmberProtocol.fahrenheitToCelsius(value).coerceIn(49f, 63f)
        }
    }
}
