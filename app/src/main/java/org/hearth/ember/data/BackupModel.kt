package org.hearth.ember.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** How often a backup runs by itself (at the time of day set in [BackupSettings.atMinute]). */
enum class Schedule(val label: String, val hours: Long) {
    Off("Off", 0), Daily("Daily", 24), Weekly("Weekly", 24 * 7), Monthly("Monthly", 24 * 30),
}

/** A place backups are kept: a folder Android can reach (this phone, an SD card, a network share through its app), or a WebDAV or Nextcloud folder. */
@Serializable
data class Destination(val id: String, val kind: String, val label: String, val uri: String, val user: String = "", val pass: String = "", val extra: String = "") {
    val isFolder get() = kind == KIND_FOLDER

    companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_DAV = "dav"
        /** The backups kept on the Hearth server this phone is signed in to. */
        const val KIND_SERVER = "server"
        const val KIND_S3 = "s3"
        const val KIND_FTP = "ftp"
        const val KIND_SFTP = "sftp"
    }
}

/** The key that seals an account's backups: 32 bytes and a short fingerprint saying which key it is. */
class BackupKey(val bytes: ByteArray, val id: String) {
    companion object {
        /** The fingerprint is the first four bytes of the key's SHA-256, in hex (the same the server reports). */
        fun of(bytes: ByteArray) = BackupKey(bytes, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) })
    }

    /** The key as text, to keep somewhere safe or to give the desktop reader. */
    val text: String get() = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
}

@Serializable
class BackupKeyResponse(val key: String, val id: String)

/** What the last backup to a destination did. */
@Serializable
data class BackupRun(val at: Long, val ok: Boolean, val message: String, val bytes: Long = 0)

/** A file found in a backup destination. */
data class RemoteEntry(val name: String, val modified: Long, val size: Long)

/** The names backups are given, and which of them to keep. Names carry the time, so the newest sort last. */
object BackupNames {
    const val CORE_PREFIX = "ember-backup-"
    const val CORE_SUFFIX = ".embkup"
    const val ARCHIVE_PREFIX = "ember-archive-"
    const val ARCHIVE_SUFFIX = ".zip"

    fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))
    fun core(ms: Long) = CORE_PREFIX + stamp(ms) + CORE_SUFFIX
    fun archive(ms: Long) = ARCHIVE_PREFIX + stamp(ms) + ARCHIVE_SUFFIX
    fun isCore(name: String) = name.startsWith(CORE_PREFIX) && name.endsWith(CORE_SUFFIX)
    fun isArchive(name: String) = name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX)

    /** Of these names (all of one kind) the ones to delete so that only the [keep] newest stay. */
    fun stale(names: List<String>, keep: Int): List<String> = names.sortedDescending().drop(keep.coerceAtLeast(1))
}

/**
 * Seals the files inside a history archive one by one (AES-256-GCM, a fresh nonce each), under one key made from the passphrase, so a large
 * archive never has to sit in memory whole. Without a passphrase the files are kept as they are.
 */
class ArchiveCipher private constructor(private val key: SecretKeySpec?, private val salt: ByteArray?, private val keyId: String? = null, private val pw: JsonObject? = null) {
    val encrypted get() = key != null

    /** What goes in the archive's plain header, so the passphrase can be checked later. */
    fun header(): JsonObject = buildJsonObject {
        put("ember_archive", 1)
        put("encrypted", encrypted)
        if (salt != null) { put("rounds", BackupFormat.ROUNDS); put("salt", java.util.Base64.getEncoder().encodeToString(salt)) }
        if (keyId != null) { put("keyed", true); put("keyId", keyId) }
        if (pw != null) put("pw", pw)
        // chats.json (and text files, named media/<id>.gz) are compressed before they are sealed.
        put("compressed", true)
    }

    fun seal(data: ByteArray): ByteArray {
        val k = key ?: return data
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce)) }
        return nonce + cipher.doFinal(data)
    }

    fun open(data: ByteArray): ByteArray {
        val k = key ?: return data
        if (data.size < 28) throw BackupException("The file is damaged.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        return try { cipher.doFinal(data, 12, data.size - 12) } catch (e: Exception) { throw BackupException("Wrong passphrase, or the file is damaged.", needsPassphrase = true) }
    }

    companion object {
        fun plain() = ArchiveCipher(null, null)

        /** Sealed under the account's own key (see [BackupFormat]): no passphrase. */
        fun keyed(key: ByteArray, keyId: String, pw: JsonObject? = null) = ArchiveCipher(SecretKeySpec(key, "AES"), null, keyId, pw)

        fun create(passphrase: String): ArchiveCipher {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            return ArchiveCipher(BackupFormat.deriveKey(passphrase, salt, BackupFormat.ROUNDS), salt)
        }

        fun from(header: JsonObject, passphrase: String?, key: ByteArray? = null, password: String? = null): ArchiveCipher {
            if (header["encrypted"]?.jsonPrimitive?.boolean != true) return plain()
            if (header["keyed"]?.jsonPrimitive?.boolean == true) {
                val envelope = header["pw"] as? JsonObject
                val use = if (envelope != null && !password.isNullOrEmpty()) PasswordWrap.unwrap(envelope, password) else key
                if (use == null) {
                    if (envelope != null) throw BackupException("This archive is sealed under your account password.", needsPassword = true)
                    throw BackupException("This archive is sealed with your account's key. Sign in first.", needsKey = true)
                }
                return ArchiveCipher(SecretKeySpec(use, "AES"), null, header["keyId"]?.jsonPrimitive?.content)
            }
            if (passphrase.isNullOrEmpty()) throw BackupException("This archive is encrypted.", needsPassphrase = true)
            val salt = java.util.Base64.getDecoder().decode(header["salt"]!!.jsonPrimitive.content)
            val rounds = header["rounds"]?.jsonPrimitive?.int ?: BackupFormat.ROUNDS
            return ArchiveCipher(BackupFormat.deriveKey(passphrase, salt, rounds), salt)
        }
    }
}

