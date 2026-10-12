package dev.nicky.ember.ble

internal sealed interface ReconnectAction {
    /** Try a direct (fast, ~30 s timeout) connection again after [delayMs]. */
    data class Direct(val delayMs: Long) : ReconnectAction

    /**
     * Hand the connection to Android's background connect, which waits indefinitely for the
     * mug to start advertising again (for example after it wakes from sleep or comes back in range).
     */
    data class Background(val delayMs: Long) : ReconnectAction

    data object GiveUp : ReconnectAction
}

internal object ReconnectPolicy {
    const val MAX_DIRECT_ATTEMPTS = 3
    const val DIRECT_BACKOFF_MS = 1_200L
    const val BACKGROUND_RETRY_DELAY_MS = 5_000L

    /**
     * @param failures consecutive failed attempts including the one that just ended (1-based).
     * @param persistent whether something (the monitoring service or a visible screen) still
     *   needs the mug once quick retries are exhausted.
     */
    fun next(failures: Int, persistent: Boolean): ReconnectAction = when {
        failures <= MAX_DIRECT_ATTEMPTS -> ReconnectAction.Direct(DIRECT_BACKOFF_MS * failures)
        persistent -> ReconnectAction.Background(
            if (failures == MAX_DIRECT_ATTEMPTS + 1) 0L else BACKGROUND_RETRY_DELAY_MS,
        )
        else -> ReconnectAction.GiveUp
    }
}
