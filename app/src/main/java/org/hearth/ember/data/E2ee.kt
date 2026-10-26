package org.hearth.ember.data

import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/** What this phone knows about encryption: does it hold the group key, is it waiting to be let in, or has nobody set it up. */
enum class E2eeStatus { Unknown, None, Waiting, Ready }

@Serializable
data class WrapDto(val senderDevice: String = "", val keyId: String = "", val eph: String = "", val n: String = "", val ct: String = "")

@Serializable
data class DeviceRegistration(val wraps: List<WrapDto> = emptyList(), val hasGroupKey: Boolean = false)

@Serializable
data class DeviceDto(val id: String, val userId: String = "", val displayName: String = "", val publicKey: String, val createdAt: Long = 0, val keyIds: List<String> = emptyList())

@Serializable
data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

@Serializable
data class FileEnvelope(val v: Int = 2, val kid: String = "", val n: String = "", val mime: String? = null)

/**
 * Hearth's end-to-end encryption, version 2, the same as the web and Windows apps: every phone has its own X25519 key, one random group key is
 * handed to each phone wrapped to that phone's public key, and messages and files are sealed with XChaCha20-Poly1305 under keys derived from it.
 * The server only ever sees ciphertext. (docs/E2EE.md describes the scheme; the older shared-passphrase "v1" messages are not readable here.)
 */
class E2ee(private val store: SessionStore, private val api: Api) {
    private val random = SecureRandom()
    private val salt = "hearth-e2ee-v1".toByteArray()
    private val _status = MutableStateFlow(E2eeStatus.Unknown)
    val status: StateFlow<E2eeStatus> = _status.asStateFlow()

    /** Bumped whenever keys change, so screens that show message text redraw. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val keys = HashMap<String, ByteArray>() // key id -> group key
    private var latest: String? = null
    val latestKeyId: String? get() = latest

    private fun secret(): ByteArray {
        store.e2eeSecret.takeIf { it.isNotEmpty() }?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = X25519.generatePrivateKey()
        store.e2eeSecret = Base64.encodeToString(fresh, Base64.NO_WRAP)
        return fresh
    }

    private fun publicKey(): ByteArray = X25519.publicFromPrivate(secret())
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun ub64(s: String) = Base64.decode(s, Base64.DEFAULT)

    private fun hkdf(ikm: ByteArray, info: String) = Hkdf.computeHkdf("HmacSha256", ikm, salt, info.toByteArray(), 32)

    private fun keyId(group: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(group).joinToString("") { "%02x".format(it) }.take(12)

    private fun seal(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val out = XChaCha20Poly1305(key).encrypt(plaintext, ByteArray(0))
        return out.copyOfRange(0, 24) to out.copyOfRange(24, out.size)
    }

    private fun open(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray =
        XChaCha20Poly1305(key).decrypt(nonce + ct, ByteArray(0))

    private fun wrap(recipientPublic: ByteArray, group: ByteArray): WrapDto {
        val eph = X25519.generatePrivateKey()
        val shared = X25519.computeSharedSecret(eph, recipientPublic)
        val wrapKey = hkdf(shared, "hearth:wrap")
        val (nonce, ct) = seal(wrapKey, group)
        return WrapDto(eph = b64(X25519.publicFromPrivate(eph)), n = b64(nonce), ct = b64(ct))
    }

    private fun unwrap(w: WrapDto): ByteArray? = runCatching {
        val shared = X25519.computeSharedSecret(secret(), ub64(w.eph))
        open(hkdf(shared, "hearth:wrap"), ub64(w.n), ub64(w.ct))
    }.getOrNull()

    /** Tell the server about this phone and learn which group keys it can open. Safe to call often. */
    suspend fun init(createIfMissing: Boolean = false) {
        val reg = api.e2eeRegister(store.deviceId, b64(publicKey()))
        var added = false
        for (w in reg.wraps) {
            val key = unwrap(w) ?: continue
            keys[w.keyId] = key
            latest = w.keyId
            added = true
        }
        if (!added && createIfMissing && !reg.hasGroupKey) {
            val group = ByteArray(32).also { random.nextBytes(it) }
            val kid = keyId(group)
            api.e2eeWrap(store.deviceId, store.deviceId, kid, wrap(publicKey(), group))
            keys[kid] = group
            latest = kid
            added = true
        }
        _status.value = when {
            keys.isNotEmpty() -> E2eeStatus.Ready
            reg.hasGroupKey -> E2eeStatus.Waiting
            else -> E2eeStatus.None
        }
        if (keys.isNotEmpty()) approveOthers()
        _revision.value++
    }

    /** Hand the key to any of this person's other devices that does not have it yet (a new phone being let in). */
    private suspend fun approveOthers() {
        val kid = latest ?: return
        val group = keys[kid] ?: return
        runCatching {
            for (d in api.e2eeDevices()) {
                if (kid in d.keyIds) continue
                api.e2eeWrap(store.deviceId, d.id, kid, wrap(ub64(d.publicKey), group))
            }
        }
    }

    val ready get() = latest != null

    private fun roomKey(kid: String?, roomId: String, purpose: String): ByteArray? {
        val group = keys[kid ?: latest] ?: return null
        return hkdf(group, "hearth:$purpose:$roomId")
    }

    fun isEncrypted(body: String) = body.startsWith("enc:v2:")

    /** The words of a message, or null when this phone cannot read them (no key yet, or an old-format message). */
    fun decryptText(roomId: String, body: String): String? {
        if (!body.startsWith("enc:v2:")) return null
        val parts = body.split(':')
        if (parts.size != 5) return null
        val key = roomKey(parts[2], roomId, "chat") ?: return null
        return runCatching { String(open(key, ub64(parts[3]), ub64(parts[4]))) }.getOrNull()
    }

    fun encryptText(roomId: String, text: String): String {
        val kid = latest ?: throw ApiException(0, "Encryption is not set up on this phone yet.")
        val key = roomKey(kid, roomId, "chat")!!
        val (nonce, ct) = seal(key, text.toByteArray())
        return "enc:v2:$kid:${b64(nonce)}:${b64(ct)}"
    }

    /** Seal a file. Returns the envelope (base64 JSON, sent beside the upload) and the ciphertext. */
    fun sealFile(roomId: String, data: ByteArray, mime: String): Pair<String, ByteArray> {
        val kid = latest ?: throw ApiException(0, "Encryption is not set up on this phone yet.")
        val key = roomKey(kid, roomId, "file")!!
        val (nonce, ct) = seal(key, data)
        val envelope = Json.encodeToString(FileEnvelope.serializer(), FileEnvelope(2, kid, b64(nonce), mime))
        return b64(envelope.toByteArray()) to ct
    }

    fun openFile(roomId: String, envelope: String, data: ByteArray): ByteArray? = runCatching {
        val env = Json { ignoreUnknownKeys = true }.decodeFromString(FileEnvelope.serializer(), String(ub64(envelope)))
        val key = roomKey(env.kid, roomId, "file") ?: return null
        open(key, ub64(env.n), data)
    }.getOrNull()

    fun mimeOf(envelope: String): String? = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(FileEnvelope.serializer(), String(ub64(envelope))).mime
    }.getOrNull()

    /** The key the call media is sealed with (LiveKit end-to-end encryption), as the web app derives it. */
    fun mediaKey(roomId: String): ByteArray? = roomKey(latest, roomId, "media")

    fun clear() {
        keys.clear()
        latest = null
        _status.value = E2eeStatus.Unknown
        _revision.value++
    }
}
