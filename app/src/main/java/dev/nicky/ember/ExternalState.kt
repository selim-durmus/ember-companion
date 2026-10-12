package dev.nicky.ember

import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.data.EmberAppState

/** What the widget and Quick Settings tile show; compared to skip redundant updates. */
internal data class ExternalState(
    val hasMug: Boolean,
    val name: String,
    val connected: Boolean,
    val heaterOn: Boolean,
    val ready: Boolean,
    val current: String?,
    val target: String?,
    val status: String,
    val presetLabel: String?,
) {
    companion object {
        fun from(state: EmberAppState): ExternalState {
            val snapshot = state.snapshot
            val ready = state.connected && snapshot.liquidState == LiquidState.AT_TARGET
            return ExternalState(
                hasMug = state.selectedAddress != null,
                name = state.mugDisplayName,
                connected = state.connected,
                heaterOn = state.heaterOn,
                ready = ready,
                current = snapshot.currentTemperatureC?.let { TemperatureFormat.format(it, state.displayUnit, compact = true) },
                target = snapshot.targetTemperatureC?.let {
                    if (it <= 0f) "Off" else TemperatureFormat.format(it, state.displayUnit, compact = true)
                },
                status = when {
                    state.selectedAddress == null -> "Set up a mug"
                    ready -> "Ready to drink"
                    state.connected && snapshot.liquidState == LiquidState.EMPTY -> "Empty"
                    state.connected && !state.heaterOn -> "Heater off"
                    state.connected && snapshot.liquidState == LiquidState.HEATING -> "Heating"
                    state.connected && snapshot.liquidState == LiquidState.COOLING -> "Cooling"
                    state.connected -> "Connected"
                    state.busy -> "Connecting…"
                    state.snapshotUpdatedAt > 0 -> "Last known"
                    else -> "Not connected"
                },
                presetLabel = state.presetsCelsius.getOrNull(1)?.let { TemperatureFormat.format(it, state.displayUnit, compact = true) },
            )
        }
    }
}
