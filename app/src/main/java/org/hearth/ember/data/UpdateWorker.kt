package org.hearth.ember.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.hearth.ember.MainActivity
import org.hearth.ember.R
import org.hearth.ember.ember
import org.hearth.ember.notify.Notifier

/**
 * Looks for a newer Ember about once an hour while the app is closed, and says so in a notification (once per version) that opens the update.
 * Switch it off under Notifications and sounds, "Tell me about updates".
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        val result = app.updater.check()
        // No answer (offline, GitHub busy): try again at the next turn, not in a loop.
        val newer = result.getOrNull()
        if (result.isFailure) return Result.success()
        if (newer == null) UpdateNotice.clear(applicationContext) else UpdateNotice.show(applicationContext, newer)
        return Result.success()
    }

    companion object {
        private const val NAME = "ember-update-check"

        /** Every hour, on any network. An older schedule (12 hours, before 0.9.7) is replaced by this one. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** The "a newer Ember is ready" notification. Shown once for each version, only when the person has not switched it off. */
object UpdateNotice {
    const val ID = 8120
    const val ACTION_OPEN_UPDATE = "org.hearth.ember.OPEN_UPDATE"

    fun show(context: Context, info: UpdateInfo) {
        val app = context.ember
        if (!app.updater.notifyEnabled) return
        if (app.updater.lastNotified == info.version) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notes = shorten(info.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), 220)
        val notification = NotificationCompat.Builder(context, Notifier.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle("Ember ${info.version} is ready")
            .setContentText(if (notes.isNotEmpty()) notes else "Tap to see what is new and update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (notes.isNotEmpty()) notes else "Tap to see what is new and update."))
            .setColor(0xFFE8590C.toInt())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
        app.updater.lastNotified = info.version
    }

    /** The release notes can be a paragraph; a notification gets the start of it, cut at a word. */
    fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', ' ', '.') + "…"
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
