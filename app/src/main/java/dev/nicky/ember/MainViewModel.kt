package dev.nicky.ember

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberRepository

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: EmberRepository = (application as EmberApplication).repository

    val state = repository.state

    fun start(): Boolean = repository.start()
    fun connectPreferred() = repository.connectPreferred()
    fun disconnect() = repository.disconnect()
    fun startMugScan() = repository.startMugScan()
    fun stopMugScan() = repository.stopMugScan()
    fun selectMug(address: String) = repository.selectMug(address)
    fun refresh() = repository.refresh()
    fun setTargetTemperature(celsius: Float) = repository.setTargetTemperature(celsius)
    fun turnHeaterOff() = repository.setTargetTemperature(0f)
    fun setDisplayUnit(unit: DisplayUnit) = repository.setDisplayUnit(unit)
    fun setPresets(values: List<Float>) = repository.setPresets(values)
    fun setLocalNickname(nickname: String) = repository.setLocalNickname(nickname)
    fun setHardwareName(name: String) = repository.setHardwareName(name)
    fun setLedColor(red: Int, green: Int, blue: Int) = repository.setLedColor(red, green, blue)
    fun setBackgroundMonitoring(enabled: Boolean) = repository.setBackgroundMonitoring(enabled)
    fun setAppVisible(visible: Boolean) = repository.setAppVisible(visible)
}
