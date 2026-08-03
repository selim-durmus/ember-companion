package dev.nicky.ember.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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

class EmberBleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState, detail: String, selectedAddress: String?)
        fun onSnapshotChanged(address: String, snapshot: MugSnapshot)
        fun onMugCandidates(candidates: List<MugCandidate>)
        fun onScanStateChanged(scanning: Boolean, detail: String)
        fun onWriteStatus(status: BleWriteStatus)
        fun onLog(message: String)
    }

    private sealed interface GattOperation {
        val characteristicUuid: UUID

        data class Read(val characteristic: BluetoothGattCharacteristic) : GattOperation {
            override val characteristicUuid: UUID = characteristic.uuid
        }

        data class Write(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val description: String,
        ) : GattOperation {
            override val characteristicUuid: UUID = characteristic.uuid
        }

        data class WriteDescriptor(
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

    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var operationInFlight: GattOperation? = null
    private var operationTimeout: Runnable? = null
    private var snapshot = MugSnapshot()
    private var state = ConnectionState.DISCONNECTED
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var scanGeneration = 0L
    private var selectedDevice: BluetoothDevice? = null
    private var receiverRegistered = false
    private var manualDisconnect = false
    private var retryCount = 0
    private var retryRunnable: Runnable? = null
    private var sessionGeneration = 0L
    private var closed = false

    init {
        registerBondReceiver()
    }

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun preferredAddress(): String? = preferences.getString(PREF_ADDRESS, null)

    fun currentAddress(): String? = selectedDevice?.address

    @SuppressLint("MissingPermission")
    fun knownMugs(): List<MugCandidate> {
        if (!hasPermissions()) return emptyList()
        val rememberedAddresses = preferences.getStringSet(PREF_KNOWN_ADDRESSES, emptySet()).orEmpty()
        return adapter?.bondedDevices.orEmpty()
            .filter { it.isEmber() || it.address == preferredAddress() || it.address in rememberedAddresses }
            .map { it.toCandidate(scanResults[it.address]?.rssi) }
            .sortedWith(compareByDescending<MugCandidate> { it.address == preferredAddress() }.thenBy { it.name })
    }

    @SuppressLint("MissingPermission")
    fun connectPreferred() {
        if (!hasPermissions()) {
            log("Bluetooth permission is missing")
            return
        }
        if (adapter?.isEnabled != true) {
            changeState(ConnectionState.DISCONNECTED, "Bluetooth is turned off")
            return
        }
        if (state != ConnectionState.DISCONNECTED && selectedDevice != null) {
            log("Connection already active for ${selectedDevice?.safeLabel()}")
            return
        }
        val rememberedAddresses = preferences.getStringSet(PREF_KNOWN_ADDRESSES, emptySet()).orEmpty()
        val bonded = adapter?.bondedDevices.orEmpty().filter {
            it.isEmber() || it.address == preferredAddress() || it.address in rememberedAddresses
        }
        val savedAddress = preferredAddress()
        val preferred = bonded.firstOrNull { it.address == savedAddress } ?: bonded.singleOrNull()
        when {
            preferred != null -> connect(preferred)
            bonded.size > 1 -> {
                emitCandidates()
                changeState(ConnectionState.DISCONNECTED, "Choose a paired mug")
            }
            else -> {
                changeState(ConnectionState.DISCONNECTED, "No paired mug selected")
                scanForMugs()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForMugs() {
        if (!hasPermissions()) return
        stopScan(notify = false)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener.onScanStateChanged(false, "Bluetooth scanner unavailable")
            return
        }
        scanResults.clear()
        val generation = ++scanGeneration
        emitCandidates()
        listener.onScanStateChanged(true, "Looking for additional Ember mugs…")
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
                if (nearbyCount == 0) "No new mugs advertising; paired mugs are still available"
                else "$nearbyCount nearby Ember mug${if (nearbyCount == 1) "" else "s"} found",
            )
        }.also { handler.postDelayed(it, SCAN_DURATION_MS) }
    }

    fun stopMugScan() {
        stopScan(notify = true)
    }

    @SuppressLint("MissingPermission")
    fun selectMug(address: String) {
        if (!hasPermissions()) return
        if (state == ConnectionState.CONNECTED && selectedDevice?.address == address) {
            stopScan(notify = false)
            log("Already connected to ${selectedDevice?.safeLabel()}")
            return
        }
        val device = adapter?.getRemoteDevice(address) ?: return
        stopScan(notify = false)
        selectedDevice = device
        rememberAddress(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connect(device)
        } else {
            beginIntentionalTransition()
            closeCurrentGatt()
            selectedDevice = device
            changeState(ConnectionState.PAIRING, "Approve Android's pairing request")
            log("Starting LE bond with ${device.safeLabel()}")
            if (!device.createBond()) {
                changeState(ConnectionState.DISCONNECTED, "Could not start pairing")
            }
        }
    }

    fun disconnect() {
        beginIntentionalTransition()
        stopScan(notify = false)
        closeCurrentGatt()
        retryCount = 0
        changeState(ConnectionState.DISCONNECTED, "Disconnected")
    }

    fun cancelConnection() = disconnect()

    fun refresh() {
        if (state != ConnectionState.CONNECTED) {
            log("Refresh ignored: mug is not connected")
            return
        }
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
            if (celsius == 0f) "Heater off" else "Target set to %.1f °C".format(Locale.US, celsius),
        )
    }

    fun setTemperatureUnit(fahrenheit: Boolean) {
        writeCharacteristic(
            EmberProtocol.TEMPERATURE_UNIT,
            EmberProtocol.encodeTemperatureUnit(fahrenheit),
            if (fahrenheit) "Mug unit set to Fahrenheit" else "Mug unit set to Celsius",
        )
    }

    fun setMugName(name: String) {
        val value = runCatching { EmberProtocol.encodeName(name) }.getOrElse {
            failWrite(EmberProtocol.NAME, it.message ?: "Invalid mug name")
            return
        }
        writeCharacteristic(EmberProtocol.NAME, value, "Mug name changed to $name")
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
        closed = true
        disconnect()
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            context.unregisterReceiver(bondReceiver)
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, resetRetryCount: Boolean = true) {
        if (closed || !hasPermissions()) return
        stopScan(notify = false)
        cancelRetry()
        sessionGeneration += 1
        manualDisconnect = false
        closeCurrentGatt()
        selectedDevice = device
        rememberAddress(device.address)
        if (resetRetryCount) retryCount = 0
        snapshot = MugSnapshot()
        listener.onSnapshotChanged(device.address, snapshot)
        changeState(ConnectionState.CONNECTING, "Connecting to ${device.safeLabel()}…")
        log("Connecting to ${device.safeLabel()} (${device.address.takeLast(5)})")
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
        )
        if (gatt == null) {
            changeState(ConnectionState.DISCONNECTED, "Could not create Bluetooth connection")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post { handleConnectionState(callbackGatt, status, newState) }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            handler.post {
                if (!isActive(callbackGatt)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed ($status)")
                    retryOrDisconnect()
                    return@post
                }
                val discovered = callbackGatt.getService(EmberProtocol.SERVICE)
                if (discovered == null) {
                    log("Connected device does not expose the Ember service")
                    disconnect()
                    return@post
                }
                service = discovered
                retryCount = 0
                changeState(ConnectionState.CONNECTED, "Connected · bonded")
                log("Discovered Ember GATT service with ${discovered.characteristics.size} characteristics")
                enablePushEvents()
                enqueueReads()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = characteristic.value ?: byteArrayOf()
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
                log(if (status == BluetoothGatt.GATT_SUCCESS) "Push events enabled" else "Push events unavailable ($status)")
                finishOperation()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = characteristic.value ?: byteArrayOf()
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
            if (newState == BluetoothProfile.STATE_DISCONNECTED) callbackGatt.close()
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            changeState(ConnectionState.DISCOVERING, "Discovering mug controls…")
            log("GATT connected; discovering services")
            if (!callbackGatt.discoverServices()) retryOrDisconnect()
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            service = null
            clearOperations()
            gatt = null
            callbackGatt.close()
            if (!manualDisconnect && retryCount < MAX_RETRIES && selectedDevice?.bondState == BluetoothDevice.BOND_BONDED) {
                scheduleRetry(status)
            } else {
                changeState(
                    ConnectionState.DISCONNECTED,
                    if (manualDisconnect) "Disconnected" else "Connection lost ($status)",
                )
            }
        }
    }

    private fun handleCharacteristicRead(
        callbackGatt: BluetoothGatt,
        uuid: UUID,
        value: ByteArray,
        status: Int,
    ) {
        val operation = operationInFlight as? GattOperation.Read
        if (!isActive(callbackGatt) || operation?.characteristicUuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            updateSnapshot(uuid, value)
            pendingVerifications.remove(uuid)?.let { pending ->
                if (valuesEquivalent(uuid, pending.value, value)) {
                    listener.onWriteStatus(BleWriteStatus(BleWritePhase.CONFIRMED, uuid, pending.description))
                } else {
                    val message = "Write verification failed: ${shortUuid(uuid)}"
                    log(message)
                    listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, message))
                }
            }
        } else {
            log("Read failed: ${shortUuid(uuid)} ($status)")
            pendingVerifications.remove(uuid)?.let {
                listener.onWriteStatus(
                    BleWriteStatus(BleWritePhase.FAILED, uuid, "Could not verify ${shortUuid(uuid)}"),
                )
            }
        }
        finishOperation()
    }

    private fun handleCharacteristicWrite(callbackGatt: BluetoothGatt, uuid: UUID, status: Int) {
        val operation = operationInFlight as? GattOperation.Write
        if (!isActive(callbackGatt) || operation?.characteristicUuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            log("Write accepted; verifying: ${shortUuid(uuid)}")
            pendingVerifications[uuid] = operation
            enqueueRead(uuid)
        } else {
            val message = "Write failed: ${shortUuid(uuid)} ($status)"
            log(message)
            pendingVerifications.remove(uuid)
            listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, uuid, message))
        }
        finishOperation()
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid != EmberProtocol.PUSH_EVENT || value.isEmpty()) return
        when (value[0].toInt() and 0xff) {
            1, 2, 3 -> enqueueRead(EmberProtocol.BATTERY)
            4 -> enqueueRead(EmberProtocol.TARGET_TEMPERATURE)
            5 -> enqueueRead(EmberProtocol.CURRENT_TEMPERATURE)
            7 -> enqueueRead(EmberProtocol.LIQUID_LEVEL)
            8 -> enqueueRead(EmberProtocol.LIQUID_STATE)
            6 -> log("Mug reported missing setup authorization")
        }
    }

    private fun updateSnapshot(uuid: UUID, value: ByteArray) {
        snapshot = when (uuid) {
            EmberProtocol.NAME -> snapshot.copy(name = value.toString(Charsets.UTF_8).trimEnd('\u0000'))
            EmberProtocol.CURRENT_TEMPERATURE -> snapshot.copy(currentTemperatureC = EmberProtocol.decodeTemperature(value))
            EmberProtocol.TARGET_TEMPERATURE -> snapshot.copy(targetTemperatureC = EmberProtocol.decodeTemperature(value))
            EmberProtocol.TEMPERATURE_UNIT -> snapshot.copy(temperatureUnit = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.LIQUID_LEVEL -> snapshot.copy(liquidLevel = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.BATTERY -> snapshot.copy(
                batteryPercent = value.getOrNull(0)?.toInt()?.and(0xff),
                charging = value.getOrNull(1)?.toInt() == 1,
            )
            EmberProtocol.LIQUID_STATE -> snapshot.copy(liquidState = value.firstOrNull()?.toInt()?.and(0xff))
            EmberProtocol.FIRMWARE -> snapshot.copy(
                firmwareVersion = littleEndianU16(value, 0),
                hardwareVersion = littleEndianU16(value, 2),
            )
            EmberProtocol.UDSK -> snapshot.copy(setupKeyPresent = value.any { it.toInt() != 0 })
            EmberProtocol.LED -> snapshot.copy(ledRgba = value.take(4).map { it.toInt() and 0xff }.toIntArray())
            else -> snapshot
        }
        selectedDevice?.address?.let { listener.onSnapshotChanged(it, snapshot) }
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
        EmberProtocol.readableCharacteristics.forEach(::enqueueRead)
    }

    private fun enqueueRead(uuid: UUID) {
        val characteristic = service?.getCharacteristic(uuid) ?: return
        if (operationInFlight is GattOperation.Read && operationInFlight?.characteristicUuid == uuid) return
        if (operationQueue.any { it is GattOperation.Read && it.characteristicUuid == uuid }) return
        enqueue(GattOperation.Read(characteristic))
    }

    private fun writeCharacteristic(uuid: UUID, value: ByteArray, description: String) {
        val characteristic = service?.getCharacteristic(uuid)
        if (state != ConnectionState.CONNECTED || characteristic == null) {
            failWrite(uuid, "Mug is not ready for this change")
            return
        }
        operationQueue.removeIf { it is GattOperation.Write && it.characteristicUuid == uuid }
        listener.onWriteStatus(BleWriteStatus(BleWritePhase.WRITING, uuid, "Applying: $description"))
        enqueue(GattOperation.Write(characteristic, value, description))
        log(description)
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
        val started = when (operation) {
            is GattOperation.Read -> currentGatt.readCharacteristic(operation.characteristic)
            is GattOperation.Write -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    currentGatt.writeCharacteristic(
                        operation.characteristic,
                        operation.value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.characteristic.value = operation.value
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(operation.characteristic)
                }
            }
            is GattOperation.WriteDescriptor -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    currentGatt.writeDescriptor(operation.descriptor, operation.value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.descriptor.value = operation.value
                    @Suppress("DEPRECATION")
                    currentGatt.writeDescriptor(operation.descriptor)
                }
            }
        }
        if (!started) {
            if (operation is GattOperation.Write) failWrite(operation.characteristicUuid, "Could not start GATT write")
            log("Could not start GATT operation: ${shortUuid(operation.characteristicUuid)}")
            operationInFlight = null
            handler.post(::startNextOperation)
            return
        }
        operationTimeout = Runnable {
            if (operationInFlight !== operation || gatt !== currentGatt) return@Runnable
            val message = "GATT operation timed out: ${shortUuid(operation.characteristicUuid)}"
            log(message)
            if (operation is GattOperation.Write) {
                pendingVerifications.remove(operation.characteristicUuid)
                listener.onWriteStatus(BleWriteStatus(BleWritePhase.FAILED, operation.characteristicUuid, message))
            }
            retryOrDisconnect()
        }.also { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }
    }

    private fun finishOperation() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        operationInFlight = null
        startNextOperation()
    }

    private fun clearOperations() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        operationQueue.clear()
        operationInFlight = null
        pendingVerifications.clear()
    }

    @SuppressLint("MissingPermission")
    private fun acceptScanResult(result: ScanResult) {
        val device = result.device
        val cached = scanResults[device.address]
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { device.name }.getOrNull() ?: cached?.name
        val knownAddress = knownMugs().any { it.address == device.address } || device.address == preferredAddress()
        val hasEmberService = record?.serviceUuids?.contains(ParcelUuid(EmberProtocol.SERVICE)) == true
        val hasEmberManufacturer = record?.manufacturerSpecificData
            ?.indexOfKey(EMBER_MANUFACTURER_ID)
            ?.let { it >= 0 } == true
        if (!knownAddress && name?.contains("Ember", ignoreCase = true) != true && !hasEmberService && !hasEmberManufacturer) return
        scanResults[device.address] = device.toCandidate(result.rssi, name ?: "Ember mug")
        emitCandidates()
    }

    @SuppressLint("MissingPermission")
    private fun emitCandidates() {
        if (!hasPermissions()) return
        val combined = linkedMapOf<String, MugCandidate>()
        knownMugs().forEach { combined[it.address] = it }
        scanResults.values.forEach { combined[it.address] = it }
        listener.onMugCandidates(
            combined.values.sortedWith(
                compareByDescending<MugCandidate> { it.address == selectedDevice?.address }
                    .thenByDescending { it.bonded }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE },
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(notify: Boolean) {
        scanGeneration += 1
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        scanCallback?.let { callback -> adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanCallback = null
        if (notify) listener.onScanStateChanged(false, "Scan stopped")
    }

    private fun scheduleRetry(status: Int) {
        retryCount += 1
        val generation = ++sessionGeneration
        val device = selectedDevice ?: return
        val delay = RETRY_DELAY_MS * retryCount
        log("Disconnected (status $status); retry $retryCount/$MAX_RETRIES")
        changeState(ConnectionState.CONNECTING, "Reconnecting…")
        retryRunnable = Runnable {
            retryRunnable = null
            if (closed || manualDisconnect || generation != sessionGeneration) return@Runnable
            connect(device, resetRetryCount = false)
        }.also { handler.postDelayed(it, delay) }
    }

    private fun cancelRetry() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    private fun beginIntentionalTransition() {
        manualDisconnect = true
        sessionGeneration += 1
        cancelRetry()
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val current = gatt
        gatt = null
        service = null
        clearOperations()
        current?.disconnect()
        current?.close()
    }

    @SuppressLint("MissingPermission")
    private fun retryOrDisconnect() {
        gatt?.disconnect()
    }

    private fun isActive(callbackGatt: BluetoothGatt): Boolean = !closed && callbackGatt === gatt

    private fun changeState(newState: ConnectionState, detail: String) {
        state = newState
        listener.onConnectionStateChanged(newState, detail, selectedDevice?.address)
    }

    private fun log(message: String) {
        listener.onLog(message)
    }

    private fun registerBondReceiver() {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != selectedDevice?.address) return
            if (state != ConnectionState.PAIRING || manualDisconnect) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    log("Bluetooth bond established")
                    connect(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    changeState(ConnectionState.DISCONNECTED, "Pairing was cancelled or failed")
                    log("Bluetooth bond was not established")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isEmber(): Boolean = name?.contains("Ember", ignoreCase = true) == true

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeLabel(): String = name ?: "Ember mug"

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toCandidate(rssi: Int?, overrideName: String? = null): MugCandidate = MugCandidate(
        address = address,
        name = overrideName ?: name ?: "Ember mug",
        rssi = rssi,
        bonded = bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)

    private fun littleEndianU16(value: ByteArray, offset: Int): Int? {
        if (value.size < offset + 2) return null
        return (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun rememberAddress(address: String) {
        val addresses = preferences.getStringSet(PREF_KNOWN_ADDRESSES, emptySet()).orEmpty().toMutableSet()
        addresses.add(address)
        preferences.edit {
            putString(PREF_ADDRESS, address)
            putStringSet(PREF_KNOWN_ADDRESSES, addresses)
        }
    }

    private fun valuesEquivalent(uuid: UUID, expected: ByteArray, actual: ByteArray): Boolean {
        return if (uuid == EmberProtocol.NAME) {
            actual.toString(Charsets.UTF_8).trimEnd('\u0000') == expected.toString(Charsets.US_ASCII)
        } else {
            actual.take(expected.size).toByteArray().contentEquals(expected)
        }
    }

    private companion object {
        const val PREF_ADDRESS = "preferred_mug_address"
        const val PREF_KNOWN_ADDRESSES = "known_mug_addresses"
        const val EMBER_MANUFACTURER_ID = 0x03C1
        const val SCAN_DURATION_MS = 8_000L
        const val RETRY_DELAY_MS = 1_200L
        const val OPERATION_TIMEOUT_MS = 8_000L
        const val MAX_RETRIES = 3
    }
}
