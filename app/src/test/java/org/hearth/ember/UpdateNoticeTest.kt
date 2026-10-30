package org.hearth.ember

import org.hearth.ember.data.UpdateNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticeTest {
    @Test fun `short notes are kept as they are`() {
        assertEquals("Groups can be renamed.", UpdateNotice.shorten("Groups can be renamed.", 100))
    }

    @Test fun `a long paragraph is cut at a word with an ellipsis`() {
        val long = "Groups: add people, remove people, rename the group. Search boxes keep their keyboard on Samsung phones and never cut their text off."
        val cut = UpdateNotice.shorten(long, 60)
        assertTrue(cut.endsWith("\u2026"))
        assertTrue(cut.length <= 61)
        assertTrue("cut at a word", long.startsWith(cut.removeSuffix("\u2026")))
        assertTrue(!cut.removeSuffix("\u2026").endsWith(" "))
    }

    @Test fun `nothing is left hanging before the ellipsis`() {
        assertEquals("One, two\u2026", UpdateNotice.shorten("One, two, three four five", 10))
    }
}
