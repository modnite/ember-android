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
 * themselves live on the Hearth server). It goes to every destination you set up, by hand or on a schedule, encrypted with a passphrase or plain.
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

    fun make(passphrase: String?): ByteArray = BackupFormat.seal(content(), passphrase)

    /** Put a backup back on this phone: the key first, then the settings and the stickers. Returns a short description of what was restored. */
    suspend fun apply(file: ByteArray, passphrase: String?): String {
        val data = EmberJson.parseToJsonElement(BackupFormat.open(file, passphrase)).jsonObject
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
        val passphrase = settings.sealingPassphrase()
        val now = System.currentTimeMillis()
        val dir = workDir()
        try {
            progress("Preparing…")
            val core = File(dir, BackupNames.core(now)).also { it.writeBytes(make(passphrase)) }
            val archive = if (settings.includeHistory) File(dir, BackupNames.archive(now)).also {
                HistoryArchive(app).build(it, if (passphrase != null) ArchiveCipher.create(passphrase) else ArchiveCipher.plain(), settings.includeMedia, progress)
            } else null
            destinations.map { d ->
                val run = runCatching {
                    progress("Saving to ${d.label}…")
                    val t = target(d)
                    t.put(core.name, core)
                    archive?.let { t.put(it.name, it) }
                    prune(t)
                    BackupRun(now, true, "Backed up", core.length() + (archive?.length() ?: 0L))
                }.getOrElse { BackupRun(now, false, it.message ?: "The backup failed.") }
                settings.record(d.id, run)
                d to run
            }
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
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

    suspend fun restoreFrom(d: Destination, name: String, passphrase: String?): String {
        val bytes = withContext(Dispatchers.IO) {
            val file = File(workDir(), name)
            try { target(d).get(name, file); file.readBytes() } finally { file.delete() }
        }
        return apply(bytes, passphrase)
    }
}
