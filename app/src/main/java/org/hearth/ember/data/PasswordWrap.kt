package org.hearth.ember.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Keeps the backup key under the account password. The key (32 random bytes) that seals a backup is itself sealed, with AES-256-GCM, under a
 * key made from the password by Argon2id (a slow, memory-hard hash, so guessing passwords is expensive) and a random salt. That sealed key, the
 * "envelope", is written into every backup's header, so the backup can be opened by anyone who knows the password, with no server and no key on
 * the server. The same format is read by the desktop Backup Reader, so both ends are tested against the same key.
 */
object PasswordWrap {
    const val MEMORY_KIB = 65_536
    const val PASSES = 3
    const val LANES = 1
    private val b64 = Base64.getEncoder()
    private val ub64 = Base64.getDecoder()

    /** The key the password makes (32 bytes), for a salt and cost settings. */
    fun derive(password: String, salt: ByteArray, memoryKib: Int = MEMORY_KIB, passes: Int = PASSES, lanes: Int = LANES): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib).withIterations(passes).withParallelism(lanes).withSalt(salt).build(),
        )
        val out = ByteArray(32)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }

    /** The envelope that holds [key] under [password]. */
    fun wrap(key: ByteArray, password: String, rnd: SecureRandom = SecureRandom()): JsonObject {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val sealed = gcm(Cipher.ENCRYPT_MODE, derive(password, salt), nonce).doFinal(key)
        return buildJsonObject {
            put("kdf", "argon2id"); put("m", MEMORY_KIB); put("t", PASSES); put("p", LANES)
            put("salt", b64.encodeToString(salt)); put("nonce", b64.encodeToString(nonce)); put("ct", b64.encodeToString(sealed))
        }
    }

    /** The backup key out of an envelope, or a [BackupException] saying the password was wrong. */
    fun unwrap(envelope: JsonObject, password: String): ByteArray {
        if (envelope["kdf"]?.jsonPrimitive?.content != "argon2id") throw BackupException("This backup uses a password scheme this version does not know.")
        return try {
            val key = derive(
                password, ub64.decode(envelope["salt"]!!.jsonPrimitive.content),
                envelope["m"]!!.jsonPrimitive.int.coerceIn(8, 1 shl 20), envelope["t"]!!.jsonPrimitive.int.coerceIn(1, 20), envelope["p"]!!.jsonPrimitive.int.coerceIn(1, 8),
            )
            gcm(Cipher.DECRYPT_MODE, key, ub64.decode(envelope["nonce"]!!.jsonPrimitive.content)).doFinal(ub64.decode(envelope["ct"]!!.jsonPrimitive.content))
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("That is not the password this backup was made under.", needsPassword = true)
        }
    }
}
