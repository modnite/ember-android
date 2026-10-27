package org.hearth.ember

import org.hearth.ember.data.NudgeNotes
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeNotesTest {
    private var n = 0
    private fun id() = "id${n++}"

    @Test fun oneNudgeIsOneLine() {
        val l = NudgeNotes.add(emptyList(), "b", "B", 1_000, ::id)
        assertEquals(listOf("B sent a nudge"), l.map { it.text })
    }

    @Test fun aRunFromOnePersonBecomesACount() {
        var l = emptyList<org.hearth.ember.data.NudgeNote>()
        for (i in 1..17) l = NudgeNotes.add(l, "b", "B", 1_000L + i * 2_000, ::id)
        assertEquals(listOf("B sent a nudge ×17"), l.map { it.text })
    }

    @Test fun aPauseOverAMinuteStartsANewLine() {
        var l = NudgeNotes.add(emptyList(), "b", "B", 0, ::id)
        l = NudgeNotes.add(l, "b", "B", 30_000, ::id)
        l = NudgeNotes.add(l, "b", "B", 30_000 + 61_000, ::id)
        assertEquals(listOf("B sent a nudge", "B sent a nudge ×2"), l.map { it.text })
    }

    @Test fun anotherPersonBreaksTheRun() {
        var l = NudgeNotes.add(emptyList(), "b", "B", 0, ::id)
        l = NudgeNotes.add(l, "me", "You", 1_000, ::id)
        l = NudgeNotes.add(l, "b", "B", 2_000, ::id)
        assertEquals(listOf("B sent a nudge", "You sent a nudge", "B sent a nudge"), l.map { it.text })
    }
}
