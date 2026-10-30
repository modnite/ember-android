package org.hearth.ember.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BackupException(message: String, val needsPassphrase: Boolean = false, val needsKey: Boolean = false, val needsPassword: Boolean = false) : Exception(message)

/**
 * The backup file: a small JSON document. Ember seals every backup (version 2) with AES-256-GCM under the key that belongs to the account, which
 * the Hearth server hands to the signed-in owner, so nobody types a password and nobody else can open it. Older files (version 1) were sealed
 * with a passphrase (PBKDF2-SHA256, 200,000 rounds) or left plain; they still open. Pure Kotlin, so it is tested without a phone.
 */
object BackupFormat {
    internal const val ROUNDS = 200_000
    private val b64 = Base64.getEncoder()
    private val ub64 = Base64.getDecoder()

    internal fun deriveKey(passphrase: String, salt: ByteArray, rounds: Int) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, rounds, 256)).encoded, "AES")

    private fun key(passphrase: String, salt: ByteArray) = deriveKey(passphrase, salt, ROUNDS)

    /** The version 2 file: [data] sealed under the account's own [key] (32 bytes), which says by [keyId] which key that was. */
    fun sealKeyed(data: String, key: ByteArray, keyId: String, pw: JsonObject? = null): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        // Compressed first (sealed data cannot be compressed afterwards): the settings and the stickers' text form shrink to about a third.
        return buildJsonObject {
            // Version 3 carries the key under the account password too (see PasswordWrap), so the password alone opens it.
            put("ember_backup", if (pw != null) 3 else 2); put("encrypted", true); put("keyed", true); put("keyId", keyId); put("gzip", true)
            if (pw != null) put("pw", pw)
            put("nonce", b64.encodeToString(nonce)); put("data", b64.encodeToString(cipher.doFinal(gzip(data.toByteArray()))))
        }.toString().toByteArray()
    }

    fun gzip(bytes: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    fun gunzip(bytes: ByteArray): ByteArray = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /** The format version a file says it is (1, 2 or 3). */
    fun parseVersion(file: ByteArray): Int = parse(file)["ember_backup"]!!.jsonPrimitive.int

    /** The password envelope of a version 3 file, or null. */
    fun envelopeOf(file: ByteArray): JsonObject? = parse(file)["pw"] as? JsonObject

    /** The key a version 3 file holds under [password]. */
    fun unwrapFile(file: ByteArray, password: String): ByteArray =
        PasswordWrap.unwrap(envelopeOf(file) ?: throw BackupException("This backup is not sealed under a password."), password)

    /** Which key sealed this file, or null for the older kinds. */
    fun keyIdOf(file: ByteArray): String? = parse(file)["keyId"]?.jsonPrimitive?.content

    /** [data] is the backup's content as JSON text. A null or empty [passphrase] leaves it unencrypted (the older kind; Ember no longer makes it). */
    fun seal(data: String, passphrase: String?): ByteArray {
        val out = if (passphrase.isNullOrEmpty()) buildJsonObject {
            put("ember_backup", 1); put("encrypted", false); put("data", data)
        } else {
            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val nonce = ByteArray(12).also(rnd::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, nonce)) }
            buildJsonObject {
                put("ember_backup", 1); put("encrypted", true); put("rounds", ROUNDS)
                put("salt", b64.encodeToString(salt)); put("nonce", b64.encodeToString(nonce))
                put("data", b64.encodeToString(cipher.doFinal(data.toByteArray())))
            }
        }
        return out.toString().toByteArray()
    }

    fun isEncrypted(file: ByteArray): Boolean = parse(file)["encrypted"]?.jsonPrimitive?.boolean == true

    private fun parse(file: ByteArray): JsonObject {
        val obj = runCatching { EmberJson.parseToJsonElement(String(file)).jsonObject }.getOrNull()
        if (obj == null || obj["ember_backup"] == null) throw BackupException("This is not an Ember backup.")
        return obj
    }

    /** The content, or a [BackupException] (asking for the passphrase, or saying it was wrong). */
    fun open(file: ByteArray, passphrase: String?, key: ByteArray? = null, keyId: String? = null, password: String? = null): String {
        val obj = parse(file)
        if (obj["encrypted"]?.jsonPrimitive?.boolean != true) return obj["data"]!!.jsonPrimitive.content
        if (obj["keyed"]?.jsonPrimitive?.boolean == true) {
            val envelope = obj["pw"] as? JsonObject
            // A password opens a version 3 file by itself; otherwise the key this phone already holds (or the server gave) does, if it is the right one.
            val own = if (envelope != null && !password.isNullOrEmpty()) PasswordWrap.unwrap(envelope, password) else null
            val use = own ?: key
            if (use == null) {
                if (envelope != null) throw BackupException("This backup is sealed under your account password.", needsPassword = true)
                throw BackupException("This backup is sealed with your account's key. Sign in first.", needsKey = true)
            }
            val key = use
            val keyId = if (own != null) null else keyId
            val sealedWith = obj["keyId"]?.jsonPrimitive?.content
            if (keyId != null && sealedWith != null && keyId != sealedWith) throw BackupException("This backup belongs to another account, so this one cannot open it.")
            return try {
                val nonce = ub64.decode(obj["nonce"]!!.jsonPrimitive.content)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
                val plain = cipher.doFinal(ub64.decode(obj["data"]!!.jsonPrimitive.content))
                String(if (obj["gzip"]?.jsonPrimitive?.boolean == true) gunzip(plain) else plain)
            } catch (e: Exception) {
                throw BackupException("This backup could not be opened: the key is not the one that sealed it, or the file is damaged.")
            }
        }
        if (passphrase.isNullOrEmpty()) throw BackupException("This backup is encrypted.", needsPassphrase = true)
        return try {
            val salt = ub64.decode(obj["salt"]!!.jsonPrimitive.content)
            val nonce = ub64.decode(obj["nonce"]!!.jsonPrimitive.content)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, nonce)) }
            String(cipher.doFinal(ub64.decode(obj["data"]!!.jsonPrimitive.content)))
        } catch (e: Exception) {
            throw BackupException("Wrong passphrase, or the file is damaged.", needsPassphrase = true)
        }
    }
}
