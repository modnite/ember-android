package org.hearth.ember.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import org.hearth.ember.MainActivity
import org.hearth.ember.R
import org.hearth.ember.ember
import org.hearth.ember.data.PushPayload

/** The incoming-call notification: a full-screen "call style" card with Answer and Decline, like any phone call. */
class CallNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    private fun allowed() =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ring(payload: PushPayload) {
        val roomId = payload.roomId ?: return
        ring(roomId, payload.title.ifBlank { "Incoming call" })
    }

    fun ring(roomId: String, callerName: String) {
        if (!allowed()) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_ANSWER)
            .putExtra(Notifier.EXTRA_ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val answer = PendingIntent.getActivity(context, roomId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Tapping the card (or the full-screen card appearing) shows the incoming-call screen; only the Answer button answers.
        val show = PendingIntent.getActivity(
            context, roomId.hashCode() + 2,
            Intent(context, MainActivity::class.java).setAction(Notifier.ACTION_OPEN_CHAT).putExtra(Notifier.EXTRA_ROOM, roomId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, roomId.hashCode() + 1,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(Notifier.EXTRA_ROOM, roomId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(callerName).setImportant(true).build()
        val style = if (Build.VERSION.SDK_INT >= 31) NotificationCompat.CallStyle.forIncomingCall(person, decline, answer) else null
        val builder = NotificationCompat.Builder(context, context.ember.notifier.callsChannel)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle(callerName)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .setFullScreenIntent(show, true)
            .setContentIntent(show)
            .setColor(0xFFE8590C.toInt())
        if (style != null) builder.setStyle(style)
        else {
            builder.addAction(0, "Decline", decline).addAction(0, "Answer", answer)
        }
        manager.notify("call:$roomId", CALL_ID, builder.build().apply { flags = flags or android.app.Notification.FLAG_INSISTENT }) // rings until answered or declined
    }

    fun stop(roomId: String) {
        manager.cancel("call:$roomId", CALL_ID)
    }

    companion object {
        const val CALL_ID = 3
        const val ACTION_ANSWER = "org.hearth.ember.ANSWER_CALL"
        const val ACTION_DECLINE = "org.hearth.ember.DECLINE_CALL"
    }
}
