package org.hearth.ember.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import org.hearth.ember.ember

/** Answers made from the notification shade: reply without opening the app, mark as read, decline a call. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.ember
        // From the call notification and the floating call window: no chat is named.
        when (intent.action) {
            org.hearth.ember.call.CallController.ACTION_HANGUP -> { app.callController?.hangup(); return }
            org.hearth.ember.call.CallController.ACTION_MUTE -> { app.callController?.toggleMic(); return }
        }
        val room = intent.getStringExtra(Notifier.EXTRA_ROOM) ?: return
        val pending = goAsync()
        when (intent.action) {
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    pending.finish()
                    return
                }
                app.scope.launch {
                    runCatching { app.repo.send(room, text) }
                    runCatching { app.api.markRead(room) }
                    app.notifier.cancelRoom(room)
                    pending.finish()
                }
            }
            Notifier.ACTION_READ -> app.scope.launch {
                runCatching { app.api.markRead(room) }
                app.notifier.cancelRoom(room)
                pending.finish()
            }
            CallNotifier.ACTION_DECLINE -> {
                app.realtime.declineCall(room)
                app.callNotifier.stop(room)
                pending.finish()
            }
            else -> pending.finish()
        }
    }
}

/** After a reboot or an update of Ember itself, start listening again if the person had notifications on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.ember
        runCatching { Delivery.apply(context) }
    }
}
