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
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BackupWorker>(schedule.hours, TimeUnit.HOURS).setConstraints(constraints).build())
    }
}
