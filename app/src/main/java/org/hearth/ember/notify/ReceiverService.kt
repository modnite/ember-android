package org.hearth.ember.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.hearth.ember.data.ApiException
import org.hearth.ember.ember

/**
 * Keeps messages and calls arriving while the app is closed, with no Google push service: a long poll to the Hearth server's own
 * notification queue. The server answers within 25 seconds either with something to say or with nothing; this loops quietly.
 */
class ReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = ember
        if (!app.store.signedIn || !app.prefs.notificationsOn.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 2, app.notifier.serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job?.isActive != true) job = scope.launch { poll() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private suspend fun poll() {
        val app = ember
        var failures = 0
        while (scope.isActive && app.store.signedIn && app.prefs.notificationsOn.value) {
            try {
                if (app.store.notifyToken.isEmpty()) register()
                val token = app.store.notifyToken
                val batch = app.api.notificationEvents(token, app.store.notifyCursor)
                failures = 0
                for (event in batch.events) handle(event)
                if (batch.cursor > app.store.notifyCursor) {
                    app.api.ackNotifications(token, batch.cursor)
                    app.store.notifyCursor = batch.cursor
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 401: this receiver was revoked or the session ended. Register again once; if that fails, wait.
                if (e.status == 401) {
                    app.store.notifyToken = ""
                }
                failures++
                delay(minOf(60_000L, 2_000L * failures))
            } catch (e: Exception) {
                failures++
                delay(minOf(60_000L, 2_000L * failures))
            }
        }
        stopSelf()
    }

    private suspend fun register() {
        val app = ember
        val device = app.api.registerNotificationDevice(app.store.notifyDeviceId, "Ember for Android")
        app.store.notifyDeviceId = device.deviceId
        app.store.notifyToken = device.token
        app.store.notifyCursor = device.cursor
    }

    private fun handle(event: org.hearth.ember.data.NativeEvent) {
        val app = ember
        val payload = event.payload
        if (event.kind == "cancel") {
            payload.roomId?.let { app.notifier.cancelRoom(it) }
            return
        }
        // Looking at that chat right now: the screen already shows it.
        if (app.repo.appVisible && payload.roomId != null && app.repo.viewingRoom == payload.roomId) return
        // The live connection already delivered it and the app is in front.
        if (app.repo.appVisible) return
        if (payload.tag?.startsWith("call-") == true) {
            // Calls are announced by the realtime ring while the app is alive; the call screen (CallNotifier) handles the rest.
            app.callNotifier.ring(payload)
            return
        }
        if (payload.tag?.startsWith("nudge-") == true) {
            app.notifier.nudge(payload)
            return
        }
        app.notifier.message(payload, app.prefs.previews.value)
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
