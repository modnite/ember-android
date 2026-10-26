package org.hearth.ember.data

import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Just enough WebDAV for backups: put, list, fetch and delete files in a folder. Works with Nextcloud, ownCloud and most NAS boxes. */
class WebDav(private val client: OkHttpClient, url: String, private val user: String, private val pass: String) {
    private val base = url.trim().trimEnd('/')

    private fun request(target: String) = Request.Builder().url(target).header("Authorization", Credentials.basic(user, pass))

    private fun fail(code: Int, message: String): Nothing =
        throw BackupException(if (code == 401) "The server did not accept that username and password." else "The server answered $code $message.")

    private fun need() { if (base.isEmpty()) throw BackupException("Set the WebDAV address first.") }

    fun upload(name: String, bytes: ByteArray): String {
        need()
        client.newCall(request("$base/$name").put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
        }
        return name
    }

    /** A file streamed from disk (a big archive never sits in memory). */
    fun put(name: String, file: File): String {
        need()
        client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
            .newCall(request("$base/$name").put(file.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) fail(r.code, r.message)
            }
        return name
    }

    /** Everything in the folder with its size and time, where the server says them. */
    fun list(): List<RemoteEntry> {
        need()
        val xml = client.newCall(request("$base/").header("Depth", "1").method("PROPFIND", "".toRequestBody("text/xml".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
            r.body?.string().orEmpty()
        }
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        return Regex("""<[^>]*response[^>]*>([\s\S]*?)</[^>]*response>""").findAll(xml).mapNotNull { block ->
            val body = block.groupValues[1]
            val href = Regex("""<[^>]*href[^>]*>([^<]+)<""").find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            if (href.endsWith("/")) return@mapNotNull null
            val name = runCatching { URLDecoder.decode(href.substringAfterLast('/'), "UTF-8") }.getOrDefault(href.substringAfterLast('/'))
            val size = Regex("""getcontentlength[^>]*>(\d+)<""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val modified = Regex("""getlastmodified[^>]*>([^<]+)<""").find(body)?.groupValues?.get(1)?.let { runCatching { date.parse(it.trim())?.time }.getOrNull() } ?: 0L
            RemoteEntry(name, modified, size)
        }.distinctBy { it.name }.toList()
    }

    fun get(name: String, to: File) {
        need()
        client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request("$base/$name").build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.message)
            r.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }

    fun delete(name: String) {
        need()
        client.newCall(request("$base/$name").delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) fail(r.code, r.message)
        }
    }

    fun latest(): ByteArray {
        need()
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
