package org.hearth.ember
import org.hearth.ember.call.ReceiveHealth
import org.junit.Assert.*
import org.junit.Test

class ReceiveHealthTest {
    @Test fun silenceDoesNotCauseRepairs() {
        val h = ReceiveHealth()
        for (t in 0L..60_000L step 3_000) assertFalse(h.stalled("a", 0, false, t))
    }
    @Test fun arrivingBytesDoNotCauseRepairs() {
        val h = ReceiveHealth()
        for (t in 0L..60_000L step 3_000) assertFalse(h.stalled("a", t, true, t))
    }
    @Test fun normalPauseAfterTalkingResetsTheStallClock() {
        val h = ReceiveHealth()
        assertFalse(h.stalled("a", 100, true, 0))
        assertFalse(h.stalled("a", 100, true, 3_000))
        assertFalse(h.stalled("a", 100, false, 6_000))
        assertFalse(h.stalled("a", 100, false, 60_000))
        assertFalse(h.stalled("a", 100, true, 63_000))
    }
    @Test fun stalledSpeechHasGraceCooldownAndBoundedRetries() {
        val h = ReceiveHealth()
        assertFalse(h.stalled("a", 0, true, 0))
        assertFalse(h.stalled("a", 0, true, 3_000))
        assertTrue(h.stalled("a", 0, true, 6_000))
        assertFalse(h.stalled("a", 0, true, 12_000))
        assertTrue(h.stalled("a", 0, true, 36_000))
        assertFalse(h.stalled("a", 0, true, 90_000))
    }
    @Test fun peersAndNewCallsHaveSeparateBudgets() {
        val h = ReceiveHealth()
        h.stalled("a", 0, true, 0)
        assertTrue(h.stalled("a", 0, true, 6_000))
        assertFalse(h.stalled("b", 0, true, 6_000))
        h.clear()
        assertFalse(h.stalled("a", 0, true, 90_000))
    }
}
