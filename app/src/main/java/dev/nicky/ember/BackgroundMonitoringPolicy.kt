package dev.nicky.ember

import dev.nicky.ember.data.EmberAppState

internal object BackgroundMonitoringPolicy {
    fun isKnownEmpty(state: EmberAppState): Boolean = state.snapshot.liquidState == LIQUID_STATE_EMPTY

    fun isLiveEmpty(state: EmberAppState): Boolean {
        return state.connected && state.snapshotFresh && isKnownEmpty(state)
    }

    fun shouldRunService(state: EmberAppState): Boolean {
        return state.backgroundMonitoring && !isKnownEmpty(state)
    }

    fun isRefill(previousLiquidState: Int?, currentLiquidState: Int?): Boolean {
        return previousLiquidState == LIQUID_STATE_EMPTY &&
            currentLiquidState != null && currentLiquidState != LIQUID_STATE_EMPTY
    }

    private const val LIQUID_STATE_EMPTY = 1
}
