package org.hearth.ember.data

/** A line in a chat saying someone nudged. Several in a row from one person are one line with a count. */
class NudgeNote(val id: String, val who: String, val text: String, val at: Long, val count: Int)

/** The web client's rule (web/src/lib/nudgeEvents.ts): the same person nudging again within a minute of their last nudge raises the count of that line. */
object NudgeNotes {
    const val MERGE_MS = 60_000L

    /** [notes] is newest first. Returns the new list, at most 50 long. */
    fun add(notes: List<NudgeNote>, who: String, name: String, now: Long, newId: () -> String): List<NudgeNote> {
        val last = notes.firstOrNull()
        return if (last != null && last.who == who && now - last.at < MERGE_MS) {
            val count = last.count + 1
            listOf(NudgeNote(last.id, who, "$name sent a nudge ×$count", now, count)) + notes.drop(1)
        } else {
            (listOf(NudgeNote(newId(), who, "$name sent a nudge", now, 1)) + notes).take(50)
        }
    }
}
