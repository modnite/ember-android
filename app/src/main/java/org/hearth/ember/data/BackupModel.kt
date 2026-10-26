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

/** How often a backup runs by itself. */
enum class Schedule(val label: String, val hours: Long) {
    Off("Off", 0), Daily("Daily", 24), Weekly("Weekly", 24 * 7), Monthly("Monthly", 24 * 30),
}

/** A place backups are kept: a folder Android can reach (this phone, an SD card, a network share through its app), or a WebDAV or Nextcloud folder. */
@Serializable
data class Destination(val id: String, val kind: String, val label: String, val uri: String, val user: String = "", val pass: String = "") {
    val isFolder get() = kind == KIND_FOLDER

    companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_DAV = "dav"
    }
}

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
class ArchiveCipher private constructor(private val key: SecretKeySpec?, private val salt: ByteArray?) {
    val encrypted get() = key != null

    /** What goes in the archive's plain header, so the passphrase can be checked later. */
    fun header(): JsonObject = buildJsonObject {
        put("ember_archive", 1)
        put("encrypted", encrypted)
        if (salt != null) { put("rounds", BackupFormat.ROUNDS); put("salt", java.util.Base64.getEncoder().encodeToString(salt)) }
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

        fun create(passphrase: String): ArchiveCipher {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            return ArchiveCipher(BackupFormat.deriveKey(passphrase, salt, BackupFormat.ROUNDS), salt)
        }

        fun from(header: JsonObject, passphrase: String?): ArchiveCipher {
            if (header["encrypted"]?.jsonPrimitive?.boolean != true) return plain()
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

    /** Encrypted by default: a backup holds the key that opens encrypted chats. */
    var encrypt: Boolean
        get() = prefs.getBoolean("encrypt", true)
        set(v) { prefs.edit().putBoolean("encrypt", v).apply(); changed() }

    var passphrase: String
        get() = prefs.getString("passphrase", "") ?: ""
        set(v) { prefs.edit().putString("passphrase", v).apply(); changed() }

    var includeHistory: Boolean
        get() = prefs.getBoolean("history", false)
        set(v) { prefs.edit().putBoolean("history", v).apply(); changed() }

    var includeMedia: Boolean
        get() = prefs.getBoolean("media", false)
        set(v) { prefs.edit().putBoolean("media", v).apply(); changed() }

    /** Scheduled backups wait for Wi-Fi (or any unmetered network) unless this is off. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) { prefs.edit().putBoolean("wifiOnly", v).apply(); changed() }

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

    /** The passphrase to seal with, or null for plain; a [BackupException] when encryption is on but no passphrase was set. */
    fun sealingPassphrase(): String? {
        if (!encrypt) return null
        if (passphrase.isEmpty()) throw BackupException("Encryption is on but no passphrase is set. Set one, or turn encryption off.")
        return passphrase
    }

}
