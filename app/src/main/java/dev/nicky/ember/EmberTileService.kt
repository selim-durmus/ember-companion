package dev.nicky.ember

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.nicky.ember.data.QuickAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Tap toggles the heater (off ↔ middle preset), connecting first when needed. */
class EmberTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private val repository
        get() = (application as EmberApplication).repository

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            repository.state.map(ExternalState::from).distinctUntilChanged().collect(::render)
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = repository.state.value
        if (state.selectedAddress == null || !state.hasPermissions) {
            openApp()
            return
        }
        val run = { repository.runQuickAction(QuickAction.TOGGLE_HEATER) }
        if (isLocked) unlockAndRun(run) else run()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: ExternalState) {
        val tile = qsTile ?: return
        tile.label = state.name
        tile.subtitle = when {
            !state.hasMug -> getString(R.string.tile_set_up)
            state.heaterOn && state.current != null && state.connected -> "${state.current} · ${state.status}"
            state.heaterOn -> state.target?.let { getString(R.string.tile_heating_to, it) } ?: state.status
            else -> state.presetLabel?.let { getString(R.string.tile_tap_to_heat, it) } ?: state.status
        }
        tile.state = if (state.heaterOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, EmberTileService::class.java)) }
        }
    }
}
