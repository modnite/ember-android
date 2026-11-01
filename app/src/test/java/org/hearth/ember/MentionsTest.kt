package org.hearth.ember

import org.hearth.ember.data.User
import org.hearth.ember.ui.chat.Mentions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    private val ann = User(id = "a", displayName = "Ann Lee", username = "ann")
    private val bo = User(id = "b", displayName = "Bo", username = "bobby")
    private val me = User(id = "m", displayName = "Me", username = "me")
    private val all = listOf(ann, bo, me)

    @Test fun opensForAnAtAloneAtTheStartOrAfterASpace() {
        assertEquals(Mentions.Trigger(0, ""), Mentions.trigger("@", 1))
        assertEquals(Mentions.Trigger(6, ""), Mentions.trigger("hello @", 7))
        assertEquals(Mentions.Trigger(4, "an"), Mentions.trigger("hi (@an", 7))
    }

    @Test fun doesNotOpenInsideAnEmailAddressOrAWord() {
        assertNull(Mentions.trigger("write to anna@example.com", 25))
        assertNull(Mentions.trigger("anna@", 5))
        assertNull(Mentions.trigger("a@b", 3))
    }

    @Test fun closesOnceThereIsASpaceAfterTheNameOrTheCaretMovedAway() {
        assertNull(Mentions.trigger("@Ann Le", 7))
        assertNull(Mentions.trigger("@Ann hello", 10))
        assertEquals(Mentions.Trigger(0, "Ann"), Mentions.trigger("@Ann hello", 4))
    }

    @Test fun offersEveryoneButYouAndNarrowsAsYouType() {
        assertEquals(listOf("a", "b"), Mentions.matches(all, "", "m").map { it.id })
        assertEquals(listOf("a"), Mentions.matches(all, "an", "m").map { it.id })
        assertEquals(listOf("b"), Mentions.matches(all, "bob", "m").map { it.id })
        assertEquals(listOf("a"), Mentions.matches(all, "lee", "m").map { it.id })
        assertEquals(emptyList<User>(), Mentions.matches(all, "zz", "m"))
    }

    @Test fun namesThatBeginWithItComeBeforeALaterWord() {
        val lena = User(id = "l", displayName = "Lena Park", username = "lena")
        assertEquals(listOf("l", "a"), Mentions.matches(listOf(ann, lena), "le", "m").map { it.id })
    }

    @Test fun insertsTheFullNameAndASpace() {
        val t = Mentions.trigger("hey @an", 7)!!
        assertEquals("hey @Ann Lee " to 13, Mentions.insert("hey @an", t, 7, ann))
        val t2 = Mentions.trigger("hey @an there", 7)!!
        assertEquals("hey @Ann Lee  there", Mentions.insert("hey @an there", t2, 7, ann).first)
    }

    @Test fun marksANameAfterAtByDisplayNameOrUsername() {
        val out = Mentions.split("hi @Ann Lee, and @bobby!", all)
        assertEquals(listOf("hi ", "@Ann Lee", ", and ", "@bobby", "!"), out.map { it.text })
        assertEquals(listOf(null, ann, null, bo, null), out.map { it.person })
    }

    @Test fun prefersTheLongerName() {
        val a2 = User(id = "x", displayName = "Ann", username = "annie")
        assertEquals(ann, Mentions.split("@Ann Lee hi", listOf(a2, ann))[0].person)
    }

    @Test fun doesNotMarkEmailsOtherWordsOrHalfNames() {
        assertEquals(1, Mentions.split("mail ann@ann.com", all).size)
        assertNull(Mentions.split("mail ann@ann.com", all)[0].person)
        assertNull(Mentions.split("@Anna", all)[0].person)
        assertNull(Mentions.split("@nobody", all)[0].person)
    }

    @Test fun isNotCaseSensitive() {
        assertNotNull(Mentions.split("@ann lee", all)[0].person)
    }
}
