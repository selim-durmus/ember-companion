package dev.nicky.ember

import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.data.EmberAppState

internal object OngoingNotificationText {
    fun summary(state: EmberAppState): String {
        val snapshot = state.snapshot
        val battery = snapshot.batteryPercent?.let { percent ->
            if (snapshot.charging == true) "Battery $percent% · charging" else "Battery $percent%"
        }
        if (!state.connected) {
            return listOfNotNull(state.connectionDetail.takeIf(String::isNotBlank), battery)
                .joinToString(" · ")
                .ifBlank { "Not connected" }
        }

        val target = snapshot.targetTemperatureC
        return when {
            snapshot.liquidState == LiquidState.AT_TARGET -> listOfNotNull("Ready", battery).joinToString(" · ")
            target != null && target <= 0f -> listOfNotNull("Temperature control off", battery).joinToString(" · ")
            snapshot.liquidState == LiquidState.FILLING ||
                snapshot.liquidState == LiquidState.COOLING ||
                snapshot.liquidState == LiquidState.HEATING -> {
                listOfNotNull(temperatureProgress(state), EmberProtocol.liquidStateLabel(snapshot.liquidState), battery)
                    .joinToString(" · ")
            }
            else -> {
                val current = snapshot.currentTemperatureC?.let { TemperatureFormat.format(it, state.displayUnit) }
                listOfNotNull(current, EmberProtocol.liquidStateLabel(snapshot.liquidState), battery)
                    .joinToString(" · ")
                    .ifBlank { "Connected" }
            }
        }
    }

    private fun temperatureProgress(state: EmberAppState): String? {
        val current = state.snapshot.currentTemperatureC ?: return null
        val target = state.snapshot.targetTemperatureC?.takeIf { it > 0f } ?: return null
        val unit = state.displayUnit
        return "${TemperatureFormat.number(unit.fromCelsius(current), unit)} → ${TemperatureFormat.format(target, unit)}"
    }
}
