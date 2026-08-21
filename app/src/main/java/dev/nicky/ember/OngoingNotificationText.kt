package dev.nicky.ember

import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.EmberRepository
import java.util.Locale

internal object OngoingNotificationText {
    fun summary(state: EmberAppState): String {
        val battery = state.snapshot.batteryPercent?.let { percent ->
            buildString {
                append("Battery $percent%")
                if (state.snapshot.charging == true) append(" · charging")
            }
        }
        if (!state.connected) {
            return listOfNotNull(state.connectionDetail.takeIf(String::isNotBlank), battery)
                .joinToString(" · ")
                .ifBlank { "Not connected" }
        }

        val target = state.snapshot.targetTemperatureC
        return when {
            state.snapshot.liquidState == LIQUID_STATE_READY -> listOfNotNull("Ready", battery).joinToString(" · ")
            target != null && target <= 0f -> listOfNotNull("Temperature control off", battery).joinToString(" · ")
            state.snapshot.liquidState == LIQUID_STATE_FILLING ||
                state.snapshot.liquidState == LIQUID_STATE_COOLING ||
                state.snapshot.liquidState == LIQUID_STATE_HEATING -> {
                val progress = temperatureProgress(state)
                val label = EmberProtocol.liquidStateLabel(state.snapshot.liquidState)
                listOfNotNull(progress, label, battery).joinToString(" · ")
            }
            else -> {
                val current = state.snapshot.currentTemperatureC?.let { formatTemperature(it, state.displayUnit) }
                val label = EmberProtocol.liquidStateLabel(state.snapshot.liquidState)
                listOfNotNull(current, label, battery).joinToString(" · ").ifBlank { "Connected" }
            }
        }
    }

    private fun temperatureProgress(state: EmberAppState): String? {
        val currentCelsius = state.snapshot.currentTemperatureC ?: return null
        val targetCelsius = state.snapshot.targetTemperatureC?.takeIf { it > 0f } ?: return null
        val current = EmberRepository.displayTemperature(currentCelsius, state.displayUnit)
        val target = EmberRepository.displayTemperature(targetCelsius, state.displayUnit)
        return if (state.displayUnit == DisplayUnit.FAHRENHEIT) {
            String.format(Locale.US, "%.0f → %.0f °F", current, target)
        } else {
            String.format(Locale.US, "%.1f → %.1f °C", current, target)
        }
    }

    private fun formatTemperature(celsius: Float, unit: DisplayUnit): String {
        val value = EmberRepository.displayTemperature(celsius, unit)
        return if (unit == DisplayUnit.FAHRENHEIT) {
            String.format(Locale.US, "%.0f °F", value)
        } else {
            String.format(Locale.US, "%.1f °C", value)
        }
    }

    private const val LIQUID_STATE_FILLING = 2
    private const val LIQUID_STATE_COOLING = 4
    private const val LIQUID_STATE_HEATING = 5
    private const val LIQUID_STATE_READY = 6
}
