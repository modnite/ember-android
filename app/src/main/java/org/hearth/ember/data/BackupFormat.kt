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

class BackupException(message: String, val needsPassphrase: Boolean = false) : Exception(message)

/**
 * The backup file: a small JSON document. Plain, it holds the data as is; encrypted, the data is sealed with AES-256-GCM under a key made
 * from the passphrase (PBKDF2-SHA256, 200,000 rounds). Pure Kotlin, so it is tested without a phone.
 */
object BackupFormat {
    private const val ROUNDS = 200_000
    private val b64 = Base64.getEncoder()
    private val ub64 = Base64.getDecoder()

    private fun key(passphrase: String, salt: ByteArray) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, ROUNDS, 256)).encoded, "AES")

    /** [data] is the backup's content as JSON text. A null or empty [passphrase] leaves it unencrypted. */
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
    fun open(file: ByteArray, passphrase: String?): String {
        val obj = parse(file)
        if (obj["encrypted"]?.jsonPrimitive?.boolean != true) return obj["data"]!!.jsonPrimitive.content
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
