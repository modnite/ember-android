package org.hearth.ember.reader

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Why a file could not be opened. [needsKey] and [needsPassphrase] say what to ask for; [wrongAccount] means a key was given but it is not this file's. */
class ReaderException(message: String, val needsKey: Boolean = false, val needsPassphrase: Boolean = false, val wrongAccount: Boolean = false, val needsPassword: Boolean = false) : Exception(message)

/** What a key can be: made from an account (it carries the short fingerprint the server reports) or just pasted. */
class AccountKey(val bytes: ByteArray, val id: String?) {
    init { require(bytes.size == 32) { "A backup key is 32 bytes." } }

    companion object {
        /** The key as Ember shows it (Base64, with or without padding). */
        fun parse(text: String): AccountKey {
            val clean = text.trim().replace("\\s".toRegex(), "")
            val raw = runCatching { Base64.getDecoder().decode(clean.padEnd((clean.length + 3) / 4 * 4, '=')) }.getOrNull()
            if (raw == null || raw.size != 32) throw ReaderException("That is not a backup key. It is 43 letters and numbers, as shown in Ember under Backup and restore, Backup key.")
            return AccountKey(raw, fingerprint(raw))
        }

        fun fingerprint(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The two kinds of file Ember makes, read here. Nothing in this program can open a backup without the key of the account that made it: there is
 * no key built into the program, and the key is only ever given by that account's own server (after signing in) or typed by its owner.
 */
/** The backup key held under the account password: Argon2id, then AES-256-GCM (the same format Ember writes; see Ember's PasswordWrap). */
object PasswordWrap {
    fun derive(password: String, salt: ByteArray, memoryKib: Int = 65_536, passes: Int = 3, lanes: Int = 1): ByteArray {
        val g = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        g.init(
            org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(memoryKib).withIterations(passes).withParallelism(lanes).withSalt(salt).build(),
        )
        return ByteArray(32).also { g.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }

    fun wrap(key: ByteArray, password: String): JsonObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, nonce)) }.doFinal(key)
        val e = Base64.getEncoder()
        return buildJsonObject { put("kdf", "argon2id"); put("m", 65536); put("t", 3); put("p", 1); put("salt", e.encodeToString(salt)); put("nonce", e.encodeToString(nonce)); put("ct", e.encodeToString(sealed)) }
    }

    /** The key in an envelope, or a [ReaderException] saying the password was wrong. */
    fun unwrap(envelope: JsonObject, password: String): AccountKey {
        if (envelope["kdf"]?.jsonPrimitive?.content != "argon2id") throw ReaderException("This backup uses a password scheme this version does not know.")
        val d = Base64.getDecoder()
        return try {
            val k = derive(password, d.decode(envelope["salt"]!!.jsonPrimitive.content), envelope["m"]!!.jsonPrimitive.int.coerceIn(8, 1 shl 20), envelope["t"]!!.jsonPrimitive.int.coerceIn(1, 20), envelope["p"]!!.jsonPrimitive.int.coerceIn(1, 8))
            val key = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, d.decode(envelope["nonce"]!!.jsonPrimitive.content))) }.doFinal(d.decode(envelope["ct"]!!.jsonPrimitive.content))
            AccountKey(key, AccountKey.fingerprint(key))
        } catch (e: Exception) {
            throw ReaderException("That is not the password this backup was made under.", needsPassword = true)
        }
    }
}

object Format {
    private val json = Json { ignoreUnknownKeys = true }
    private val ub64 = Base64.getDecoder()
    private val b64 = Base64.getEncoder()

    private fun parse(file: ByteArray): JsonObject {
        val obj = runCatching { json.parseToJsonElement(String(file)).jsonObject }.getOrNull()
        if (obj == null || obj["ember_backup"] == null) throw ReaderException("This is not an Ember backup file.")
        return obj
    }

    /** What the file says about itself without opening it. */
    class Info(val version: Int, val encrypted: Boolean, val keyed: Boolean, val keyId: String?)

    fun info(file: ByteArray): Info {
        val o = parse(file)
        return Info(o["ember_backup"]!!.jsonPrimitive.int, o["encrypted"]?.jsonPrimitive?.boolean == true, o["keyed"]?.jsonPrimitive?.boolean == true, o["keyId"]?.jsonPrimitive?.contentOrNull)
    }

    fun gzip(bytes: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    fun gunzip(bytes: ByteArray): ByteArray = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }

