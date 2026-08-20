package dev.nicky.ember

import kotlin.math.abs

internal data class ReadyNotificationDecision(
    val shouldNotify: Boolean,
    val stateChanged: Boolean,
)

internal class ReadyNotificationGate(
    mugAddress: String? = null,
    targetCelsius: Float? = null,
    armed: Boolean = true,
) {
    var mugAddress: String? = mugAddress
        private set
    var targetCelsius: Float? = targetCelsius
        private set
    var armed: Boolean = armed
        private set

    fun update(
        mugAddress: String?,
        targetCelsius: Float?,
        currentCelsius: Float?,
        liquidState: Int?,
    ): ReadyNotificationDecision {
        var stateChanged = false
        if (this.mugAddress != mugAddress || !sameTarget(this.targetCelsius, targetCelsius)) {
            this.mugAddress = mugAddress
            this.targetCelsius = targetCelsius
            armed = true
            stateChanged = true
        }

        val activeTarget = targetCelsius?.takeIf { it > 0f }
        if (mugAddress == null || activeTarget == null || currentCelsius == null) {
            return ReadyNotificationDecision(shouldNotify = false, stateChanged = stateChanged)
        }

        val distanceFromTarget = abs(currentCelsius - activeTarget)
        if (!armed && distanceFromTarget >= REARM_DISTANCE_CELSIUS) {
            armed = true
            stateChanged = true
        }

        val reachedTarget = liquidState == LIQUID_STATE_READY && distanceFromTarget <= READY_TOLERANCE_CELSIUS
        if (armed && reachedTarget) {
            armed = false
            return ReadyNotificationDecision(shouldNotify = true, stateChanged = true)
        }
        return ReadyNotificationDecision(shouldNotify = false, stateChanged = stateChanged)
    }

    private fun sameTarget(first: Float?, second: Float?): Boolean {
        if (first == null || second == null) return first == second
        return abs(first - second) < TARGET_CHANGE_EPSILON_CELSIUS
    }

    companion object {
        const val REARM_DISTANCE_CELSIUS = 5f
        private const val READY_TOLERANCE_CELSIUS = 1f
        private const val TARGET_CHANGE_EPSILON_CELSIUS = 0.01f
        private const val LIQUID_STATE_READY = 6
    }
}
