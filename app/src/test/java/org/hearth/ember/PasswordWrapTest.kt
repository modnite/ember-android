package org.hearth.ember

import org.hearth.ember.data.BackupException
import org.hearth.ember.data.BackupFormat
import org.hearth.ember.data.PasswordWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PasswordWrapTest {
    private val key = ByteArray(32) { (it * 5 + 2).toByte() }

    @Test fun derivesTheSameKeyAsTheDesktopReader() {
        val k = PasswordWrap.derive("correct horse", ByteArray(16) { it.toByte() }, memoryKib = 1024, passes = 2, lanes = 1)
        assertEquals("77237b8a217dfd77699617e3893817f9fa215bff881849f3c007154da0b4788d", k.joinToString("") { "%02x".format(it) })
    }

    @Test fun anEnvelopeOpensWithItsPasswordOnly() {
        val env = PasswordWrap.wrap(key, "my account password")
        assertEquals(key.toList(), PasswordWrap.unwrap(env, "my account password").toList())
        try { PasswordWrap.unwrap(env, "another one"); fail() } catch (e: BackupException) { assertTrue(e.needsPassword) }
    }

    @Test fun aBackupCarriesTheEnvelopeAndOpensWithThePasswordAlone() {
        val env = PasswordWrap.wrap(key, "pw one")
        val file = BackupFormat.sealKeyed("""{"x":1}""", key, "aabbccdd", env)
        assertEquals(3, BackupFormat.parseVersion(file))
        assertEquals("""{"x":1}""", BackupFormat.open(file, null, password = "pw one"))
        try { BackupFormat.open(file, null, password = "pw two"); fail() } catch (e: BackupException) { assertTrue(e.needsPassword) }
        try { BackupFormat.open(file, null); fail() } catch (e: BackupException) { assertTrue(e.needsPassword) }
        // the key itself still opens it (the phone that made it holds the key)
        assertEquals("""{"x":1}""", BackupFormat.open(file, null, key, "aabbccdd"))
    }
}
