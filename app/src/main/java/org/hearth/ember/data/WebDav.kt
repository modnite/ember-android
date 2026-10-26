package org.hearth.ember.data

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Just enough WebDAV for backups: put a file in a folder, and fetch the newest backup from it. Works with Nextcloud, ownCloud and most NAS boxes. */
class WebDav(private val client: OkHttpClient, url: String, private val user: String, private val pass: String) {
    private val base = url.trim().trimEnd('/')

    private fun request(target: String) = Request.Builder().url(target).header("Authorization", Credentials.basic(user, pass))

    private fun fail(code: Int, message: String): Nothing =
        throw BackupException(if (code == 401) "The server did not accept that username and password." else "The server answered $code $message.")

    fun upload(name: String, bytes: ByteArray): String {
        if (base.isEmpty()) throw BackupException("Set the WebDAV address first.")
        client.newCall(request("$base/$name").put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
        }
        return name
    }

    fun latest(): ByteArray {
        if (base.isEmpty()) throw BackupException("Set the WebDAV address first.")
        val listing = client.newCall(request("$base/").header("Depth", "1").method("PROPFIND", "".toRequestBody("text/xml".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
            r.body?.string().orEmpty()
        }
        val newest = Regex("""ember-backup-[0-9-]+\.embkup""").findAll(listing).map { it.value }.distinct().maxOrNull()
            ?: throw BackupException("No Ember backup found in that folder.")
        return client.newCall(request("$base/$newest").build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
            r.body!!.bytes()
        }
    }
}
