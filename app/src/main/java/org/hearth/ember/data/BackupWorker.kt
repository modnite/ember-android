package org.hearth.ember.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.hearth.ember.ember

/** The scheduled backup: runs when Android finds the phone idle enough, on a suitable network, and does what "Back up now" does. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        if (!app.store.signedIn || app.backup.settings.schedule == Schedule.Off || app.backup.settings.destinations.isEmpty()) return Result.success()
        return try {
            val results = app.backup.runNow()
            // A destination that could not be reached right now is tried again a little later; the settings page shows what happened either way.
            if (results.any { !it.second.ok } && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: BackupException) {
            Result.success()
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

    /** Milliseconds from [nowMs] to the next time the clock reads [atMinute] minutes after midnight. */
    fun delayUntil(atMinute: Int, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs; set(java.util.Calendar.HOUR_OF_DAY, atMinute / 60); set(java.util.Calendar.MINUTE, atMinute % 60); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= nowMs) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - nowMs
    }
}