/**
 * How backups are set up, kept in the same encrypted store as the sign-in: where they go, how often, whether they are encrypted (and with what
 * passphrase, kept here so a scheduled backup can run unattended), what they include, how many to keep and what the last run did.
 */
class BackupSettings(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "ember_backup", key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (_: Exception) {
        context.deleteSharedPreferences("ember_backup")
        context.getSharedPreferences("ember_backup_plain", Context.MODE_PRIVATE)
    }
    private val json = EmberJson
    private val _revision = MutableStateFlow(0)

    /** Changes whenever anything here does, so screens can redraw. */
    val revision: StateFlow<Int> = _revision.asStateFlow()
    private fun changed() { _revision.value++ }

    var schedule: Schedule
        get() = Schedule.entries.firstOrNull { it.name == prefs.getString("schedule", null) } ?: Schedule.Off
        set(v) { prefs.edit().putString("schedule", v.name).apply(); changed() }

    /** The time of day an automatic backup is due, in minutes after midnight (the default is 03:00). Android may run it a little later. */
    var atMinute: Int
        get() = prefs.getInt("atMinute", 3 * 60).coerceIn(0, 24 * 60 - 1)
        set(v) { prefs.edit().putInt("atMinute", v.coerceIn(0, 24 * 60 - 1)).apply(); changed() }

    /** The key this phone made for its own backups (see PasswordWrap), and the envelope that holds it under the account password. Both stay here. */
    fun localKey(): BackupKey? {
        val b64 = prefs.getString("localKey", null) ?: return null
        return runCatching { BackupKey.of(java.util.Base64.getDecoder().decode(b64)) }.getOrNull()
    }

    val envelope: JsonObject?
        get() = prefs.getString("envelope", null)?.let { runCatching { EmberJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    fun setLocal(key: BackupKey, envelope: JsonObject) {
        prefs.edit().putString("localKey", java.util.Base64.getEncoder().encodeToString(key.bytes)).putString("envelope", envelope.toString()).apply()
        changed()
    }

    /** The account's backup key, as last fetched from the server, with who it belongs to ("server|user"), so a backup can run without the server. */
    fun cachedKey(owner: String): BackupKey? {
        if (prefs.getString("keyOwner", null) != owner) return null
        val b64 = prefs.getString("keyB64", null) ?: return null
        val id = prefs.getString("keyId", null) ?: return null
        return runCatching { BackupKey(java.util.Base64.getDecoder().decode(b64), id) }.getOrNull()
    }

    fun cacheKey(owner: String, key: BackupKey) {
        prefs.edit().putString("keyOwner", owner).putString("keyB64", java.util.Base64.getEncoder().encodeToString(key.bytes)).putString("keyId", key.id).apply()
    }

    /** Only for opening older backups that were sealed with a passphrase; new ones are sealed with the account's key. */
    var passphrase: String
        get() = prefs.getString("passphrase", "") ?: ""
        set(v) { prefs.edit().putString("passphrase", v).apply(); changed() }

    /** Chats are in a backup unless the person switched them off (from 0.9.1; before it the switch started off, so a backup was only the key, settings and stickers). */
    var includeHistory: Boolean
        get() = prefs.getBoolean("history", true)
        set(v) { prefs.edit().putBoolean("history", v).apply(); changed() }

    var includeMedia: Boolean
        get() = prefs.getBoolean("media", false)
        set(v) { prefs.edit().putBoolean("media", v).apply(); changed() }

    /** Scheduled backups wait for Wi-Fi (or any unmetered network) unless this is off. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) { prefs.edit().putBoolean("wifiOnly", v).apply(); changed() }

    /** What the chats looked like when the last complete archive was made (see BackupService), so an unchanged day costs nothing. */
    var archiveStamp: String
        get() = prefs.getString("archiveStamp", "") ?: ""
        set(v) { prefs.edit().putString("archiveStamp", v).apply() }

    var keep: Int
        get() = prefs.getInt("keep", 5).coerceIn(1, 30)
        set(v) { prefs.edit().putInt("keep", v.coerceIn(1, 30)).apply(); changed() }

    var destinations: List<Destination>
        get() = runCatching { json.decodeFromString(ListSerializer(Destination.serializer()), prefs.getString("destinations", "[]") ?: "[]") }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("destinations", json.encodeToString(ListSerializer(Destination.serializer()), v)).apply(); changed() }

    var lastRuns: Map<String, BackupRun>
        get() = runCatching { json.decodeFromString(MapSerializer(String.serializer(), BackupRun.serializer()), prefs.getString("runs", "{}") ?: "{}") }.getOrDefault(emptyMap())
        set(v) { prefs.edit().putString("runs", json.encodeToString(MapSerializer(String.serializer(), BackupRun.serializer()), v)).apply(); changed() }

    fun record(destination: String, run: BackupRun) { lastRuns = lastRuns + (destination to run) }


}
