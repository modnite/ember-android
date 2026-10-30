package org.hearth.ember.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.hearth.ember.R
import org.hearth.ember.ember

/**
 * A backup started in the app belongs to the app: it runs in the app's scope and shows in the strip on every screen. This service exists only
 * for the time the app is left: it keeps the process alive with a notification (what the backup is doing, and Cancel), and it is stopped the
 * moment the app comes back to the front, where the strip takes over again. Nothing here runs the backup.
 */
class BackupKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext.ember
        if (intent?.action == ACTION_CANCEL) { app.backup.cancel(); stopSelf(); return START_NOT_STICKY }
        val now = app.backup.activity.value
        if (now == null) { stopSelf(); return START_NOT_STICKY }
        startAs(notification(now.text))
        scope.launch {
            app.backup.activity.collect { a ->
                if (a == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else getSystemService(NotificationManager::class.java).notify(ID, notification(a.text))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel() }

    private fun startAs(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a backup runs and the app is closed"; setShowBadge(false) })
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val cancel = PendingIntent.getService(this, 1, Intent(this, BackupKeepAliveService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ember).setContentTitle("Backing up Ember").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).setContentIntent(open)
            .addAction(0, "Cancel", cancel).build()
    }

    companion object {
        const val CHANNEL = "backup"
        const val ID = 7031
        const val ACTION_CANCEL = "org.hearth.ember.BACKUP_CANCEL"
    }
}

/**
 * The notification of a scheduled backup that runs while the app is closed. Android does not let a background job start a foreground service,
 * so this is an ordinary ongoing notification with progress and Cancel; it is posted while the backup runs and removed when it ends.
 */
object BackupNotice {
    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(BackupKeepAliveService.CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(BackupKeepAliveService.CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a backup runs and the app is closed"; setShowBadge(false) })
        }
    }

    fun show(context: Context, activity: BackupActivity) {
        channel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val cancel = PendingIntent.getService(context, 1, Intent(context, BackupKeepAliveService::class.java).setAction(BackupKeepAliveService.ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, BackupKeepAliveService.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ember).setContentTitle(if (activity.restore) "Restoring Ember" else "Backing up Ember").setContentText(activity.text)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).setContentIntent(open)
            .apply { if (activity.canCancel) addAction(0, "Cancel", cancel) }.build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(BackupKeepAliveService.ID + 1, n) }
    }

    fun clear(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java).cancel(BackupKeepAliveService.ID + 1) } }
}

object BackupKeepAlive {
    /** The app was left while a backup runs: keep it going with a notification. (Android may refuse a start from the background; the backup then simply goes on while the process lives.) */
    fun onLeft(context: Context) {
        if (context.ember.backup.activity.value?.restore == false) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BackupKeepAliveService::class.java)) }
        }
    }

    /** The app is back in front: the strip shows the backup again and the notification goes. */
    fun onReturned(context: Context) {
        runCatching { context.stopService(Intent(context, BackupKeepAliveService::class.java)) }
    }
}
