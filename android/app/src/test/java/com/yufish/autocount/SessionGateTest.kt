package com.yufish.autocount

import org.junit.Assert.*
import org.junit.Test

class SessionGateTest {
    @Test fun cancellationRequiresCleanupBeforeAnotherSession() {
        val gate = SessionGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        gate.cancel()
        assertTrue(gate.busy())
        assertFalse(gate.active())
        assertFalse(gate.begin())
        gate.finish()
        assertTrue(gate.begin())
    }

    @Test fun oldQueuedActionsCannotEnterANewSession() {
        val gate = SessionGate()
        gate.begin()
        val old = gate.token()
        assertTrue(gate.accepts(old))
        gate.cancel()
        assertFalse(gate.accepts(old))
        gate.finish()
        gate.begin()
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(gate.token()))
    }
}
