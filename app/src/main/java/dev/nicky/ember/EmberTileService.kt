package dev.nicky.ember

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.nicky.ember.data.EmberAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EmberTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private val repository
        get() = (application as EmberApplication).repository

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            repository.state.collectLatest(::render)
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val action = {
            val state = repository.state.value
            when {
                !state.connected -> repository.connectPreferred()
                (state.snapshot.targetTemperatureC ?: 0f) > 0f -> repository.setTargetTemperature(0f)
                else -> state.presetsCelsius.getOrNull(1)?.let(repository::setTargetTemperature)
            }
            render(repository.state.value)
        }
        if (isLocked) unlockAndRun(action) else action()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: EmberAppState) {
        qsTile ?: return
        qsTile.label = state.selectedProfile?.displayName ?: "Ember"
        qsTile.subtitle = when {
            !state.connected -> "Tap to connect"
            (state.snapshot.targetTemperatureC ?: 0f) == 0f -> "Heater off · tap for preset"
            state.snapshot.liquidState == 6 -> "Ready · tap to turn off"
            else -> "${state.connectionDetail} · tap to turn off"
        }
        qsTile.state = if (state.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, EmberTileService::class.java))
        }
    }
}