    fun passphraseKey(passphrase: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, rounds, 256)).encoded

    /** The backup's content (JSON text). */
    fun openCore(file: ByteArray, key: AccountKey?, passphrase: String?, password: String? = null): String {
        val o = parse(file)
        if (o["encrypted"]?.jsonPrimitive?.boolean != true) return o["data"]!!.jsonPrimitive.content
        if (o["keyed"]?.jsonPrimitive?.boolean == true) {
            val envelope = o["pw"] as? JsonObject
            val own = if (envelope != null && !password.isNullOrEmpty()) PasswordWrap.unwrap(envelope, password) else null
            val key = own ?: key
            if (key == null) {
                if (envelope != null) throw ReaderException("This backup is sealed under your account password.", needsPassword = true)
                throw ReaderException("This backup is sealed with its account's key.", needsKey = true)
            }
            val sealedWith = o["keyId"]?.jsonPrimitive?.contentOrNull
            if (sealedWith != null && key.id != null && key.id != sealedWith) throw ReaderException("This backup belongs to another account. That key cannot open it.", wrongAccount = true)
            return try {
                val plain = gcm(Cipher.DECRYPT_MODE, key.bytes, ub64.decode(o["nonce"]!!.jsonPrimitive.content)).doFinal(ub64.decode(o["data"]!!.jsonPrimitive.content))
                String(if (o["gzip"]?.jsonPrimitive?.boolean == true) gunzip(plain) else plain)
            } catch (e: Exception) {
                throw ReaderException("That key does not open this backup, or the file is damaged.", wrongAccount = true)
            }
        }
        if (passphrase.isNullOrEmpty()) throw ReaderException("This older backup was sealed with a passphrase.", needsPassphrase = true)
        return try {
            val k = passphraseKey(passphrase, ub64.decode(o["salt"]!!.jsonPrimitive.content), o["rounds"]?.jsonPrimitive?.int ?: 200_000)
            String(gcm(Cipher.DECRYPT_MODE, k, ub64.decode(o["nonce"]!!.jsonPrimitive.content)).doFinal(ub64.decode(o["data"]!!.jsonPrimitive.content)))
        } catch (e: Exception) {
            throw ReaderException("Wrong passphrase, or the file is damaged.", needsPassphrase = true)
        }
    }

    /** Seals content the way Ember does (used by the tests, and so the format is written down in code). */
    fun sealCore(data: String, key: AccountKey): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        return buildJsonObject {
            put("ember_backup", 2); put("encrypted", true); put("keyed", true); put("keyId", key.id ?: ""); put("gzip", true)
            put("nonce", b64.encodeToString(nonce))
            put("data", b64.encodeToString(gcm(Cipher.ENCRYPT_MODE, key.bytes, nonce).doFinal(gzip(data.toByteArray()))))
        }.toString().toByteArray()
    }

    // ------------------------------------------------------------------ the history archive (a zip)

    /** Opens the files inside an archive: each is its 12 byte nonce followed by the sealed data. */
    class ArchiveCipher(private val key: ByteArray?) {
        fun open(data: ByteArray): ByteArray {
            val k = key ?: return data
            if (data.size < 28) throw ReaderException("A file in the archive is damaged.")
            return try { gcm(Cipher.DECRYPT_MODE, k, data.copyOfRange(0, 12)).doFinal(data, 12, data.size - 12) }
            catch (e: Exception) { throw ReaderException("That key does not open this archive, or it is damaged.", wrongAccount = true) }
        }

        fun seal(data: ByteArray): ByteArray {
            val k = key ?: return data
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            return nonce + gcm(Cipher.ENCRYPT_MODE, k, nonce).doFinal(data)
        }
    }

    class ArchiveInfo(val encrypted: Boolean, val keyed: Boolean, val keyId: String?, val header: JsonObject)

    fun archiveInfo(zip: ZipFile): ArchiveInfo {
        val entry = zip.getEntry("archive.json") ?: throw ReaderException("This zip is not an Ember archive.")
        val h = json.parseToJsonElement(String(zip.getInputStream(entry).readBytes())).jsonObject
        if (h["ember_archive"] == null) throw ReaderException("This zip is not an Ember archive.")
        return ArchiveInfo(h["encrypted"]?.jsonPrimitive?.boolean == true, h["keyed"]?.jsonPrimitive?.boolean == true, h["keyId"]?.jsonPrimitive?.contentOrNull, h)
    }

    fun archiveCipher(info: ArchiveInfo, key: AccountKey?, passphrase: String?, password: String? = null): ArchiveCipher {
        if (!info.encrypted) return ArchiveCipher(null)
        if (info.keyed) {
            val envelope = info.header["pw"] as? JsonObject
            val own = if (envelope != null && !password.isNullOrEmpty()) PasswordWrap.unwrap(envelope, password) else null
            val key = own ?: key
            if (key == null) {
                if (envelope != null) throw ReaderException("This archive is sealed under your account password.", needsPassword = true)
                throw ReaderException("This archive is sealed with its account's key.", needsKey = true)
            }
            if (info.keyId != null && key.id != null && key.id != info.keyId) throw ReaderException("This archive belongs to another account. That key cannot open it.", wrongAccount = true)
            return ArchiveCipher(key.bytes)
        }
        if (passphrase.isNullOrEmpty()) throw ReaderException("This older archive was sealed with a passphrase.", needsPassphrase = true)
        val salt = ub64.decode(info.header["salt"]!!.jsonPrimitive.content)
        return ArchiveCipher(passphraseKey(passphrase, salt, info.header["rounds"]?.jsonPrimitive?.int ?: 200_000))
    }
}

