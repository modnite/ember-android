package org.hearth.ember.call

/** Silence is normal. Repair only sustained receive stalls during remote speech. */
class ReceiveHealth {
    private data class Sample(var bytes: Long, var since: Long, var attempts: Int = 0, var repaired: Long = 0)
    private val samples = mutableMapOf<String, Sample>()
    fun clear() = samples.clear()
    fun stalled(key: String, bytes: Long, speaking: Boolean, now: Long): Boolean {
        val s = samples.getOrPut(key) { Sample(bytes, now) }
        if (!speaking || bytes != s.bytes) { s.bytes = bytes; s.since = now; return false }
        if (now - s.since < 6_000 || s.attempts >= 2 || (s.attempts > 0 && now - s.repaired < 30_000)) return false
        s.attempts++; s.repaired = now; s.since = now
        return true
    }
}
