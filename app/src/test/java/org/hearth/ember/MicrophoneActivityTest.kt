package org.hearth.ember
import org.hearth.ember.call.MicrophoneActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MicrophoneActivityTest {
    private fun frame(value: Int) = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(480) { putShort(value.toShort()) }; flip() }
    @Test fun capturedSpeechNeedsNoServerEcho() {
        val m = MicrophoneActivity(); val data = frame(1500)
        m.accept(data, 16, 1000)
        assertEquals(0, data.position()); assertTrue(m.hasSamples); assertTrue(m.active(1000, true))
    }
    @Test fun silenceAndVeryQuietNoiseDoNotLightTheBorder() {
        val m = MicrophoneActivity(); m.accept(frame(50), 16, 1000)
        assertTrue(m.hasSamples); assertFalse(m.active(1000, true))
    }
    @Test fun syllableGapsAreHeldButStoppedCaptureExpires() {
        val m = MicrophoneActivity(); m.accept(frame(1500), 16, 1000); m.accept(frame(0), 16, 1050)
        assertTrue(m.active(1200, true)); assertFalse(m.active(1251, true))
    }
    @Test fun muteClearsImmediatelyAndUnmuteDoesNotResurrectOldSpeech() {
        val m = MicrophoneActivity(); m.accept(frame(1500), 16, 1000)
        assertFalse(m.active(1001, false)); assertFalse(m.active(1002, true))
    }
    @Test fun leavingClearsSamplesAndUnsupportedInputUsesFallback() {
        val m = MicrophoneActivity(); m.accept(frame(1500), 16, 1000); m.clear()
        assertFalse(m.hasSamples); assertFalse(m.active(1001, true))
        m.accept(frame(1500), 32, 1002); assertFalse(m.hasSamples)
    }
}