// ---------------------------------------------------------------------- what an archive holds

class Attachment(val id: String, val filename: String, val mime: String, val size: Long)
class Message(val id: String, val from: String, val at: Long, val text: String, val editedAt: Long?, val deleted: Boolean, val attachments: List<Attachment>)
class Chat(val id: String, val name: String, val kind: String, val encrypted: Boolean, val members: List<String>, val messages: List<Message>)

/** An opened history archive: the chats read out of chats.json, and the files in media/ opened on demand. */
class OpenArchive(private val zip: ZipFile, private val cipher: Format.ArchiveCipher, private val compressed: Boolean = false) : AutoCloseable {
    val chats: List<Chat>
    val skipped: List<String>
    val madeAt: Long

    init {
        val entry = zip.getEntry("chats.json") ?: throw ReaderException("This archive has no chats.json.")
        val opened = cipher.open(zip.getInputStream(entry).readBytes())
        val root = Json.parseToJsonElement(String(if (compressed) Format.gunzip(opened) else opened)).jsonObject
        madeAt = root["madeAt"]?.jsonPrimitive?.long ?: 0L
        skipped = root["skipped"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        chats = (root["chats"] as? JsonArray).orEmpty().map { c ->
            val o = c.jsonObject
            Chat(
                o["id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.contentOrNull ?: "Chat", o["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                o["encrypted"]?.jsonPrimitive?.boolean == true, o["members"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                (o["messages"] as? JsonArray).orEmpty().map { m ->
                    val mo = m.jsonObject
                    Message(
                        mo["id"]!!.jsonPrimitive.content, mo["from"]?.jsonPrimitive?.contentOrNull ?: "", mo["at"]?.jsonPrimitive?.long ?: 0L,
                        mo["text"]?.jsonPrimitive?.contentOrNull ?: "", mo["editedAt"]?.jsonPrimitive?.long, mo["deleted"]?.jsonPrimitive?.boolean == true,
                        (mo["attachments"] as? JsonArray).orEmpty().map { a ->
                            val ao = a.jsonObject
                            Attachment(ao["id"]!!.jsonPrimitive.content, ao["filename"]?.jsonPrimitive?.contentOrNull ?: "file", ao["mime"]?.jsonPrimitive?.contentOrNull ?: "", ao["size"]?.jsonPrimitive?.long ?: 0L)
                        },
                    )
                },
            )
        }
    }

    /** The bytes of an attachment, or null when the archive was made without the pictures and files. */
    fun media(id: String): ByteArray? {
        zip.getEntry("media/$id.gz")?.let { return Format.gunzip(cipher.open(zip.getInputStream(it).readBytes())) }
        return zip.getEntry("media/$id")?.let { cipher.open(zip.getInputStream(it).readBytes()) }
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File, key: AccountKey?, passphrase: String?, password: String? = null): OpenArchive {
            val zip = ZipFile(file)
            return try {
                val info = Format.archiveInfo(zip)
                OpenArchive(zip, Format.archiveCipher(info, key, passphrase, password), info.header["compressed"]?.jsonPrimitive?.boolean == true)
            } catch (e: Throwable) { zip.close(); throw e }
        }
    }
}
