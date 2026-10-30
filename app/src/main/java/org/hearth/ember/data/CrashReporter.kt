package org.hearth.ember.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.hearth.ember.BuildConfig
import org.hearth.ember.EmberApp
import java.io.File

/**
 * When Ember crashes, the report is written to a file before the process dies. The next time the app starts and the phone is signed in, the
 * reports go to the person's own Hearth server (the Crashes page of its admin panel), and the files are deleted. Nothing is sent anywhere else.
 */
object CrashReporter {
    private const val KEEP = 10

    fun install(app: EmberApp) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, error, thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "crash-reports").also { it.mkdirs() }

    /** The report as the server wants it (`POST /api/crash-reports`). */
    internal fun report(app: EmberApp, error: Throwable, thread: String): JsonObject = buildJsonObject {
        put("source", "ember")
        put("kind", "crash")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("platform", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("message", error.toString())
        put("stack", Log.getStackTraceString(error))
        put("at", System.currentTimeMillis())
        put("context", buildJsonObject {
            put("thread", thread)
            put("inCall", runCatching { app.callController?.state?.value?.phase?.name ?: "none" }.getOrDefault("unknown"))
            put("appVisible", runCatching { app.repo.appVisible }.getOrDefault(false))
            put("freeMemoryMb", Runtime.getRuntime().freeMemory() / 1_048_576)
        })
    }

    private fun save(app: EmberApp, error: Throwable, thread: String) {
        val d = dir(app)
        File(d, "${System.currentTimeMillis()}.json").writeText(report(app, error, thread).toString())
        d.listFiles()?.sortedBy { it.name }?.dropLast(KEEP)?.forEach { it.delete() }
    }

    /** Send what is waiting. Files stay if the server cannot be reached; they go if the server says it does not want them. */
    suspend fun sendPending(app: EmberApp) = withContext(Dispatchers.IO) {
        val files = dir(app).listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return@withContext
        if (!app.prefs.crashReports.value) {
            files.forEach { it.delete() }
            return@withContext
        }
        if (!app.store.signedIn) return@withContext
        for (f in files) {
            val body = runCatching { EmberJson.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()
            if (body == null) { f.delete(); continue }
            try {
                app.api.sendCrashReport(body)
                f.delete()
            } catch (e: ApiException) {
                // 400 and 403: this server will never take it. Anything else (no connection, busy): try again next start.
                if (e.status == 400 || e.status == 403) f.delete() else break
            }
        }
    }
}
