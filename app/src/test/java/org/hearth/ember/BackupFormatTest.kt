package org.hearth.ember

import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {
    private val data = """{"e2eeSecret":"abc","theme":2}"""

    @Test fun plainBackupRoundTripsAndIsReadable() {
        val file = BackupFormat.seal(data, null)
        assertFalse(BackupFormat.isEncrypted(file))
        assertTrue(String(file).contains("e2eeSecret"))
        assertEquals(data, BackupFormat.open(file, null))
    }

    @Test fun encryptedBackupHidesTheDataAndNeedsThePassphrase() {
        val file = BackupFormat.seal(data, "correct horse")
        assertTrue(BackupFormat.isEncrypted(file))
        assertFalse(String(file).contains("e2eeSecret"))
        assertEquals(data, BackupFormat.open(file, "correct horse"))
        try { BackupFormat.open(file, null); fail() } catch (e: BackupException) { assertTrue(e.needsPassphrase) }
        try { BackupFormat.open(file, "wrong"); fail() } catch (e: BackupException) { assertTrue(e.needsPassphrase) }
    }

    @Test fun somethingElseIsRefused() {
        try { BackupFormat.open("hello".toByteArray(), null); fail() } catch (e: BackupException) { assertFalse(e.needsPassphrase) }
    }
}
