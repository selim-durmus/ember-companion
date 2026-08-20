package dev.nicky.ember

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyNotificationGateTest {
    @Test
    fun `first arrival at target notifies once`() {
        val gate = ReadyNotificationGate(MUG, 55f)

        assertFalse(gate.update(MUG, 55f, 54f, 5).shouldNotify)
        assertTrue(gate.update(MUG, 55f, 55f, 6).shouldNotify)
        assertFalse(gate.update(MUG, 55f, 55f, 6).shouldNotify)
    }

    @Test
    fun `small ready state oscillations do not rearm notification`() {
        val gate = ReadyNotificationGate(MUG, 55f)
        assertTrue(gate.update(MUG, 55f, 55f, 6).shouldNotify)

        assertFalse(gate.update(MUG, 55f, 55.5f, 5).shouldNotify)
        assertFalse(gate.update(MUG, 55f, 55f, 6).shouldNotify)
        assertFalse(gate.update(MUG, 55f, 54.5f, 5).shouldNotify)
        assertFalse(gate.update(MUG, 55f, 55f, 6).shouldNotify)
    }

    @Test
    fun `five degree drop rearms notification`() {
        val gate = notifiedGate()

        assertFalse(gate.update(MUG, 55f, 50f, 3).shouldNotify)
        assertTrue(gate.armed)
        assertTrue(gate.update(MUG, 55f, 55f, 6).shouldNotify)
    }

    @Test
    fun `five degree rise rearms notification`() {
        val gate = notifiedGate()

        assertFalse(gate.update(MUG, 55f, 60f, 4).shouldNotify)
        assertTrue(gate.armed)
        assertTrue(gate.update(MUG, 55f, 55f, 6).shouldNotify)
    }

    @Test
    fun `changing target arms a fresh arrival without trusting stale ready state`() {
        val gate = notifiedGate()

        assertFalse(gate.update(MUG, 60f, 55f, 6).shouldNotify)
        assertTrue(gate.armed)
        assertTrue(gate.update(MUG, 60f, 60f, 6).shouldNotify)
    }

    @Test
    fun `restored disarmed gate ignores small fluctuations`() {
        val gate = ReadyNotificationGate(MUG, 55f, armed = false)

        assertFalse(gate.update(MUG, 55f, 55.5f, 5).shouldNotify)
        assertFalse(gate.update(MUG, 55f, 55f, 6).shouldNotify)
    }

    private fun notifiedGate(): ReadyNotificationGate {
        return ReadyNotificationGate(MUG, 55f).also { gate ->
            assertTrue(gate.update(MUG, 55f, 55f, 6).shouldNotify)
        }
    }

    private companion object {
        const val MUG = "AA:BB:CC:DD:EE:FF"
    }
}
