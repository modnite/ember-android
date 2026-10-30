package org.hearth.ember.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatTest {
    private val mine = AccountKey(ByteArray(32) { (it * 7 + 1).toByte() }, null).let { AccountKey(it.bytes, AccountKey.fingerprint(it.bytes)) }
    private val someoneElse = AccountKey(ByteArray(32) { (it * 11 + 3).toByte() }, null).let { AccountKey(it.bytes, AccountKey.fingerprint(it.bytes)) }
    private val data = """{"e2eeSecret":"abc","settings":{"theme":2}}"""

    @Test fun aBackupOpensWithItsOwnKey() {
        val file = Format.sealCore(data, mine)
        assertFalse(String(file).contains("e2eeSecret"))
        assertEquals(data, Format.openCore(file, mine, null))
    }

    @Test fun nobodyElsesKeyOpensIt() {
        val file = Format.sealCore(data, mine)
        val other = assertFailsWith<ReaderException> { Format.openCore(file, someoneElse, null) }
        assertTrue(other.wrongAccount)
        // Not even claiming to be the right key: the cipher itself refuses.
        val liar = AccountKey(someoneElse.bytes, mine.id)
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(file, liar, null) }.wrongAccount)
    }

    @Test fun withoutAKeyItAsksForOne() {
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(Format.sealCore(data, mine), null, null) }.needsKey)
    }

    @Test fun theProgramHoldsNoKeyOfItsOwn() {
        // Every key made here is random or given; the same data sealed twice is different each time.
        assertFalse(Format.sealCore(data, mine).contentEquals(Format.sealCore(data, mine)))
    }

    @Test fun aBackupMadeByEmberOnAPhoneOpensWithTheKeyTheServerGave() {
        // A file made by Ember 0.4.0 (the scratch server's account) and the key that server reported for it.
        val file = javaClass.getResourceAsStream("/sample-backup.embkup")!!.readBytes()
        val key = AccountKey.parse("2pS0A8Ea5AD7f68Z9wO/eizjx0chDMDCOO8PIxPpfO0=")
        assertEquals("418b081b", Format.info(file).keyId)
        assertEquals("418b081b", key.id)
        val content = Format.openCore(file, key, null)
        assertTrue(content.contains("\"createdWith\":\"Ember\""))
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(file, someoneElse, null) }.wrongAccount)
    }

    @Test fun keysAreReadWithOrWithoutPaddingOrSpaces() {
        val text = java.util.Base64.getEncoder().encodeToString(mine.bytes)
        assertContentEquals(mine.bytes, AccountKey.parse(text).bytes)
        assertContentEquals(mine.bytes, AccountKey.parse(text.trimEnd('=')).bytes)
        assertContentEquals(mine.bytes, AccountKey.parse("  " + text.chunked(8).joinToString(" ") + "\n").bytes)
        assertFailsWith<ReaderException> { AccountKey.parse("not a key") }
    }

    @Test fun anOlderPlainBackupAndAPassphraseBackupStillOpen() {
        val plain = """{"ember_backup":1,"encrypted":false,"data":"{\"a\":1}"}""".toByteArray()
        assertEquals("""{"a":1}""", Format.openCore(plain, null, null))
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 40).toByte() }
        val k = Format.passphraseKey("correct horse", salt, 1000)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(k, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce)) }
        val b64 = java.util.Base64.getEncoder()
        val old = """{"ember_backup":1,"encrypted":true,"rounds":1000,"salt":"${b64.encodeToString(salt)}","nonce":"${b64.encodeToString(nonce)}","data":"${b64.encodeToString(c.doFinal(data.toByteArray()))}"}""".toByteArray()
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(old, null, null) }.needsPassphrase)
        assertEquals(data, Format.openCore(old, null, "correct horse"))
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(old, null, "wrong") }.needsPassphrase)
    }

    @Test fun notABackup() {
        assertFailsWith<ReaderException> { Format.openCore("hello".toByteArray(), null, null) }
    }

    @Test fun anArchiveOpensWithItsKeyAndShowsChatsAndFiles() {
        val cipher = Format.ArchiveCipher(mine.bytes)
        val chats = """{"madeAt":1000,"skipped":["x"],"chats":[{"id":"r1","name":"Alex","kind":"direct","encrypted":false,"members":["Me","Alex"],
            "messages":[{"id":"m1","from":"Alex","at":1761700000000,"text":"hello","attachments":[{"id":"a1","filename":"cat.png","mime":"image/png","size":3}]},
            {"id":"m2","from":"Me","at":1761700060000,"text":"","deleted":true,"attachments":[]}]}]}"""
        val zipFile = File.createTempFile("archive", ".zip").also { it.deleteOnExit() }
        ZipOutputStream(zipFile.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("archive.json", """{"ember_archive":1,"encrypted":true,"keyed":true,"keyId":"${mine.id}"}""".toByteArray())
            put("chats.json", cipher.seal(chats.toByteArray()))
            put("media/a1", cipher.seal(byteArrayOf(1, 2, 3)))
        }
        OpenArchive.open(zipFile, mine, null).use { a ->
            assertEquals(1, a.chats.size)
            assertEquals("Alex", a.chats[0].name)
            assertEquals(listOf("Me", "Alex"), a.chats[0].members)
            assertEquals(2, a.chats[0].messages.size)
            assertTrue(a.chats[0].messages[1].deleted)
            assertContentEquals(byteArrayOf(1, 2, 3), a.media("a1"))
            assertEquals(null, a.media("nope"))
            val text = asText(a.chats[0])
            assertTrue(text.contains("Alex: hello"))
            assertTrue(text.contains("attachment: cat.png"))
        }
        assertTrue(assertFailsWith<ReaderException> { OpenArchive.open(zipFile, null, null) }.needsKey)
        assertTrue(assertFailsWith<ReaderException> { OpenArchive.open(zipFile, someoneElse, null) }.wrongAccount)
        ZipFile(zipFile).use { assertTrue(Format.archiveInfo(it).keyed) }
    }

    @Test fun aCompressedArchiveAndACompressedBackupOpen() {
        val big = "x".repeat(20000)
        val file = Format.sealCore("""{"data":"$big"}""", mine)
        assertTrue(file.size < 5000) // sealed after compression, so it stays small
        assertEquals("""{"data":"$big"}""", Format.openCore(file, mine, null))

        val cipher = Format.ArchiveCipher(mine.bytes)
        val chats = """{"madeAt":1,"skipped":[],"chats":[{"id":"r1","name":"Alex","kind":"direct","encrypted":false,"members":[],"messages":[{"id":"m1","from":"A","at":1,"text":"hi","attachments":[{"id":"a1","filename":"n.txt","mime":"text/plain","size":5},{"id":"a2","filename":"p.png","mime":"image/png","size":3}]}]}]}"""
        val zipFile = File.createTempFile("archive2", ".zip").also { it.deleteOnExit() }
        ZipOutputStream(zipFile.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("archive.json", """{"ember_archive":1,"encrypted":true,"keyed":true,"keyId":"${mine.id}","compressed":true}""".toByteArray())
            put("chats.json", cipher.seal(Format.gzip(chats.toByteArray())))
            put("media/a1.gz", cipher.seal(Format.gzip("hello".toByteArray())))
            put("media/a2", cipher.seal(byteArrayOf(9, 8, 7)))
        }
        OpenArchive.open(zipFile, mine, null).use { a ->
            assertEquals("hi", a.chats[0].messages[0].text)
            assertContentEquals("hello".toByteArray(), a.media("a1"))
            assertContentEquals(byteArrayOf(9, 8, 7), a.media("a2"))
        }
    }

    @Test fun argon2KnownAnswer() {
        val salt = ByteArray(16) { it.toByte() }
        val k = PasswordWrap.derive("correct horse", salt, memoryKib = 1024, passes = 2, lanes = 1)
        // The same answer Ember's PasswordWrapTest expects: the two programs derive the same key from the same password.
        assertEquals("77237b8a217dfd77699617e3893817f9fa215bff881849f3c007154da0b4788d", k.joinToString("") { "%02x".format(it) })
    }

    @Test fun aBackupSealedUnderThePasswordOpensWithItAndOnlyIt() {
        val key = mine
        val env = PasswordWrap.wrap(key.bytes, "hunter2 correct horse")
        val file = Format.sealCore("""{"a":1}""", key).let { String(it) }.replace("\"gzip\":true", "\"gzip\":true,\"pw\":$env").toByteArray()
        assertEquals("""{"a":1}""", Format.openCore(file, null, null, "hunter2 correct horse"))
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(file, null, null, "wrong") }.needsPassword)
        assertTrue(assertFailsWith<ReaderException> { Format.openCore(file, null, null) }.needsPassword)
        assertEquals("""{"a":1}""", Format.openCore(file, key, null)) // the key itself still opens it
    }

    @Test fun serverAddressesAreTidied() {
        assertEquals("https://chat.example.com", Account.normalize("chat.example.com/"))
        assertEquals("https://chat.example.com", Account.normalize("http://chat.example.com"))
        assertEquals("http://localhost:18200", Account.normalize("http://localhost:18200"))
        assertEquals("http://192.168.1.5:8080", Account.normalize("http://192.168.1.5:8080/"))
    }
}
