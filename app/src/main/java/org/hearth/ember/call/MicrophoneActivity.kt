package org.hearth.ember.call

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Activity from existing capture samples; never records or retains audio. */
class MicrophoneActivity {
    private var lastLoud: Long? = null
    @Volatile var hasSamples = false
        private set
    @Synchronized fun clear() { lastLoud = null; hasSamples = false }
    @Synchronized fun accept(data: ByteBuffer, bits: Int, now: Long) {
        if (bits != 16 || data.remaining() < 2) return
        val samples = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var energy = 0.0
        var count = 0
        while (samples.remaining() >= 2) { val v = samples.short / 32768.0; energy += v * v; count++ }
        hasSamples = true
        if (sqrt(energy / count) >= 0.008) lastLoud = now
    }
    @Synchronized fun active(now: Long, enabled: Boolean): Boolean {
        if (!enabled) { lastLoud = null; return false }
        return lastLoud?.let { now - it in 0..250 } == true
    }
}
