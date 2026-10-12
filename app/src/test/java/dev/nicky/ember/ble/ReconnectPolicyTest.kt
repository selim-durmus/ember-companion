package dev.nicky.ember.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun `quick retries back off linearly`() {
        assertEquals(ReconnectAction.Direct(1_200L), ReconnectPolicy.next(1, persistent = false))
        assertEquals(ReconnectAction.Direct(2_400L), ReconnectPolicy.next(2, persistent = true))
        assertEquals(ReconnectAction.Direct(3_600L), ReconnectPolicy.next(3, persistent = false))
    }

    @Test
    fun `gives up after quick retries when nothing needs the mug`() {
        assertEquals(ReconnectAction.GiveUp, ReconnectPolicy.next(4, persistent = false))
    }

    @Test
    fun `falls back to background connect immediately, then waits between attempts`() {
        assertEquals(ReconnectAction.Background(0L), ReconnectPolicy.next(4, persistent = true))
        assertEquals(ReconnectAction.Background(5_000L), ReconnectPolicy.next(5, persistent = true))
        assertEquals(ReconnectAction.Background(5_000L), ReconnectPolicy.next(40, persistent = true))
    }
}
