package org.hearth.ember

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hearth.ember.data.ArchiveCipher
import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupNames
import org.hearth.ember.data.BackupRun
import org.hearth.ember.data.BackupScheduler
import org.hearth.ember.data.Schedule
import org.hearth.ember.data.WebDav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupModelTest {
    @Test fun keepsTheNewestAndNamesWhatToDelete() {
        val names = listOf("ember-backup-20261001-0900.embkup", "ember-backup-20261003-0900.embkup", "ember-backup-20261002-0900.embkup", "ember-backup-20261004-100000.embkup")
        assertEquals(listOf("ember-backup-20261002-0900.embkup", "ember-backup-20261001-0900.embkup"), BackupNames.stale(names, 2))
        assertEquals(emptyList<String>(), BackupNames.stale(names, 10))
        // never deletes everything, whatever is asked
        assertEquals(3, BackupNames.stale(names, 0).size)
    }

    @Test fun recognisesItsOwnFilesOnly() {
        assertTrue(BackupNames.isCore(BackupNames.core(1_700_000_000_000)))
        assertTrue(BackupNames.isArchive(BackupNames.archive(1_700_000_000_000)))
        assertFalse(BackupNames.isCore("holiday.embkup"))
        assertFalse(BackupNames.isArchive("ember-archive-x.txt"))
        assertFalse(BackupNames.isCore(BackupNames.archive(0)))
    }

    @Test fun plainArchiveFilesStayAsTheyAre() {
        val c = ArchiveCipher.plain()
        val data = "hello".toByteArray()
        assertArrayEquals(data, c.seal(data))
        assertFalse(c.encrypted)
        assertFalse(c.header()["encrypted"].toString().toBoolean())
    }

    @Test fun sealedArchiveFilesOpenOnlyWithThePassphrase() {
        val sealed = ArchiveCipher.create("correct horse")
        val data = ByteArray(5000) { (it * 7).toByte() }
        val blob = sealed.seal(data)
        assertTrue(blob.size > data.size)
        assertFalse(blob.copyOfRange(12, 40).contentEquals(data.copyOfRange(0, 28)))
        // the header is enough to get the key back later
        val reopened = ArchiveCipher.from(sealed.header(), "correct horse")
        assertArrayEquals(data, reopened.open(blob))
        try { ArchiveCipher.from(sealed.header(), "wrong").open(blob); fail() } catch (e: BackupException) { assertTrue(e.needsPassphrase) }
        try { ArchiveCipher.from(sealed.header(), null); fail() } catch (e: BackupException) { assertTrue(e.needsPassphrase) }
    }

    @Test fun twoSealsOfTheSameFileDiffer() {
        val c = ArchiveCipher.create("pw")
        val data = "same".toByteArray()
        assertFalse(c.seal(data).contentEquals(c.seal(data)))
    }

    @Test fun listsWhatIsInAWebDavFolderWithSizesAndTimes() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/Backups/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/Backups/ember-backup-20261003-0900.embkup</d:href><d:propstat><d:prop>
                  <d:getcontentlength>1234</d:getcontentlength><d:getlastmodified>Sat, 03 Oct 2026 09:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/Backups/My%20notes.txt</d:href><d:propstat><d:prop><d:getcontentlength>5</d:getcontentlength></d:prop></d:propstat></d:response>
                </d:multistatus>""",
            ),
        )
        server.start()
        val entries = WebDav(OkHttpClient(), server.url("/dav/Backups").toString(), "u", "p").list()
        assertEquals(listOf("ember-backup-20261003-0900.embkup", "My notes.txt"), entries.map { it.name })
        assertEquals(1234L, entries[0].size)
        assertTrue(entries[0].modified > 0)
        assertEquals("PROPFIND", server.takeRequest().method)
        server.shutdown()
    }

    @Test fun putsAFileFromDiskAndDeletesOne() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404)) // deleting what is already gone is fine
        server.start()
        val dav = WebDav(OkHttpClient(), server.url("/dav").toString(), "u", "p")
        val file = File.createTempFile("backup", ".zip").apply { writeBytes(ByteArray(300_000) { 1 }); deleteOnExit() }
        dav.put("ember-archive-1.zip", file)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(300_000L, put.bodySize)
        dav.delete("ember-archive-0.zip")
        assertEquals("DELETE", server.takeRequest().method)
        dav.delete("gone.zip")
        server.shutdown()
    }

    @Test fun keyedArchiveSealsFilesAndNeedsTheKeyToOpen() {
        val key = ByteArray(32) { (it + 5).toByte() }
        val sealed = ArchiveCipher.keyed(key, "aabbccdd")
        assertTrue(sealed.encrypted)
        val blob = sealed.seal("hello".toByteArray())
        assertFalse(String(blob).contains("hello"))
        assertEquals("hello", String(ArchiveCipher.from(sealed.header(), null, key).open(blob)))
        try { ArchiveCipher.from(sealed.header(), null); fail() } catch (e: BackupException) { assertTrue(e.needsKey) }
    }

    @Test fun nextBackupTimeIsTheChosenClockTimeTodayOrTomorrow() {
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val noon = java.util.Calendar.getInstance(zone).apply { clear(); set(2026, 9, 29, 12, 0, 0) }.timeInMillis
        val hour = 60 * 60 * 1000L
        assertEquals(15 * hour, org.hearth.ember.data.BackupScheduler.delayUntil(3 * 60, noon, zone)) // 03:00 has passed: tomorrow
        assertEquals(2 * hour, org.hearth.ember.data.BackupScheduler.delayUntil(14 * 60, noon, zone))
        assertEquals(24 * hour, org.hearth.ember.data.BackupScheduler.delayUntil(12 * 60, noon, zone)) // exactly now: tomorrow
    }

    // ---- a missed scheduled backup is caught up
    private val hour = 3_600_000L
    private fun run(at: Long, ok: Boolean = true) = BackupRun(at, ok, "")

    @Test fun nothingIsOverdueWhenAutomaticBackupsAreOffOrThereIsNowhereToSave() {
        assertFalse(BackupScheduler.isOverdue(Schedule.Off, true, emptyList(), 100 * 24 * hour))
        assertFalse(BackupScheduler.isOverdue(Schedule.Daily, false, emptyList(), 100 * 24 * hour))
    }

    @Test fun aBackupIsOverdueOnceTheScheduleAndAGraceHaveGoneBy() {
        val now = 10 * 24 * hour
        assertFalse(BackupScheduler.isOverdue(Schedule.Daily, true, listOf(run(now - 23 * hour)), now))
        assertFalse(BackupScheduler.isOverdue(Schedule.Daily, true, listOf(run(now - 24 * hour - 20 * 60_000L)), now))
        assertTrue(BackupScheduler.isOverdue(Schedule.Daily, true, listOf(run(now - 25 * hour)), now))
        assertTrue(BackupScheduler.isOverdue(Schedule.Daily, true, emptyList(), now))
    }

    @Test fun aPlaceThatKeepsFailingIsNotRetriedAtEveryOpening() {
        val now = 10 * 24 * hour
        val failedJustNow = listOf(run(now - 40 * hour), run(now - 10 * 60_000L, ok = false))
        assertFalse(BackupScheduler.isOverdue(Schedule.Daily, true, failedJustNow, now))
        val failedLongAgo = listOf(run(now - 40 * hour), run(now - 2 * hour, ok = false))
        assertTrue(BackupScheduler.isOverdue(Schedule.Daily, true, failedLongAgo, now))
    }
}
