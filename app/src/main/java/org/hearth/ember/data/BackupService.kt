package org.hearth.ember.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hearth.ember.EmberApp

/**
 * Backing up what lives only on this phone: the encryption key that opens the encrypted chats (without it a lost phone means those chats
 * cannot be read again), the settings and your stickers and GIFs. Optionally also a readable copy of your chats and their files (the messages
 * themselves live on the Hearth server). It goes to every destination you set up, by hand or on a schedule, always sealed with a key that belongs to your account (see [BackupFormat]).
 */
class BackupService(private val app: EmberApp, private val context: Context) {
    val settings by lazy { BackupSettings(context) }

    private val favouriteLimit = 6L * 1024 * 1024

    private fun content(): String = buildJsonObject {
        put("createdWith", "Ember")
        put("server", app.store.server)
        put("userId", app.store.userId)
        put("e2eeSecret", app.store.e2eeSecret)
        put("settings", app.prefs.dump())
        put("stickers", favourites(app.stickers))
        put("gifs", favourites(app.gifs))
    }.toString()

    private fun favourites(store: Favourites): JsonArray {
        var total = 0L
        return buildJsonArray {
            for (f in store.items.value) {
                if (total + f.length() > favouriteLimit) break
                total += f.length()
                add(JsonPrimitive(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
            }
        }
    }

    fun make(key: BackupKey): ByteArray = BackupFormat.sealKeyed(content(), key.bytes, key.id, envelopeFor(key))

    /** The password envelope goes into a backup only when the key being used is the one it holds. */
    private fun envelopeFor(key: BackupKey): JsonObject? = settings.envelope?.takeIf { settings.localKey()?.id == key.id }

    /**
     * Put the backup key under the account password: the phone makes its own random key if it has none, and seals it under [password] (Argon2id) so
     * every backup carries it. With [verify], the password is first checked against the server, so a typo cannot lock backups.
     */
    suspend fun adoptPassword(password: String, verify: Boolean) = withContext(Dispatchers.Default) {
        if (password.isEmpty()) throw BackupException("Enter your account password.")
        if (verify) {
            val username = app.repo.me.value?.username ?: throw BackupException("Sign in first.")
            runCatching { app.api.login(username, password) }.getOrElse { throw BackupException("That is not your account password.") }
        }
        val key = settings.localKey() ?: BackupKey.of(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        settings.setLocal(key, PasswordWrap.wrap(key.bytes, password))
    }

    /**
     * The key that seals this account's backups. The server gives it to the signed-in owner and it is remembered here, so a scheduled backup
     * still runs when the server cannot be reached; with neither, there is no backup.
     */
    suspend fun key(): BackupKey {
        settings.localKey()?.let { if (settings.envelope != null) return it }
        val owner = app.store.server + "|" + app.store.userId
        val fetched = runCatching { app.api.backupKey() }.getOrNull()
        if (fetched != null) {
            val key = BackupKey(java.util.Base64.getDecoder().decode(fetched.key), fetched.id)
            settings.cacheKey(owner, key)
            return key
        }
        return settings.cachedKey(owner) ?: throw BackupException("Could not get your backup key from the server. Check the connection and try again.")
    }

    /** Put a backup back on this phone: the key first, then the settings and the stickers. Returns a short description of what was restored. */
    suspend fun apply(file: ByteArray, passphrase: String?, password: String? = null): String {
        val key = if (app.store.signedIn) runCatching { key() }.getOrNull() else null
        val data = EmberJson.parseToJsonElement(BackupFormat.open(file, passphrase, key?.bytes, key?.id, password)).jsonObject
        // A backup that was opened with the password hands its key to this phone, so new backups are sealed under the same key and password.
        if (!password.isNullOrEmpty() && BackupFormat.envelopeOf(file) != null) {
            runCatching {
                val k = BackupKey.of(BackupFormat.unwrapFile(file, password))
                settings.setLocal(k, PasswordWrap.wrap(k.bytes, password))
            }
        }
        val secret = data["e2eeSecret"]?.jsonPrimitive?.content.orEmpty()
        if (secret.isNotEmpty()) app.store.e2eeSecret = secret
        (data["settings"] as? JsonObject)?.let { app.prefs.restore(it) }
        var kept = 0
        for ((key, store) in listOf("stickers" to app.stickers, "gifs" to app.gifs)) (data[key] as? JsonArray)?.forEach {
            runCatching { store.add(Base64.decode(it.jsonPrimitive.content, Base64.NO_WRAP)); kept++ }
        }
        app.notifier.createChannels()
        runCatching { app.e2ee.init(false) }
        return buildString {
            append(if (secret.isNotEmpty()) "Restored your encryption key and settings" else "Restored your settings")
            if (kept == 1) append(" and 1 sticker or GIF") else if (kept > 1) append(" and $kept stickers and GIFs")
            append(".")
        }
    }

    fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Could not read that file.")

    fun writeUri(uri: Uri, bytes: ByteArray) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Could not write that file.")).use { it.write(bytes) }
    }

    fun target(d: Destination): BackupTarget = if (d.isFolder) FolderTarget(context, d) else DavTarget(app.client, d)

    private fun workDir() = File(context.cacheDir, "backups").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }

    /** Back up now to every destination. One destination failing does not stop the others; each gets its own result, remembered for the settings page. */
    suspend fun runNow(progress: (String) -> Unit = {}): List<Pair<Destination, BackupRun>> = withContext(Dispatchers.IO) {
        val destinations = settings.destinations
        if (destinations.isEmpty()) throw BackupException("Add a place to back up to first.")
        val key = key()
        val now = System.currentTimeMillis()
        val dir = workDir()
        try {
            progress("Preparing…")
            val core = File(dir, BackupNames.core(now)).also { it.writeBytes(make(key)) }
            // The archive is the big one. When no chat has anything new since the last complete one (and a week has not gone by) it is not made again.
            val stamp = archiveStamp()
            val unchanged = settings.includeHistory && stamp == settings.archiveStamp && stamp.isNotEmpty() && settings.lastRuns.values.all { it.ok }
            val archive = if (settings.includeHistory && !unchanged) File(dir, BackupNames.archive(now)).also {
                HistoryArchive(app).build(it, ArchiveCipher.keyed(key.bytes, key.id, envelopeFor(key)), settings.includeMedia, progress)
            } else null
            val results = destinations.map { d ->
                val run = runCatching {
                    progress("Saving to ${d.label}…")
                    val t = target(d)
                    t.put(core.name, core)
                    archive?.let { t.put(it.name, it) }
                    prune(t)
                    BackupRun(now, true, if (unchanged) "Backed up (chats unchanged)" else "Backed up", core.length() + (archive?.length() ?: 0L))
                }.getOrElse { BackupRun(now, false, it.message ?: "The backup failed.") }
                settings.record(d.id, run)
                d to run
            }
            if (archive != null && results.all { it.second.ok }) settings.archiveStamp = stamp
            results
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** A fingerprint of the chats: each one's newest message, the week, and whether files are included. A change of any of them means a new archive. */
    internal fun archiveStamp(): String {
        val rooms = app.repo.rooms.value.sortedBy { it.id }
        val text = rooms.joinToString("|") { r -> "${r.id}:${r.lastMessage?.id}:${r.lastMessage?.editedAt}:${r.lastMessage?.deletedAt}:${r.members.size}" } +
            "#${settings.includeMedia}#${System.currentTimeMillis() / (7L * 24 * 3600 * 1000)}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Keep only the newest few of each kind of file in a destination. */
    private fun prune(t: BackupTarget) {
        val names = t.list().map { it.name }
        for (kind in listOf(names.filter { BackupNames.isCore(it) }, names.filter { BackupNames.isArchive(it) })) {
            for (old in BackupNames.stale(kind, settings.keep)) runCatching { t.delete(old) }
        }
    }

    /** The backups found in a destination, newest first. */
    suspend fun backupsIn(d: Destination): List<RemoteEntry> = withContext(Dispatchers.IO) {
        target(d).list().filter { BackupNames.isCore(it.name) }.sortedByDescending { it.name }
    }

    suspend fun restoreFrom(d: Destination, name: String, passphrase: String?, password: String? = null): String {
        val bytes = withContext(Dispatchers.IO) {
            val file = File(workDir(), name)
            try { target(d).get(name, file); file.readBytes() } finally { file.delete() }
        }
        return apply(bytes, passphrase, password)
    }
}
