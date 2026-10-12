package dev.nicky.ember.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.nicky.ember.BackgroundMonitoringPolicy
import dev.nicky.ember.EmberConnectionService
import dev.nicky.ember.EmberTileService
import dev.nicky.ember.EmberWidgetProvider
import dev.nicky.ember.ExternalState
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

/**
 * Single source of truth shared by the activity, the monitoring service, the widget and the
 * Quick Settings tile. All methods must be called on the main thread.
 */
class EmberRepository(private val context: Context) : EmberBleClient.Listener {
    private val preferences = EmberPreferences(context)
    private val client = EmberBleClient(context, this)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state: MutableStateFlow<EmberAppState>
    val state: StateFlow<EmberAppState>

    /** Cached values for [EmberAppState.snapshotAddress]; live reads are layered on top. */
    private var baseSnapshot: MugSnapshot
    private var lastPersisted: MugSnapshot? = null
    private var sessionSynced = false
    private var unitSyncedThisSession = false
    private var pendingTarget: Float? = null
    private var appVisible = false
    private var serviceRequested = false
    private var serviceStartBlocked = false
    private var quickActionsInFlight = 0
    private var lastExternalState: ExternalState? = null
    private var writeSequence = 0L

    private val idleDisconnect = Runnable {
        if (!appVisible && !serviceRequested && quickActionsInFlight == 0 && _state.value.connectionState != ConnectionState.DISCONNECTED) {
            appendLog("Disconnected while the app is in the background")
            client.disconnect()
        }
    }
    private val pendingTargetTimeout = Runnable {
        if (pendingTarget != null) {
            pendingTarget = null
            publishWriteStatus(WritePhase.FAILED, "Couldn't reach the mug to apply the change")
        }
    }

    init {
        val address = client.preferredAddress()
        val (cached, updatedAt) = preferences.cachedSnapshot(address)
        baseSnapshot = cached
        lastPersisted = cached
        _state = MutableStateFlow(
            EmberAppState(
                bluetoothEnabled = client.isBluetoothEnabled(),
                hasPermissions = client.hasPermissions(),
                selectedAddress = address,
                snapshotAddress = address,
                snapshot = cached,
                snapshotUpdatedAt = updatedAt,
                profiles = preferences.profiles(),
                displayUnit = preferences.displayUnit(),
                presetsCelsius = preferences.presets(),
                backgroundMonitoring = preferences.backgroundMonitoring(),
            ),
        )
        state = _state.asStateFlow()
        refreshKnownMugs(client.knownMugs())
    }

    // region Commands

    /** Re-reads permission and adapter state, e.g. after the user granted access. */
    fun refreshEnvironment() {
        _state.update { it.copy(hasPermissions = client.hasPermissions(), bluetoothEnabled = client.isBluetoothEnabled()) }
        refreshKnownMugs(client.knownMugs())
        syncMonitoring()
    }

    fun connectPreferred(): EmberBleClient.ConnectResult {
        refreshEnvironment()
        return client.connectPreferred()
    }

    fun disconnect() {
        clearPendingTarget()
        client.disconnect()
    }

    fun startMugScan() {
        refreshKnownMugs(client.knownMugs())
        client.scanForMugs()
    }

    fun stopMugScan() = client.stopMugScan()

    fun selectMug(address: String) {
        clearPendingTarget()
        client.selectMug(address)
    }

    fun forgetMug(address: String) {
        if (address == _state.value.selectedAddress) return
        preferences.forget(address)
        refreshKnownMugs(_state.value.mugChoices.map(MugChoice::candidate).filterNot { it.address == address && !it.bonded })
        appendLog("Removed a saved mug")
    }

    fun refresh() = client.refresh()

    fun setTargetTemperature(celsius: Float) {
        if (!EmberProtocol.isValidTarget(celsius)) return
        if (_state.value.connected) {
            clearPendingTarget()
            client.setTargetTemperature(celsius)
            return
        }
        pendingTarget = celsius
        handler.removeCallbacks(pendingTargetTimeout)
        handler.postDelayed(pendingTargetTimeout, PENDING_TARGET_TIMEOUT_MS)
        publishWriteStatus(WritePhase.WRITING, "Connecting to apply the change…")
        if (_state.value.connectionState == ConnectionState.DISCONNECTED) {
            when (connectPreferred()) {
                EmberBleClient.ConnectResult.STARTED -> Unit
                else -> {
                    clearPendingTarget()
                    publishWriteStatus(WritePhase.FAILED, "Couldn't reach the mug to apply the change")
                }
            }
        }
    }

    fun setDisplayUnit(unit: DisplayUnit) {
        preferences.setDisplayUnit(unit)
        _state.update { it.copy(displayUnit = unit) }
        if (_state.value.connected) client.setTemperatureUnit(unit == DisplayUnit.FAHRENHEIT)
        publishExternalState()
    }

    fun setPresets(values: List<Float>) {
        val safeValues = values.map { it.coerceIn(EmberProtocol.MIN_TARGET_CELSIUS, EmberProtocol.MAX_TARGET_CELSIUS) }
        preferences.setPresets(safeValues)
        _state.update { it.copy(presetsCelsius = safeValues) }
        publishExternalState()
    }

