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
        // Android wants startForeground within seconds of every start, even one that is about to end again (signed out, alerts off).
        ServiceCompat.startForeground(this, 2, app.notifier.serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!app.store.signedIn || !app.prefs.notificationsOn.value || !app.prefs.instantDelivery.value) {
            stopSelf()
            return START_NOT_STICKY
        }
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

    private suspend fun register() = NotifyPump.register(ember)

    private fun handle(event: org.hearth.ember.data.NativeEvent) = NotifyPump.handle(ember, event)

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}

/** What happens to a notification event once the server has handed it over: shared by the always-on receiver and the 15 minute check. */
object NotifyPump {
    suspend fun register(app: org.hearth.ember.EmberApp) {
        val device = app.api.registerNotificationDevice(app.store.notifyDeviceId, "Ember for Android")
        app.store.notifyDeviceId = device.deviceId
        app.store.notifyToken = device.token
        app.store.notifyCursor = device.cursor
    }

    fun handle(app: org.hearth.ember.EmberApp, event: org.hearth.ember.data.NativeEvent) {
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
        // An archived chat is quiet (the server already leaves it out; this covers one archived a moment ago).
        if (payload.roomId != null && app.repo.room(payload.roomId)?.archived == true) return
        if (payload.tag?.startsWith("nudge-") == true) {
            app.notifier.nudge(payload)
            return
        }
        app.notifier.message(payload, app.prefs.previews.value)
    }
}

/** The battery-friendly way: one look at the server's queue every 15 minutes, with nothing running (and nothing shown) in between. */
class NotifyWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.ember
        if (!app.store.signedIn || !app.prefs.notificationsOn.value) return Result.success()
        return try {
            if (app.store.notifyToken.isEmpty()) NotifyPump.register(app)
            val token = app.store.notifyToken
            val batch = app.api.notificationEvents(token, app.store.notifyCursor)
            for (event in batch.events) NotifyPump.handle(app, event)
            if (batch.cursor > app.store.notifyCursor) {
                app.api.ackNotifications(token, batch.cursor)
                app.store.notifyCursor = batch.cursor
            }
            Result.success()
        } catch (e: ApiException) {
            if (e.status == 401) app.store.notifyToken = ""
            Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Makes what runs match the settings: nothing when alerts are off, the always-on receiver for instant delivery, the 15 minute check otherwise. */
object Delivery {
    private const val NAME = "ember-notify-check"

    fun apply(context: Context) {
        val app = context.ember
        val work = androidx.work.WorkManager.getInstance(context)
        if (!app.store.signedIn || !app.prefs.notificationsOn.value) {
            work.cancelUniqueWork(NAME)
            ReceiverService.stop(context)
            return
        }
        if (app.prefs.instantDelivery.value) {
            work.cancelUniqueWork(NAME)
            runCatching { ReceiverService.start(context) }
        } else {
            ReceiverService.stop(context)
            val request = androidx.work.PeriodicWorkRequestBuilder<NotifyWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            work.enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
