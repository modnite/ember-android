package org.hearth.ember

import android.view.textservice.SuggestionsInfo
import org.hearth.ember.ui.chat.SpellChecker
import org.hearth.ember.ui.chat.SpellError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellCheckTest {
    @Test fun `a word in the dictionary is not a mistake`() {
        assertFalse(SpellChecker.isMistake(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, 0))
    }

    @Test fun `a typo flag or an offer of suggestions marks a mistake (Google's keyboard does the second)`() {
        assertTrue(SpellChecker.isMistake(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, 0))
        assertTrue(SpellChecker.isMistake(28, 3))
    }

    @Test fun `an unknown word with nothing to offer is left alone`() {
        assertFalse(SpellChecker.isMistake(0, 0))
    }

    @Test fun `only real words are checked`() {
        assertTrue(SpellChecker.isCheckable("hello"))
        assertTrue(SpellChecker.isCheckable("don't"))
        assertTrue(SpellChecker.isCheckable("well-known"))
        assertFalse(SpellChecker.isCheckable("a"))
        assertFalse(SpellChecker.isCheckable("b2b"))
        assertFalse(SpellChecker.isCheckable("https://x.org"))
        assertFalse(SpellChecker.isCheckable("12345"))
    }

    @Test fun `the cursor in or touching a mistake finds it`() {
        val errors = listOf(SpellError(0, 3, listOf("This")), SpellError(10, 15, emptyList()))
        assertEquals(errors[0], SpellChecker.at(errors, 0))
        assertEquals(errors[0], SpellChecker.at(errors, 3))
        assertNull(SpellChecker.at(errors, 5))
        assertEquals(errors[1], SpellChecker.at(errors, 12))
    }

    @Test fun `a suggestion replaces the word and the cursor goes after it`() {
        val (text, cursor) = SpellChecker.apply("Ths is a test", SpellError(0, 3, listOf("This")), "This")
        assertEquals("This is a test", text)
        assertEquals(4, cursor)
        val (t2, c2) = SpellChecker.apply("so spellcheking now", SpellError(3, 15, emptyList()), "spellchecking")
        assertEquals("so spellchecking now", t2)
        assertEquals(16, c2)
    }
}
