package org.hearth.ember

import org.hearth.ember.data.EmberJson
import org.hearth.ember.data.Room
import org.hearth.ember.data.RoomsResponse
import org.hearth.ember.data.User
import org.hearth.ember.ui.callTimer
import org.hearth.ember.ui.fileSize
import org.hearth.ember.ui.sameDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsAndFormatTest {
    private val me = User(id = "me", displayName = "Me")
    private val alex = User(id = "alex", displayName = "Alex", status = "online")

    @Test fun directChatIsNamedAfterTheOtherPerson() {
        val room = Room(id = "r", kind = "dm", members = listOf(me, alex))
        assertEquals("Alex", room.title("me"))
        assertEquals(alex, room.other("me"))
        assertTrue(room.isDm)
    }

    @Test fun groupKeepsItsOwnNameAndHasNoOtherPerson() {
        val room = Room(id = "g", kind = "group", name = "Weekend", members = listOf(me, alex))
        assertEquals("Weekend", room.title("me"))
        assertNull(room.other("me"))
        assertEquals("Group", Room(id = "x", kind = "group").title("me"))
    }

    @Test fun onlineMeansOnlineAwayOrBusy() {
        assertTrue(alex.isOnline)
        assertTrue(alex.copy(status = "away").isOnline)
        assertFalse(alex.copy(status = "offline").isOnline)
    }

    @Test fun serverRoomsDecodeAndIgnoreUnknownFields() {
        val json = """{"rooms":[{"id":"a","kind":"dm","encrypted":true,"members":[{"id":"u","displayName":"U","somethingNew":1}],"extra":"x"}]}"""
        val rooms = EmberJson.decodeFromString<RoomsResponse>(json).rooms
        assertEquals(1, rooms.size)
        assertTrue(rooms[0].encrypted)
        assertEquals("U", rooms[0].members[0].displayName)
    }

    @Test fun callTimerShowsMinutesThenHours() {
        assertEquals("0:00", callTimer(-5))
        assertEquals("0:59", callTimer(59_000))
        assertEquals("1:05", callTimer(65_000))
        assertEquals("1:01:01", callTimer(3_661_000))
    }

    @Test fun fileSizesAreReadable() {
        assertEquals("512 B", fileSize(512))
        assertEquals("2 KB", fileSize(2048))
        assertTrue(fileSize(5L * 1024 * 1024).endsWith("MB"))
    }

    @Test fun sameDayComparesCalendarDays() {
        val noon = 1_700_000_000_000L
        assertTrue(sameDay(noon, noon + 60_000))
        assertFalse(sameDay(noon, noon + 3 * 24 * 3_600_000L))
    }
}
