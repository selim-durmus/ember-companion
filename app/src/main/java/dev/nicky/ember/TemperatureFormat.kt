package dev.nicky.ember

import dev.nicky.ember.data.DisplayUnit
import java.util.Locale

internal object TemperatureFormat {
    /** "57.5 °C" / "136 °F"; [compact] drops the unit letter: "57.5°". */
    fun format(celsius: Float, unit: DisplayUnit, compact: Boolean = false): String {
        val number = number(unit.fromCelsius(celsius), unit)
        return if (compact) "$number°" else "$number ${unit.symbol}"
    }

    fun number(displayValue: Float, unit: DisplayUnit): String = if (unit == DisplayUnit.FAHRENHEIT) {
        String.format(Locale.US, "%.0f", displayValue)
    } else {
        String.format(Locale.US, "%.1f", displayValue)
    }
}
