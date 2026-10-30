package org.hearth.ember.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer version of Ember that can be installed. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** Version numbers and the GitHub release answer, kept free of Android so they are tested on their own. */
object Versions {
    /** "v1.2.3-debug" and "1.2.3" are both [1, 2, 3]. */
    fun parse(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release as GitHub's "latest release" answer gives it: its version, notes and the .apk to download. Null when there is none. */
    fun release(json: String): UpdateInfo? {
        val obj = runCatching { EmberJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        val assets = (obj["assets"] as? JsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
        val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true } ?: return null
        return UpdateInfo(
            version = tag.removePrefix("v"),
            notes = obj["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            apkUrl = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null,
            size = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
        )
    }
}

/**
 * Looks for a newer Ember on the project's GitHub releases, downloads it and hands it to Android to install. Android itself refuses an update
 * that is not signed with the same key as the installed app, so a tampered file cannot replace Ember.
 */
class Updater(private val context: Context, private val client: OkHttpClient, private val repo: String, private val current: String) {
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()
    private val prefs = context.getSharedPreferences("ember_update", Context.MODE_PRIVATE)

    /** Tell the person with a notification when a newer Ember is out (on by default). */
    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) { prefs.edit().putBoolean("notify", v).apply() }

    /** The version a notification was last shown for, so each version is announced once. */
    var lastNotified: String
        get() = prefs.getString("lastNotified", "") ?: ""
        set(v) { prefs.edit().putString("lastNotified", v).apply() }

    /** The check, now. Returns the newer release or null (also null when the check could not be made: [error] says which). */
    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest").header("Accept", "application/vnd.github+json").build()
            val body = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(true).followSslRedirects(true).build().newCall(request).execute().use { r ->
                if (r.code == 404) return@use null
                if (!r.isSuccessful) throw BackupException("GitHub answered ${r.code}.")
                r.body?.string()
            }
            prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            val info = body?.let { Versions.release(it) }
            val newer = info?.takeIf { Versions.isNewer(it.version, current) }
            _available.value = newer
            newer
        }
    }

    /** A check at most about twice a day, quietly. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("checkedAt", 0) > 12 * 3600_000L) check()
    }

    /** Download the update, reporting how far along it is (0 to 1). */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        val file = File(dir, "Ember-${info.version}.apk")
        fetch(client, info.apkUrl, file, info.size, onProgress)
        file
    }

    /** Hand the file to Android's installer. The first time, Android asks to allow installs from Ember. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        /** Fetch [url] into [file]. GitHub answers a release download with a redirect to its file storage, so redirects are followed here (the app's own client does not follow them). */
        fun fetch(client: OkHttpClient, url: String, file: File, expected: Long, onProgress: (Float) -> Unit) {
            client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(true).followSslRedirects(true)
                .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw BackupException("The download failed (${r.code}).")
                    val total = (r.body?.contentLength() ?: expected).takeIf { it > 0 } ?: expected
                    r.body!!.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
        }
    }
}
