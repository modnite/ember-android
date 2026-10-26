package org.hearth.ember.data

import android.content.Context
import android.net.Uri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hearth.ember.EmberApp

/**
 * Backing up what lives only on this phone: the encryption key that opens the encrypted chats (without it a lost phone means those chats
 * cannot be read again), and the settings. Messages and files stay on the Hearth server, which is their backup. Goes to a file you choose
 * (any storage Android can reach, which includes network shares through their apps) or to a WebDAV or Nextcloud server.
 */
class BackupService(private val app: EmberApp, private val context: Context) {
    fun fileName(): String = "ember-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".embkup"

    private fun content(): String = buildJsonObject {
        put("createdWith", "Ember")
        put("server", app.store.server)
        put("userId", app.store.userId)
        put("e2eeSecret", app.store.e2eeSecret)
        put("settings", app.prefs.dump())
    }.toString()

    fun make(passphrase: String?): ByteArray = BackupFormat.seal(content(), passphrase)

    /** Put a backup back on this phone: the key first, then the settings. Returns a short description of what was restored. */
    suspend fun apply(file: ByteArray, passphrase: String?): String {
        val data = EmberJson.parseToJsonElement(BackupFormat.open(file, passphrase)).jsonObject
        val secret = data["e2eeSecret"]?.jsonPrimitive?.content.orEmpty()
        if (secret.isNotEmpty()) app.store.e2eeSecret = secret
        (data["settings"] as? JsonObject)?.let { app.prefs.restore(it) }
        app.notifier.createChannels()
        runCatching { app.e2ee.init(false) }
        return if (secret.isNotEmpty()) "Restored your encryption key and settings." else "Restored your settings."
    }

    fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Could not read that file.")

    fun writeUri(uri: Uri, bytes: ByteArray) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Could not write that file.")).use { it.write(bytes) }
    }

    // ------------------------------------------------------------------ WebDAV (Nextcloud, ownCloud, most NAS boxes)
    // No cookies: the sign-in cookie for the Hearth server must never travel to another server.
    private fun dav() = WebDav(app.client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(), app.store.webdavUrl, app.store.webdavUser, app.store.webdavPass)

    suspend fun uploadDav(bytes: ByteArray): String = withContext(Dispatchers.IO) { dav().upload(fileName(), bytes) }
    suspend fun downloadLatestDav(): ByteArray = withContext(Dispatchers.IO) { dav().latest() }
}
