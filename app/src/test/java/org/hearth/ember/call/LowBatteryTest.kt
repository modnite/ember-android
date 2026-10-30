package org.hearth.ember.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryTest {
    @Test
    fun warnsAtEachStepOnce() {
        val done = mutableSetOf<Int>()
        assertFalse(LowBattery.crossed(50, false, done))
        assertFalse(LowBattery.crossed(21, false, done))
        assertTrue(LowBattery.crossed(20, false, done))
        assertFalse(LowBattery.crossed(19, false, done))
        assertTrue(LowBattery.crossed(15, false, done))
        assertFalse(LowBattery.crossed(14, false, done))
        assertTrue(LowBattery.crossed(10, false, done))
        assertTrue(LowBattery.crossed(5, false, done))
        assertFalse(LowBattery.crossed(4, false, done))
        assertFalse(LowBattery.crossed(5, false, done))
    }

    @Test
    fun skippedStepsGiveOneWarning() {
        val done = mutableSetOf<Int>()
        assertTrue(LowBattery.crossed(14, false, done))
        assertFalse(LowBattery.crossed(13, false, done))
        assertFalse(LowBattery.crossed(12, false, done))
        assertTrue(LowBattery.crossed(9, false, done))
    }

    @Test
    fun startingBelowEveryStepWarnsOnceAtJoining() {
        val done = mutableSetOf<Int>()
        assertTrue(LowBattery.crossed(3, false, done))
        assertFalse(LowBattery.crossed(3, false, done))
    }

    @Test
    fun chargingForgetsEverything() {
        val done = mutableSetOf<Int>()
        assertTrue(LowBattery.crossed(15, false, done))
        assertFalse(LowBattery.crossed(16, true, done))
        assertTrue(done.isEmpty())
        // unplugged at 18: warns again
        assertTrue(LowBattery.crossed(18, false, done))
    }

    @Test
    fun climbingBackAboveTheFirstStepForgets() {
        val done = mutableSetOf<Int>()
        assertTrue(LowBattery.crossed(12, false, done))
        assertFalse(LowBattery.crossed(25, false, done))
        assertTrue(LowBattery.crossed(19, false, done))
    }

    @Test
    fun messagesRoundTripAndBadOnesAreIgnored() {
        assertEquals(15 to false, LowBattery.decode(LowBattery.encode(15, false)))
        assertEquals(40 to true, LowBattery.decode(LowBattery.encode(40, true)))
        assertNull(LowBattery.decode("nonsense".toByteArray()))
        assertNull(LowBattery.decode("{\"level\":250}".toByteArray()))
        assertNull(LowBattery.decode("{}".toByteArray()))
    }
}
