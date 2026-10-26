package org.hearth.ember.call

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.hearth.ember.MainActivity
import org.hearth.ember.R
import org.hearth.ember.ember
import org.hearth.ember.notify.Notifier
import org.hearth.ember.notify.NotificationActionReceiver

/**
 * Keeps the microphone and camera alive while a call is going and the app is not in front. Android stops capture for apps that are not visible
 * unless a foreground service of the right type is running, so this is what lets you leave the call screen to read something else.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = ember
        val state = app.callController?.state?.value
        if (state == null || state.roomId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).setAction(Notifier.ACTION_OPEN_CHAT).putExtra(Notifier.EXTRA_ROOM, state.roomId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangup = PendingIntent.getBroadcast(
            this, 11, Intent(this, NotificationActionReceiver::class.java).setAction(CallController.ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle(state.title.ifBlank { "Call" })
            .setContentText("Ongoing call")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(if (state.startedAt > 0) state.startedAt else System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, "Hang up", hangup)
            .setColor(0xFFE8590C.toInt())
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (state.camOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        // Sharing the screen needs its own service type, declared before the capture starts.
        if (app.callController?.projectionWanted == true) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        runCatching { ServiceCompat.startForeground(this, 4, notification, type) }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
