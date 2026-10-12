package dev.nicky.ember.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

enum class BleWritePhase {
    WRITING,
    CONFIRMED,
    FAILED,
}

data class BleWriteStatus(
    val phase: BleWritePhase,
    val characteristic: UUID,
    val message: String,
)

/**
 * Owns the single GATT session with the selected mug. Every public method and every listener
 * callback runs on the main thread; GATT and scan callbacks are re-posted there.
 */
class EmberBleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState, detail: String, selectedAddress: String?)

        /**
         * @param synced true once every readable characteristic has been read in the current
         *   session. Before that, the snapshot only holds the values read so far.
         */
        fun onSnapshotChanged(address: String, snapshot: MugSnapshot, synced: Boolean)
        fun onMugCandidates(candidates: List<MugCandidate>)
        fun onScanStateChanged(scanning: Boolean, detail: String)
        fun onWriteStatus(status: BleWriteStatus)
        fun onLog(message: String)
    }

    private sealed interface GattOperation {
        val characteristicUuid: UUID

        class Read(val characteristic: BluetoothGattCharacteristic) : GattOperation {
            override val characteristicUuid: UUID = characteristic.uuid
        }

        class Write(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val description: String,
        ) : GattOperation {
            override val characteristicUuid: UUID = characteristic.uuid
        }

        class WriteDescriptor(
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
        ) : GattOperation {
            override val characteristicUuid: UUID = descriptor.characteristic.uuid
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = manager?.adapter
    private val preferences = context.getSharedPreferences("ember_ble", Context.MODE_PRIVATE)
    private val operationQueue = ArrayDeque<GattOperation>()
    private val scanResults = linkedMapOf<String, MugCandidate>()
    private val pendingVerifications = mutableMapOf<UUID, GattOperation.Write>()
    private val pendingInitialReads = mutableSetOf<UUID>()

    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var operationInFlight: GattOperation? = null
    private var operationTimeout: Runnable? = null
    private var disconnectWatchdog: Runnable? = null
    private var snapshot = MugSnapshot()
    private var synced = false
    private var state = ConnectionState.DISCONNECTED
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var scanGeneration = 0L
    private var scanKnownAddresses: Set<String> = emptySet()
    private var selectedDevice: BluetoothDevice? = null

    /** True while the user (or the monitoring service) wants a live session with [selectedDevice]. */
    private var wantConnection = false
    private var persistentReconnect = false
    private var backgroundConnect = false
    private var failures = 0
    private var retryRunnable: Runnable? = null
    private var pairingTimeout: Runnable? = null
    private var closed = false

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun preferredAddress(): String? = preferences.getString(PREF_ADDRESS, null)

    /**
     * Keeps retrying in the background (via Android's auto-connect) after quick retries fail.
     * Enabled while the monitoring service runs or the app is on screen.
     */
    fun setPersistentReconnect(enabled: Boolean) {
        if (persistentReconnect == enabled) return
        persistentReconnect = enabled
        if (enabled && wantConnection && state == ConnectionState.DISCONNECTED && gatt == null) {
            selectedDevice?.takeIf { it.isBondedSafe() }?.let { connect(it) }
        } else if (!enabled && backgroundConnect) {
            log("Stopped waiting for the mug in the background")
            wantConnection = false
            teardown()
            changeState(ConnectionState.DISCONNECTED, "Not connected")
        }
    }

    @SuppressLint("MissingPermission")
    fun knownMugs(): List<MugCandidate> {
        if (!hasPermissions()) return emptyList()
        val remembered = rememberedAddresses()
        val preferred = preferredAddress()
        return adapter?.bondedDevices.orEmpty()
            .filter { it.isEmber() || it.address == preferred || it.address in remembered }
            .map { it.toCandidate(scanResults[it.address]?.rssi) }
            .sortedWith(compareByDescending<MugCandidate> { it.address == preferred }.thenBy { it.name })
    }

    /** Connects to the remembered mug, the only paired mug, or reports that a choice is needed. */
    @SuppressLint("MissingPermission")
    fun connectPreferred(): ConnectResult {
        if (closed) return ConnectResult.UNAVAILABLE
        if (!hasPermissions()) {
            log("Bluetooth permission is missing")
            return ConnectResult.NO_PERMISSION
        }
        if (!isBluetoothEnabled()) {
            changeState(ConnectionState.DISCONNECTED, "Bluetooth is off")
            return ConnectResult.BLUETOOTH_OFF
        }
        if (state != ConnectionState.DISCONNECTED && selectedDevice != null) return ConnectResult.STARTED
        val bonded = knownMugs()
        val preferred = bonded.firstOrNull { it.address == preferredAddress() } ?: bonded.singleOrNull()
        return when {
            preferred != null -> {
                adapter?.getRemoteDevice(preferred.address)?.let { connect(it) }
                ConnectResult.STARTED
            }
            bonded.size > 1 -> {
                emitCandidates()
                changeState(ConnectionState.DISCONNECTED, "Choose which mug to connect")
                ConnectResult.NEEDS_CHOICE
            }
            else -> {
                changeState(ConnectionState.DISCONNECTED, "No paired mug yet")
                ConnectResult.NEEDS_CHOICE
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForMugs() {
        if (!hasPermissions()) return
        stopScan(notify = false)
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            listener.onScanStateChanged(false, if (isBluetoothEnabled()) "Bluetooth scanner unavailable" else "Bluetooth is off")
            return
        }
        scanResults.clear()
        scanKnownAddresses = knownMugs().mapTo(mutableSetOf()) { it.address } + listOfNotNull(preferredAddress())
        val generation = ++scanGeneration
        emitCandidates()
        listener.onScanStateChanged(true, "Looking for nearby Ember mugs…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post { if (generation == scanGeneration) acceptScanResult(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                handler.post { if (generation == scanGeneration) results.forEach(::acceptScanResult) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (generation == scanGeneration) {
                        stopScan(notify = false)
                        listener.onScanStateChanged(false, "Scan failed ($errorCode)")
                    }
                }
            }
        }
        scanCallback = callback
        // Mugs do not reliably advertise the Ember service UUID, so filter in acceptScanResult.
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        scanTimeout = Runnable {
            if (generation != scanGeneration) return@Runnable
            val nearbyCount = scanResults.size
            stopScan(notify = false)
            emitCandidates()
            listener.onScanStateChanged(
                false,
                when (nearbyCount) {
                    0 -> "No mugs advertising nearby"
                    1 -> "1 mug nearby"
                    else -> "$nearbyCount mugs nearby"
                },
            )
        }.also { handler.postDelayed(it, SCAN_DURATION_MS) }
    }

    fun stopMugScan() = stopScan(notify = true)

    @SuppressLint("MissingPermission")
    fun selectMug(address: String) {
        if (closed || !hasPermissions()) return
        if (state == ConnectionState.CONNECTED && selectedDevice?.address == address) {
            stopScan(notify = false)
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        stopScan(notify = false)
        rememberAddress(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connect(device)
            return
        }
        teardown()
        selectedDevice = device
        wantConnection = true
        failures = 0
        changeState(ConnectionState.PAIRING, "Pairing… keep the mug pulsing blue and approve the Android prompt")
        schedulePairingTimeout(device)
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            log("Waiting for the in-progress bond with ${device.safeLabel()}")
        } else {
            log("Starting LE bond with ${device.safeLabel()}")
            val started = if (Build.VERSION.SDK_INT >= 37) {
                device.createBond(BluetoothDevice.TRANSPORT_LE)
            } else {
                device.createBond()
            }
            if (!started) {
                cancelPairingTimeout()
                wantConnection = false
                changeState(ConnectionState.DISCONNECTED, "Could not start pairing")
            }
        }
    }

    fun disconnect() {
        wantConnection = false
        stopScan(notify = false)
        teardown()
        changeState(ConnectionState.DISCONNECTED, "Disconnected")
    }

    fun refresh() {
        if (state != ConnectionState.CONNECTED) return
        enqueueReads()
    }

    fun setTargetTemperature(celsius: Float) {
        val value = runCatching { EmberProtocol.encodeTemperature(celsius) }.getOrElse {
            failWrite(EmberProtocol.TARGET_TEMPERATURE, it.message ?: "Invalid temperature")
            return
        }
        writeCharacteristic(
            EmberProtocol.TARGET_TEMPERATURE,
            value,
            if (celsius == EmberProtocol.HEATER_OFF_CELSIUS) "Heater off" else "Target %.1f °C".format(Locale.US, celsius),
        )
    }

    fun setTemperatureUnit(fahrenheit: Boolean) {
        writeCharacteristic(
            EmberProtocol.TEMPERATURE_UNIT,
            EmberProtocol.encodeTemperatureUnit(fahrenheit),
            if (fahrenheit) "Mug unit set to °F" else "Mug unit set to °C",
            reportStatus = false,
        )
    }

    fun setMugName(name: String) {
        val value = runCatching { EmberProtocol.encodeName(name) }.getOrElse {
            failWrite(EmberProtocol.NAME, it.message ?: "Invalid mug name")
            return
        }
        writeCharacteristic(EmberProtocol.NAME, value, "Mug renamed to $name")
    }

    fun setLedColor(red: Int, green: Int, blue: Int) {
        val value = runCatching { EmberProtocol.encodeLedColor(red, green, blue) }.getOrElse {
            failWrite(EmberProtocol.LED, it.message ?: "Invalid LED colour")
            return
        }
        writeCharacteristic(EmberProtocol.LED, value, "LED colour updated")
    }

    fun close() {
        if (closed) return
        disconnect()
        closed = true
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(systemReceiver) }
    }

    // region Connection lifecycle

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, isRetry: Boolean = false, background: Boolean = false) {
        if (closed || !hasPermissions()) return
        if (!isBluetoothEnabled()) {
            selectedDevice = device
            wantConnection = true
            changeState(ConnectionState.DISCONNECTED, "Bluetooth is off")
            return
        }
        stopScan(notify = false)
        teardown()
        selectedDevice = device
        wantConnection = true
        backgroundConnect = background
        rememberAddress(device.address)
        if (!isRetry) failures = 0
        snapshot = MugSnapshot()
        synced = false
        changeState(
            ConnectionState.CONNECTING,
            when {
                background -> "Waiting for ${device.safeLabel()} to wake up…"
                isRetry -> "Reconnecting…"
                else -> "Connecting to ${device.safeLabel()}…"
            },
        )
        if (!isRetry) log("Connecting to ${device.safeLabel()} (…${device.address.takeLast(5)})")
        gatt = openGatt(device, autoConnect = background)
        if (gatt == null) {
            wantConnection = false
            changeState(ConnectionState.DISCONNECTED, "Could not open a Bluetooth connection")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean): BluetoothGatt? = runCatching {
        if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutoConnectEnabled(autoConnect)
                .build()
            device.connectGatt(settings, context.mainExecutor, gattCallback)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
        }
    }.onFailure { log("connectGatt failed: ${it.message}") }.getOrNull()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post { handleConnectionState(callbackGatt, status, newState) }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            handler.post { handleServicesDiscovered(callbackGatt, status) }
        }

        @Deprecated("Used on API 31–32 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = characteristic.value?.copyOf() ?: byteArrayOf()
                handler.post { handleCharacteristicRead(callbackGatt, characteristic.uuid, value, status) }
            }
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post { handleCharacteristicRead(callbackGatt, characteristic.uuid, value, status) }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post { handleCharacteristicWrite(callbackGatt, characteristic.uuid, status) }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            handler.post {
                if (!isActive(callbackGatt) || operationInFlight !is GattOperation.WriteDescriptor) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) log("Push events unavailable ($status)")
                finishOperation()
            }
        }

        @Deprecated("Used on API 31–32 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = characteristic.value?.copyOf() ?: byteArrayOf()
                handler.post { if (isActive(callbackGatt)) handleNotification(characteristic.uuid, value) }
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post { if (isActive(callbackGatt)) handleNotification(characteristic.uuid, value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionState(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isActive(callbackGatt)) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { callbackGatt.close() }
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            backgroundConnect = false
            changeState(ConnectionState.DISCOVERING, "Loading mug controls…")
            if (!callbackGatt.discoverServices()) forceReconnect("Service discovery could not start")
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            onSessionLost(status)
        }
    }

    private fun handleServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
        if (!isActive(callbackGatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            forceReconnect("Service discovery failed ($status)")
            return
        }
        val discovered = callbackGatt.getService(EmberProtocol.SERVICE)
        if (discovered == null) {
            log("This device does not expose the Ember service")
            disconnect()
            return
        }
        service = discovered
        failures = 0
        changeState(ConnectionState.CONNECTED, "Connected")
        log("Connected to ${selectedDevice?.safeLabel()}")
        enablePushEvents()
        pendingInitialReads.clear()
        pendingInitialReads += EmberProtocol.readableCharacteristics.filter { discovered.getCharacteristic(it) != null }
        enqueueReads()
        if (pendingInitialReads.isEmpty()) markSynced()
    }

    /** Called when the active GATT session ended, whether reported by Android or by the watchdog. */
    @SuppressLint("MissingPermission")
    private fun onSessionLost(status: Int) {
        val current = gatt
        gatt = null
        service = null
        clearOperations("Connection lost before the change was confirmed")
        cancelDisconnectWatchdog()
        runCatching { current?.close() }
        val device = selectedDevice
        if (!wantConnection || device == null || !device.isBondedSafe()) {
            changeState(ConnectionState.DISCONNECTED, if (wantConnection) "Connection lost ($status)" else "Disconnected")
            wantConnection = false
            return
        }
        if (!isBluetoothEnabled()) {
            changeState(ConnectionState.DISCONNECTED, "Bluetooth is off")
            return
        }
        failures += 1
        when (val action = ReconnectPolicy.next(failures, persistentReconnect)) {
            is ReconnectAction.Direct -> {
                if (failures == 1) log("Connection dropped (status $status); retrying")
                changeState(ConnectionState.CONNECTING, "Reconnecting…")
                scheduleRetry(action.delayMs) { connect(device, isRetry = true) }
            }
            is ReconnectAction.Background -> {
                if (failures == ReconnectPolicy.MAX_DIRECT_ATTEMPTS + 1) {
                    log("Mug is out of reach; will reconnect when it wakes up")
                }
                changeState(ConnectionState.CONNECTING, "Waiting for ${device.safeLabel()} to wake up…")
                scheduleRetry(action.delayMs) { connect(device, isRetry = true, background = true) }
            }
            ReconnectAction.GiveUp -> {
                wantConnection = false
                log("Could not reach the mug (status $status)")
                changeState(ConnectionState.DISCONNECTED, "Mug not reachable. Make sure it is nearby and awake")
            }
        }
    }

    /** Asks Android to drop the link; if it never reports back, treat the session as lost anyway. */
    @SuppressLint("MissingPermission")
    private fun forceReconnect(reason: String) {
        log(reason)
        val current = gatt ?: return
        runCatching { current.disconnect() }
        cancelDisconnectWatchdog()
        disconnectWatchdog = Runnable {
            disconnectWatchdog = null
            if (gatt === current) onSessionLost(BluetoothGatt.GATT_FAILURE)
        }.also { handler.postDelayed(it, DISCONNECT_WATCHDOG_MS) }
    }

    /** Closes the current GATT session and cancels pending work without changing [state]. */
    @SuppressLint("MissingPermission")
    private fun teardown() {
        cancelPairingTimeout()
        cancelRetry()
        cancelDisconnectWatchdog()
        backgroundConnect = false
        val current = gatt
        gatt = null
        service = null
        clearOperations("Connection closed before the change was confirmed")
        runCatching {
            current?.disconnect()
            current?.close()
        }
    }

    private fun scheduleRetry(delayMs: Long, action: () -> Unit) {
        cancelRetry()
        retryRunnable = Runnable {
            retryRunnable = null
            if (!closed && wantConnection) action()
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelRetry() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    private fun cancelDisconnectWatchdog() {
        disconnectWatchdog?.let(handler::removeCallbacks)
        disconnectWatchdog = null
    }

    private fun schedulePairingTimeout(device: BluetoothDevice) {
        cancelPairingTimeout()
        pairingTimeout = Runnable {
            pairingTimeout = null
            if (closed || state != ConnectionState.PAIRING || selectedDevice?.address != device.address) return@Runnable
            if (device.isBondedSafe()) {
                connect(device)
            } else {
                wantConnection = false
                log("Pairing timed out")
                changeState(
                    ConnectionState.DISCONNECTED,
                    "Pairing timed out. Hold the mug's button until it pulses blue, then try again",
                )
            }
        }.also { handler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun cancelPairingTimeout() {
        pairingTimeout?.let(handler::removeCallbacks)
        pairingTimeout = null
    }

    // endregion

    // region GATT operations

    private fun handleCharacteristicRead(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        val operation = operationInFlight as? GattOperation.Read
        if (!isActive(callbackGatt) || operation?.characteristicUuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            updateSnapshot(uuid, value)
            pendingVerifications.remove(uuid)?.let { pending ->
                if (valuesEquivalent(uuid, pending.value, value)) {
                    listener.onWriteStatus(BleWriteStatus(BleWritePhase.CONFIRMED, uuid, pending.description))
                } else {
                    val message = "The mug did not accept the change"
                    log("$message (${shortUuid(uuid)})")
                    listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, message))
                }
            }
        } else {
            log("Read failed: ${shortUuid(uuid)} ($status)")
            pendingVerifications.remove(uuid)?.let {
                listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, "Could not confirm the change"))
            }
        }
        if (pendingInitialReads.remove(uuid) && pendingInitialReads.isEmpty()) markSynced()
        finishOperation()
    }

    private fun handleCharacteristicWrite(callbackGatt: BluetoothGatt, uuid: UUID, status: Int) {
        val operation = operationInFlight as? GattOperation.Write
        if (!isActive(callbackGatt) || operation?.characteristicUuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pendingVerifications[uuid] = operation
            enqueueRead(uuid, force = true)
        } else {
            val message = "The mug rejected the change ($status)"
            log(message)
            listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, message))
        }
        finishOperation()
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid != EmberProtocol.PUSH_EVENT || value.isEmpty()) return
        when (value[0].toInt() and 0xff) {
            PushEvent.BATTERY_CHANGED, PushEvent.CHARGING, PushEvent.NOT_CHARGING -> enqueueRead(EmberProtocol.BATTERY)
            PushEvent.TARGET_TEMPERATURE_CHANGED -> enqueueRead(EmberProtocol.TARGET_TEMPERATURE)
            PushEvent.CURRENT_TEMPERATURE_CHANGED -> enqueueRead(EmberProtocol.CURRENT_TEMPERATURE)
            PushEvent.LIQUID_LEVEL_CHANGED -> enqueueRead(EmberProtocol.LIQUID_LEVEL)
            PushEvent.LIQUID_STATE_CHANGED -> enqueueRead(EmberProtocol.LIQUID_STATE)
            PushEvent.AUTH_INFO_MISSING -> log("Mug reported missing setup authorization")
        }
    }

    private fun updateSnapshot(uuid: UUID, value: ByteArray) {
        val previous = snapshot
        snapshot = when (uuid) {
            EmberProtocol.NAME -> snapshot.copy(name = EmberProtocol.decodeName(value))
            EmberProtocol.CURRENT_TEMPERATURE -> snapshot.copy(currentTemperatureC = EmberProtocol.decodeTemperature(value))
            EmberProtocol.TARGET_TEMPERATURE -> snapshot.copy(targetTemperatureC = EmberProtocol.decodeTemperature(value))
            EmberProtocol.TEMPERATURE_UNIT -> snapshot.copy(temperatureUnit = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.LIQUID_LEVEL -> snapshot.copy(liquidLevel = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.BATTERY -> EmberProtocol.decodeBattery(value)?.let {
                snapshot.copy(batteryPercent = it.percent, charging = it.charging ?: snapshot.charging)
            } ?: snapshot
            EmberProtocol.LIQUID_STATE -> snapshot.copy(liquidState = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.FIRMWARE -> EmberProtocol.decodeFirmware(value)?.let { info ->
                snapshot.copy(
                    firmwareVersion = info.firmware,
                    hardwareVersion = info.hardware,
                    bootloaderVersion = info.bootloader,
                )
            } ?: snapshot
            EmberProtocol.UDSK -> snapshot.copy(setupKeyPresent = value.any { it.toInt() != 0 })
            EmberProtocol.LED -> snapshot.copy(ledColor = EmberProtocol.decodeLedColor(value))
            else -> snapshot
        }
        if (snapshot != previous) emitSnapshot()
    }

    private fun markSynced() {
        if (synced) return
        synced = true
        emitSnapshot()
    }

    private fun emitSnapshot() {
        selectedDevice?.address?.let { listener.onSnapshotChanged(it, snapshot, synced) }
    }

    @SuppressLint("MissingPermission")
    private fun enablePushEvents() {
        val characteristic = service?.getCharacteristic(EmberProtocol.PUSH_EVENT) ?: return
        val descriptor = characteristic.getDescriptor(EmberProtocol.CCCD) ?: return
        if (gatt?.setCharacteristicNotification(characteristic, true) == true) {
            enqueue(GattOperation.WriteDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
        }
    }

    private fun enqueueReads() {
        EmberProtocol.readableCharacteristics.forEach { enqueueRead(it) }
    }

    /**
     * Queues a read unless an identical one is already waiting. [force] is used for write
     * verification, which must observe the value after the write rather than a read queued
     * before it.
     */
    private fun enqueueRead(uuid: UUID, force: Boolean = false) {
        val characteristic = service?.getCharacteristic(uuid) ?: return
        if (!force && operationQueue.any { it is GattOperation.Read && it.characteristicUuid == uuid }) return
        enqueue(GattOperation.Read(characteristic))
    }

    private fun writeCharacteristic(uuid: UUID, value: ByteArray, description: String, reportStatus: Boolean = true) {
        val characteristic = service?.getCharacteristic(uuid)
        if (state != ConnectionState.CONNECTED || characteristic == null) {
            if (reportStatus) failWrite(uuid, "The mug isn't connected")
            return
        }
        // A newer value for the same characteristic supersedes one that has not been sent yet.
        operationQueue.removeIf { it is GattOperation.Write && it.characteristicUuid == uuid }
        if (reportStatus) listener.onWriteStatus(BleWriteStatus(BleWritePhase.WRITING, uuid, description))
        enqueue(GattOperation.Write(characteristic, value, description))
    }

    private fun failWrite(uuid: UUID, message: String) {
        log(message)
        listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, message))
    }

    private fun enqueue(operation: GattOperation) {
        operationQueue.add(operation)
        startNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun startNextOperation() {
        if (operationInFlight != null) return
        val currentGatt = gatt ?: return
        val operation = operationQueue.pollFirst() ?: return
        operationInFlight = operation
        val started = runCatching {
            when (operation) {
                is GattOperation.Read -> currentGatt.readCharacteristic(operation.characteristic)
                is GattOperation.Write -> if (Build.VERSION.SDK_INT >= 33) {
                    currentGatt.writeCharacteristic(
                        operation.characteristic,
                        operation.value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    operation.characteristic.value = operation.value
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(operation.characteristic)
                }
                is GattOperation.WriteDescriptor -> if (Build.VERSION.SDK_INT >= 33) {
                    currentGatt.writeDescriptor(operation.descriptor, operation.value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.descriptor.value = operation.value
                    @Suppress("DEPRECATION")
                    currentGatt.writeDescriptor(operation.descriptor)
                }
            }
        }.getOrDefault(false)
        if (!started) {
            operationInFlight = null
            if (operation is GattOperation.Write) failWrite(operation.characteristicUuid, "Could not send the change")
            if (operation is GattOperation.Read) {
                pendingVerifications.remove(operation.characteristicUuid)?.let {
                    failWrite(operation.characteristicUuid, "Could not confirm the change")
                }
                if (pendingInitialReads.remove(operation.characteristicUuid) && pendingInitialReads.isEmpty()) markSynced()
            }
            log("Could not start GATT operation: ${shortUuid(operation.characteristicUuid)}")
            handler.post(::startNextOperation)
            return
        }
        operationTimeout = Runnable {
            operationTimeout = null
            if (operationInFlight !== operation || gatt !== currentGatt) return@Runnable
            forceReconnect("Mug stopped responding (${shortUuid(operation.characteristicUuid)})")
        }.also { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }
    }

    private fun finishOperation() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        operationInFlight = null
        startNextOperation()
    }

    /** Drops queued work; any write that was not yet confirmed is reported as failed. */
    private fun clearOperations(failureMessage: String) {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        val unconfirmed = buildList {
            (operationInFlight as? GattOperation.Write)?.let(::add)
            operationQueue.filterIsInstance<GattOperation.Write>().forEach(::add)
            addAll(pendingVerifications.values)
        }
        operationQueue.clear()
        operationInFlight = null
        pendingVerifications.clear()
        pendingInitialReads.clear()
        unconfirmed.distinctBy { it.characteristicUuid }.forEach {
            listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, it.characteristicUuid, failureMessage))
        }
    }

    // endregion

    // region Scanning

    @SuppressLint("MissingPermission")
    private fun acceptScanResult(result: ScanResult) {
        val device = result.device
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { device.name }.getOrNull() ?: scanResults[device.address]?.name
        val known = device.address in scanKnownAddresses
        val hasEmberService = record?.serviceUuids?.contains(ParcelUuid(EmberProtocol.SERVICE)) == true
        val hasEmberManufacturer = record?.manufacturerSpecificData?.indexOfKey(EMBER_MANUFACTURER_ID)?.let { it >= 0 } == true
        if (!known && name?.contains("Ember", ignoreCase = true) != true && !hasEmberService && !hasEmberManufacturer) return
        val candidate = device.toCandidate(result.rssi, name ?: "Ember mug")
        // RSSI jitters constantly; only re-emit when something visible changes.
        val previous = scanResults[device.address]
        scanResults[device.address] = candidate
        if (previous == null || previous.name != candidate.name || previous.bonded != candidate.bonded ||
            kotlin.math.abs((previous.rssi ?: 0) - (candidate.rssi ?: 0)) >= RSSI_EMIT_THRESHOLD
        ) {
            emitCandidates()
        }
    }

    private fun emitCandidates() {
        if (!hasPermissions()) return
        val combined = linkedMapOf<String, MugCandidate>()
        knownMugs().forEach { combined[it.address] = it }
        scanResults.values.forEach { combined[it.address] = it }
        listener.onMugCandidates(combined.values.toList())
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(notify: Boolean) {
        scanGeneration += 1
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        if (notify) listener.onScanStateChanged(false, "Scan stopped")
    }

    // endregion

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> onAdapterStateChanged(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR),
                )
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    onBondStateChanged(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
                }
            }
        }
    }

    // Declared after systemReceiver: initializers run in source order.
    init {
        ContextCompat.registerReceiver(
            context,
            systemReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            // Bluetooth broadcasts can originate from the Bluetooth stack app rather than the
            // system UID, so the receiver must be exported to get them. Both actions are
            // protected broadcasts that ordinary apps cannot send.
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun onAdapterStateChanged(adapterState: Int) {
        if (closed) return
        when (adapterState) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                stopScan(notify = false)
                if (gatt != null || retryRunnable != null || state != ConnectionState.DISCONNECTED) {
                    teardown()
                    changeState(ConnectionState.DISCONNECTED, "Bluetooth is off")
                }
            }
            BluetoothAdapter.STATE_ON -> {
                val device = selectedDevice
                if (wantConnection && device != null && device.isBondedSafe()) {
                    log("Bluetooth is back on; reconnecting")
                    connect(device)
                }
            }
        }
    }

    private fun onBondStateChanged(device: BluetoothDevice, bondState: Int) {
        if (closed || device.address != selectedDevice?.address || state != ConnectionState.PAIRING) return
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                cancelPairingTimeout()
                scanResults[device.address]?.let { scanResults[device.address] = it.copy(bonded = true) }
                emitCandidates()
                log("Paired with ${device.safeLabel()}")
                connect(device)
            }
            BluetoothDevice.BOND_NONE -> {
                cancelPairingTimeout()
                wantConnection = false
                emitCandidates()
                log("Pairing was not completed")
                changeState(
                    ConnectionState.DISCONNECTED,
                    "Pairing failed. Hold the mug's button until it pulses blue, then try again",
                )
            }
        }
    }

    private fun isActive(callbackGatt: BluetoothGatt): Boolean = !closed && callbackGatt === gatt

    private fun changeState(newState: ConnectionState, detail: String) {
        state = newState
        listener.onConnectionStateChanged(newState, detail, selectedDevice?.address)
    }

    private fun log(message: String) = listener.onLog(message)

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isEmber(): Boolean =
        runCatching { name }.getOrNull()?.contains("Ember", ignoreCase = true) == true

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isBondedSafe(): Boolean =
        runCatching { bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeLabel(): String = runCatching { name }.getOrNull() ?: "your mug"

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toCandidate(rssi: Int?, overrideName: String? = null): MugCandidate = MugCandidate(
        address = address,
        name = overrideName ?: runCatching { name }.getOrNull() ?: "Ember mug",
        rssi = rssi,
        bonded = isBondedSafe(),
    )

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)

    private fun rememberedAddresses(): Set<String> =
        preferences.getStringSet(PREF_KNOWN_ADDRESSES, emptySet()).orEmpty()

    private fun rememberAddress(address: String) {
        val addresses = rememberedAddresses() + address
        preferences.edit {
            putString(PREF_ADDRESS, address)
            putStringSet(PREF_KNOWN_ADDRESSES, addresses)
        }
    }

    private fun valuesEquivalent(uuid: UUID, expected: ByteArray, actual: ByteArray): Boolean = when (uuid) {
        EmberProtocol.NAME -> EmberProtocol.decodeName(actual) == expected.toString(Charsets.US_ASCII)
        // Only RGB matters; some firmware reports a different alpha byte than was written.
        EmberProtocol.LED -> EmberProtocol.decodeLedColor(actual) == EmberProtocol.decodeLedColor(expected)
        else -> actual.size >= expected.size && actual.copyOf(expected.size).contentEquals(expected)
    }

    enum class ConnectResult { STARTED, NEEDS_CHOICE, BLUETOOTH_OFF, NO_PERMISSION, UNAVAILABLE }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        private const val PREF_ADDRESS = "preferred_mug_address"
        private const val PREF_KNOWN_ADDRESSES = "known_mug_addresses"
        private const val EMBER_MANUFACTURER_ID = 0x03C1
        private const val SCAN_DURATION_MS = 10_000L
        private const val OPERATION_TIMEOUT_MS = 8_000L
        private const val DISCONNECT_WATCHDOG_MS = 4_000L
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val RSSI_EMIT_THRESHOLD = 4
    }
}
