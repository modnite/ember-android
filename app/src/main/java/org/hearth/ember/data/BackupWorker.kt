package org.hearth.ember.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.hearth.ember.R
import org.hearth.ember.ember

/**
 * A backup run. Both "Back up now" and the schedule end up here, so a backup is the same thing either way: it runs in the background as a
 * foreground job with a notification, keeps going while the app is closed or the screen is left, and stops only when it is cancelled
 * (the Cancel button on the settings page or in its notification) or has nothing more to do.
 */
class BackupRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun notification(text: String) = run {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a backup is running"; setShowBadge(false) })
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle("Backing up Ember")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(0, "Cancel", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
    }

    private fun foreground(text: String): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification(text))

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Backing up…")

    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        if (!app.store.signedIn || app.backup.settings.destinations.isEmpty()) return Result.success()
        // Being a foreground job is what keeps it running when the app is left; if Android will not allow that right now it still runs.
        runCatching { setForeground(foreground("Preparing…")) }
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        return try {
            val results = app.backup.runNow { text ->
                setProgressAsync(workDataOf("text" to text))
                runCatching { nm.notify(NOTIFICATION_ID, notification(text)) }
            }
            val failed = results.filter { !it.second.ok }
            Result.success(workDataOf("places" to results.size, "failed" to failed.joinToString("\n") { "${it.first.label}: ${it.second.message}" }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            Result.success(workDataOf("error" to (e.message ?: "The backup failed.")))
        }
    }

    companion object {
        const val CHANNEL = "backup"
        const val NOTIFICATION_ID = 7031
    }
}

/** The schedule: at the chosen time it starts a backup run, which is where the work (and its foreground notification) lives. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        if (!app.store.signedIn || app.backup.settings.schedule == Schedule.Off || app.backup.settings.destinations.isEmpty()) return Result.success()
        BackupScheduler.runNow(applicationContext)
        return Result.success()
    }
}

object BackupScheduler {
    private const val NAME = "ember-backup"
    private const val NOW = "ember-backup-now"

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

    /** Start a backup in the background. One at a time: asking again while one is running does nothing. */
    fun runNow(context: Context) {
        val settings = context.ember.backup.settings
        // A phone folder needs no connection; a server does.
        val network = if (settings.destinations.any { !it.isFolder }) (if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED) else NetworkType.NOT_REQUIRED
        val request = OneTimeWorkRequestBuilder<BackupRunWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Stop the backup that is running. This is the only way one stops before it is done. */
    fun cancelNow(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NOW) }

    /** The running backup, or the last one's outcome. */
    fun state(context: Context): Flow<WorkInfo?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW).map { it.firstOrNull() }

    /** Milliseconds from [nowMs] to the next time the clock reads [atMinute] minutes after midnight. */
    fun delayUntil(atMinute: Int, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs; set(java.util.Calendar.HOUR_OF_DAY, atMinute / 60); set(java.util.Calendar.MINUTE, atMinute % 60); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= nowMs) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - nowMs
    }
}