    fun setLocalNickname(nickname: String) {
        val address = _state.value.selectedAddress ?: return
        preferences.setNickname(address, nickname)
        refreshProfilesAndChoices()
        publishExternalState()
    }

    fun setHardwareName(name: String) = client.setMugName(name)

    fun setLedColor(red: Int, green: Int, blue: Int) = client.setLedColor(red, green, blue)

    fun setBackgroundMonitoring(enabled: Boolean) {
        preferences.setBackgroundMonitoring(enabled)
        _state.update { it.copy(backgroundMonitoring = enabled) }
        if (!enabled) EmberConnectionService.cancelNotifications(context)
        syncMonitoring()
        publishExternalState()
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (visible) {
            refreshEnvironment()
            if (_state.value.connectionState == ConnectionState.DISCONNECTED && _state.value.hasPermissions) {
                client.connectPreferred()
            }
        }
        syncMonitoring()
    }

    /**
     * Runs a widget/tile/notification command, connecting first if needed. [onDone] is invoked
     * once the change is confirmed, fails, or times out, so callers can release their wake hold.
     */
    fun runQuickAction(action: QuickAction, onDone: () -> Unit = {}) {
        quickActionsInFlight += 1
        syncMonitoring()
        val startSequence = _state.value.writeStatus.sequence
        var finished = false
        lateinit var watcher: Runnable
        val finish = {
            if (!finished) {
                finished = true
                handler.removeCallbacks(watcher)
                quickActionsInFlight -= 1
                syncMonitoring()
                onDone()
            }
        }
        val deadline = System.currentTimeMillis() + QUICK_ACTION_TIMEOUT_MS
        watcher = Runnable {
            val status = _state.value.writeStatus
            val settled = status.sequence != startSequence &&
                (status.phase == WritePhase.CONFIRMED || status.phase == WritePhase.FAILED)
            if (settled || System.currentTimeMillis() >= deadline) finish() else handler.postDelayed(watcher, 250L)
        }
        val state = _state.value
        when (action) {
            QuickAction.HEATER_OFF -> setTargetTemperature(EmberProtocol.HEATER_OFF_CELSIUS)
            QuickAction.PRESET -> state.presetsCelsius.getOrNull(1)?.let(::setTargetTemperature)
            QuickAction.TOGGLE_HEATER -> if (state.heaterOn) {
                setTargetTemperature(EmberProtocol.HEATER_OFF_CELSIUS)
            } else {
                state.presetsCelsius.getOrNull(1)?.let(::setTargetTemperature)
            }
            QuickAction.CONNECT -> {
                if (!state.connected) connectPreferred()
                handler.postDelayed({ finish() }, CONNECT_HOLD_MS)
                return
            }
        }
        handler.post(watcher)
    }

    // endregion

    // region BLE listener

    override fun onConnectionStateChanged(state: ConnectionState, detail: String, selectedAddress: String?) {
        if (state != ConnectionState.CONNECTED) {
            sessionSynced = false
            unitSyncedThisSession = false
        }
        selectedAddress?.let(::switchSnapshotAddress)
        val pendingFailed = state == ConnectionState.DISCONNECTED && pendingTarget != null
        if (pendingFailed) clearPendingTarget()
        _state.update { current ->
            current.copy(
                connectionState = state,
                connectionDetail = detail,
                bluetoothEnabled = client.isBluetoothEnabled(),
                selectedAddress = selectedAddress ?: current.selectedAddress,
                snapshotFresh = state == ConnectionState.CONNECTED && sessionSynced,
            )
        }
        if (pendingFailed) publishWriteStatus(WritePhase.FAILED, "Couldn't reach the mug to apply the change")
        refreshProfilesAndChoices()
        if (state == ConnectionState.CONNECTED) {
            pendingTarget?.let { target ->
                clearPendingTarget()
                client.setTargetTemperature(target)
            }
        }
        syncMonitoring()
        publishExternalState()
    }

    override fun onSnapshotChanged(address: String, snapshot: MugSnapshot, synced: Boolean) {
        switchSnapshotAddress(address)
        sessionSynced = synced
        val merged = snapshot.withFallback(baseSnapshot)
        if (synced && merged != lastPersisted && merged.hasData) {
            preferences.persistSnapshot(address, merged)
            lastPersisted = merged
            snapshot.name?.let { name ->
                if (_state.value.profiles.none { it.address == address && it.advertisedName == name }) {
                    preferences.rememberCandidate(address, name, null)
                    refreshProfilesAndChoices()
                }
            }
        }
        _state.update {
            it.copy(
                selectedAddress = address,
                snapshotAddress = address,
                snapshot = merged,
                snapshotUpdatedAt = if (synced) System.currentTimeMillis() else it.snapshotUpdatedAt,
                snapshotFresh = synced && it.connected,
            )
        }
        if (synced && !unitSyncedThisSession && _state.value.connected) {
            unitSyncedThisSession = true
            val wantFahrenheit = _state.value.displayUnit == DisplayUnit.FAHRENHEIT
            val mugUnit = merged.temperatureUnit
            if (mugUnit != null && (mugUnit == 1) != wantFahrenheit) client.setTemperatureUnit(wantFahrenheit)
        }
        syncMonitoring()
        publishExternalState()
    }

