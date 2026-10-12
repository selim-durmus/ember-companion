package dev.nicky.ember

import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.data.EmberAppState

internal object BackgroundMonitoringPolicy {
    fun isKnownEmpty(state: EmberAppState): Boolean = state.snapshot.liquidState == LiquidState.EMPTY

    fun isLiveEmpty(state: EmberAppState): Boolean = state.connected && state.snapshotFresh && isKnownEmpty(state)

    /**
     * Monitoring keeps a foreground service and connection alive only while it can be useful:
     * the feature is on, a mug is chosen, Bluetooth access is granted and the mug is not empty.
     * An empty mug pauses monitoring until the app sees it refilled.
     */
    fun shouldRunService(state: EmberAppState): Boolean =
        state.backgroundMonitoring && state.hasPermissions && state.selectedAddress != null && !isKnownEmpty(state)
}
