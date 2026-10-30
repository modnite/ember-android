package org.hearth.ember.data

import kotlinx.coroutines.flow.collect
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import org.hearth.ember.ember

/**
 * The scheduled backup: runs in the background when Android finds the phone idle enough, on a suitable network, and does what "Back up now" does.
 * If the app happens to be open while it runs, the progress strip shows it like any other backup.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        if (!app.store.signedIn || app.backup.settings.schedule == Schedule.Off || app.backup.settings.destinations.isEmpty()) return Result.success()
        // While it runs (the app may be closed) its progress is in a notification, with Cancel.
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            app.backup.activity.collect { a -> if (a != null) BackupNotice.show(applicationContext, a) else BackupNotice.clear(applicationContext) }
        }
        return try {
            val results = app.backup.runNow()
            // A destination that could not be reached right now is tried again a little later; the settings page shows what happened either way.
            if (results.any { !it.second.ok } && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: BackupException) {
            Result.success()
        } finally {
            watcher.cancel()
            BackupNotice.clear(applicationContext)
        }
    }
}

object BackupScheduler {
    private const val NAME = "ember-backup"

    /** Make the schedule match the settings: nothing when it is off, otherwise one repeating job. */
    fun apply(context: Context, settings: BackupSettings) {
        val work = WorkManager.getInstance(context)
        val schedule = settings.schedule
        if (schedule == Schedule.Off) { work.cancelUniqueWork(NAME); return }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        // The first run is due at the chosen time of day (today or tomorrow), then every day, week or month after it. Android may run it a little later.
        val request = PeriodicWorkRequestBuilder<BackupWorker>(schedule.hours, TimeUnit.HOURS)
            .setConstraints(constraints).setInitialDelay(delayUntil(settings.atMinute, System.currentTimeMillis()), TimeUnit.MILLISECONDS).build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * "Back up now": a backup that belongs to the app. It runs in the app's own scope, so it carries on through every screen, with its
     * progress in the strip across the top; when the app is left a notification keeps it alive (see [BackupKeepAlive]) and it is back in the
     * strip when the app returns. Asking again while one is running does nothing. Only [cancel] stops it.
     */
    fun startManual(context: Context) {
        val app = context.ember
        if (app.backup.activity.value != null) return
        app.scope.launch {
            val message = try {
                val results = app.backup.runNow()
                val failed = results.filter { !it.second.ok }
                if (failed.isEmpty()) "Backed up to ${results.size} ${if (results.size == 1) "place" else "places"}." else failed.joinToString("\n") { "${it.first.label}: ${it.second.message}" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Backup cancelled."
            } catch (e: Exception) {
                e.message ?: "The backup failed."
            }
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Stop the backup that is running. This is the only way one stops before it is done. */
    fun cancel(context: Context) = context.ember.backup.cancel()

    /**
     * Has a scheduled backup been missed? True when automatic backups are on and the last good one is older than the schedule allows (plus a
     * little grace), unless something was tried in the last half hour, so a place that keeps failing is not retried at every opening.
     * Android may skip or delay the scheduled job (phone off, no network, battery low, Doze); this is the safety net.
     */
    fun overdue(settings: BackupSettings, nowMs: Long = System.currentTimeMillis()): Boolean =
        isOverdue(settings.schedule, settings.destinations.isNotEmpty(), settings.lastRuns.values, nowMs)

    /** The rule on its own, so it can be tested without a phone. */
    fun isOverdue(schedule: Schedule, hasPlaces: Boolean, runs: Collection<BackupRun>, nowMs: Long): Boolean {
        if (schedule == Schedule.Off || !hasPlaces) return false
        val lastGood = runs.filter { it.ok }.maxOfOrNull { it.at } ?: 0L
        val lastTry = runs.maxOfOrNull { it.at } ?: 0L
        val allowed = TimeUnit.HOURS.toMillis(schedule.hours) + TimeUnit.MINUTES.toMillis(30)
        return nowMs - lastGood > allowed && nowMs - lastTry > TimeUnit.MINUTES.toMillis(30)
    }

    /** Called whenever the app comes to the front: a missed backup starts now, in the app, instead of waiting for Android. */
    fun catchUp(context: Context) {
        val app = context.ember
        if (app.store.signedIn && overdue(app.backup.settings)) startManual(context)
    }

    /** Milliseconds from [nowMs] to the next time the clock reads [atMinute] minutes after midnight. */
    fun delayUntil(atMinute: Int, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs; set(java.util.Calendar.HOUR_OF_DAY, atMinute / 60); set(java.util.Calendar.MINUTE, atMinute % 60); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= nowMs) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - nowMs
    }
}