    override fun onMugCandidates(candidates: List<MugCandidate>) {
        candidates.filter { it.rssi != null }.forEach { preferences.rememberCandidate(it.address, it.name, it.rssi) }
        refreshKnownMugs(candidates)
    }

    override fun onScanStateChanged(scanning: Boolean, detail: String) {
        _state.update {
            it.copy(
                scanState = when {
                    scanning -> ScanState.SCANNING
                    detail.startsWith("Scan failed") || detail.contains("unavailable") || detail.contains("off") -> ScanState.FAILED
                    else -> ScanState.COMPLETE
                },
                scanDetail = detail,
            )
        }
    }

    override fun onWriteStatus(status: BleWriteStatus) {
        publishWriteStatus(
            when (status.phase) {
                BleWritePhase.WRITING -> WritePhase.WRITING
                BleWritePhase.CONFIRMED -> WritePhase.CONFIRMED
                BleWritePhase.FAILED -> WritePhase.FAILED
            },
            status.message,
        )
        if (status.phase == BleWritePhase.FAILED) appendLog(status.message)
    }

    override fun onLog(message: String) = appendLog(message)

    // endregion

    private fun switchSnapshotAddress(address: String) {
        if (address == _state.value.snapshotAddress) return
        val (cached, updatedAt) = preferences.cachedSnapshot(address)
        baseSnapshot = cached
        lastPersisted = cached
        sessionSynced = false
        _state.update {
            it.copy(snapshotAddress = address, snapshot = cached, snapshotUpdatedAt = updatedAt, snapshotFresh = false)
        }
    }

    /**
     * Decides whether the foreground monitoring service should run and how hard the BLE client
     * should try to stay connected, then schedules an idle disconnect when nothing needs the mug.
     */
    private fun syncMonitoring() {
        val current = _state.value
        val wantService = BackgroundMonitoringPolicy.shouldRunService(current)
        if (wantService != serviceRequested) {
            serviceRequested = if (wantService) EmberConnectionService.start(context) else {
                EmberConnectionService.stop(context)
                false
            }
            if (wantService && !serviceRequested && !serviceStartBlocked) {
                serviceStartBlocked = true
                appendLog("Android blocked background monitoring; open the app to resume it")
            }
            if (serviceRequested) serviceStartBlocked = false
            if (!wantService && current.backgroundMonitoring && BackgroundMonitoringPolicy.isKnownEmpty(current)) {
                appendLog("Mug is empty; monitoring pauses until it is refilled")
            }
        }
        if (BackgroundMonitoringPolicy.isLiveEmpty(current)) EmberConnectionService.cancelReadyNotification(context)
        if (current.monitoringActive != serviceRequested) _state.update { it.copy(monitoringActive = serviceRequested) }
        client.setPersistentReconnect(serviceRequested || appVisible)
        handler.removeCallbacks(idleDisconnect)
        if (!appVisible && !serviceRequested && quickActionsInFlight == 0 && current.connectionState != ConnectionState.DISCONNECTED) {
            handler.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS)
        }
    }

    private fun clearPendingTarget() {
        pendingTarget = null
        handler.removeCallbacks(pendingTargetTimeout)
    }

    private fun publishWriteStatus(phase: WritePhase, message: String) {
        writeSequence += 1
        _state.update { it.copy(writeStatus = WriteStatus(phase, message, writeSequence)) }
    }

    private fun refreshKnownMugs(candidates: List<MugCandidate>) {
        val profiles = preferences.profiles()
        val byAddress = linkedMapOf<String, MugCandidate>()
        candidates.forEach { byAddress[it.address] = it }
        profiles.forEach { profile ->
            byAddress.putIfAbsent(
                profile.address,
                MugCandidate(address = profile.address, name = profile.advertisedName, rssi = null, bonded = false),
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
            compareByDescending<MugChoice> { it.selected }
                .thenByDescending { it.candidate.bonded }
                .thenByDescending { it.candidate.rssi ?: Int.MIN_VALUE }
                .thenBy { it.displayName },
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

    /** Pushes state to the widget and tile, but only when something they display changed. */
    private fun publishExternalState() {
        val external = ExternalState.from(_state.value)
        if (external == lastExternalState) return
        lastExternalState = external
        EmberWidgetProvider.updateAll(context, external)
        EmberTileService.requestUpdate(context)
    }

    companion object {
        const val MAX_LOG_LINES = 60
        private const val IDLE_DISCONNECT_MS = 5_000L
        private const val PENDING_TARGET_TIMEOUT_MS = 30_000L
        private const val QUICK_ACTION_TIMEOUT_MS = 9_000L
        private const val CONNECT_HOLD_MS = 8_000L
    }
}

enum class QuickAction {
    HEATER_OFF,
    PRESET,
    TOGGLE_HEATER,
    CONNECT,
}
