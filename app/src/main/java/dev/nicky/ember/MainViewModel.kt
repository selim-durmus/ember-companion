package dev.nicky.ember

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nicky.ember.ble.EmberBleClient
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.WritePhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as EmberApplication).repository

    val state: StateFlow<EmberAppState> = repository.state

    /**
     * Target the user is choosing but the mug has not confirmed yet, in °C. While set, the UI
     * shows it instead of the mug's value so live updates never yank the control back.
     */
    private val _draftTarget = MutableStateFlow<Float?>(null)
    val draftTarget: StateFlow<Float?> = _draftTarget.asStateFlow()

    private var applyJob: Job? = null
    private var lastActiveTarget = EmberPreferencesDefaults.FALLBACK_TARGET

    init {
        viewModelScope.launch {
            state.collect { current ->
                current.snapshot.targetTemperatureC?.takeIf { it > 0f }?.let { lastActiveTarget = it }
                val draft = _draftTarget.value ?: return@collect
                if (applyJob?.isActive == true) return@collect
                val confirmed = current.snapshot.targetTemperatureC?.let { abs(it - draft) < 0.05f } == true
                if (confirmed || current.writeStatus.phase == WritePhase.FAILED || !current.connected) {
                    _draftTarget.value = null
                }
            }
        }
    }

    fun connect(): EmberBleClient.ConnectResult = repository.connectPreferred()
    fun disconnect() = repository.disconnect()
    fun refreshEnvironment() = repository.refreshEnvironment()
    fun startMugScan() = repository.startMugScan()
    fun stopMugScan() = repository.stopMugScan()
    fun selectMug(address: String) = repository.selectMug(address)
    fun forgetMug(address: String) = repository.forgetMug(address)
    fun refresh() = repository.refresh()
    fun setDisplayUnit(unit: DisplayUnit) = repository.setDisplayUnit(unit)
    fun setPresets(values: List<Float>) = repository.setPresets(values)
    fun setLocalNickname(nickname: String) = repository.setLocalNickname(nickname)
    fun setHardwareName(name: String) = repository.setHardwareName(name)
    fun setLedColor(red: Int, green: Int, blue: Int) = repository.setLedColor(red, green, blue)
    fun setBackgroundMonitoring(enabled: Boolean) = repository.setBackgroundMonitoring(enabled)
    fun setAppVisible(visible: Boolean) = repository.setAppVisible(visible)

    /** Updates the shown target while a slider is being dragged, without sending it. */
    fun previewTarget(celsius: Float) {
        applyJob?.cancel()
        _draftTarget.value = celsius
    }

    /** Shows [celsius] immediately and sends it once the user pauses. */
    fun setTarget(celsius: Float, delayMs: Long = APPLY_DELAY_MS) {
        val clamped = celsius.coerceIn(EmberProtocol.MIN_TARGET_CELSIUS, EmberProtocol.MAX_TARGET_CELSIUS)
        _draftTarget.value = clamped
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            delay(delayMs)
            repository.setTargetTemperature(clamped)
        }
    }

    /** Sends whatever the slider was left on. */
    fun commitDraftTarget() {
        _draftTarget.value?.let { setTarget(it, delayMs = 150L) }
    }

    /** Moves the target one step (0.5 °C or 1 °F) up or down. */
    fun stepTarget(direction: Int) {
        val unit = state.value.displayUnit
        val base = currentTargetCelsius()
        val display = (unit.fromCelsius(base) / unit.step).roundToInt() * unit.step + direction * unit.step
        setTarget(unit.toCelsius(display.coerceIn(unit.range.start, unit.range.endInclusive)))
    }

    fun setHeater(on: Boolean) {
        if (on) {
            setTarget(currentTargetCelsius(), delayMs = 0L)
        } else {
            applyJob?.cancel()
            _draftTarget.value = null
            repository.setTargetTemperature(EmberProtocol.HEATER_OFF_CELSIUS)
        }
    }

    /** The target the controls should start from, even when the heater is currently off. */
    private fun currentTargetCelsius(): Float =
        _draftTarget.value ?: state.value.snapshot.targetTemperatureC?.takeIf { it > 0f } ?: lastActiveTarget

    private companion object {
        const val APPLY_DELAY_MS = 700L
    }
}

private object EmberPreferencesDefaults {
    const val FALLBACK_TARGET = 57.5f
}
